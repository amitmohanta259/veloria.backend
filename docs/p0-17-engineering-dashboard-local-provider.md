# P0-17 — Engineering Dashboard, local development encryption & manual anomaly scan

> **Built and running.** The Engineering section exists in the Admin portal, the
> scan runs on demand, findings are stored with their schema identifiers encrypted,
> and an authorized engineer sees them decrypted server-side.
>
> **AWS KMS remains BLOCKED / ON HOLD.** The local key provider unblocks development
> and does not close that gate. It is development-only, and the application refuses
> to start if it is selected outside a local profile.

## 1. Engineering UI

Four pages in the existing Admin portal — same sidebar, layout, routing, tables,
cards, modals and loading states. No separate application:

```
Engineering
├── Dashboard              /admin/engineering
├── DB Data Fluctuation    /admin/engineering/fluctuation
├── Transaction 360        /admin/engineering/transaction-360
└── Compliance Data Gaps   /admin/engineering/compliance-gaps
```

## 2. Dashboard

Scan controls with typed filters (date from, date to, transaction, product,
domain), the summary cards, per-domain counts, the anomaly list with severity and
domain filters, and scan history.

**Empty state** before the first scan: *"No anomaly scan has been executed yet."*
**Zero-anomaly state**: *"No anomalies detected by the configured rules."* — never
"the system is compliant" or "the database is perfect", because a scanner can only
speak for the rules it runs.

Transportation shows a zero with an explanation rather than being hidden: its
pricing is UNDECIDED, so no rule exists to compare against.

## 3. Manual execution

```
Admin → RUN ANOMALY SCAN → confirmation → POST /engineering/anomaly-scans/run
   → 202 { scanId, status: QUEUED } → advisory lock → RUNNING
   → read-only rules → monitoring tables → COMPLETED | PARTIAL | FAILED
```

**No cron.** No `@Scheduled`, no startup hook, no automatic retry, no synchronous
variant. The only `@Scheduled` in the codebase is the pre-existing 03:00 session
cleanup, which predates this work and is unrelated.

The HTTP request does not stay open for the scan. `ResponseCode.ACCEPTED` was added
and `AppController.statusOf` maps it to 202 — the existing response envelope, not a
parallel framework.

### Executor

A dedicated `EngScan-` executor: core 1, max 1, **queue capacity 0**, graceful
shutdown. Deliberately not the shared `taskExecutor`, which serves other features
with a 500-deep queue — a multi-minute database scan parked there would contend
with them, and a queue would only hold work the advisory lock has already decided
must not run.

## 4. Scan lifecycle

```
QUEUED → RUNNING → COMPLETED
                 → PARTIAL    ≥1 independent rule failed, the rest finished
                 → FAILED     the scan could not proceed
```

Enforced in `ScanStatus`, in the shape of the existing `PaymentStatus` and
`RefundStatus`. No backward transition: `COMPLETED → RUNNING`, `PARTIAL → RUNNING`,
`FAILED → RUNNING` and even `QUEUED → COMPLETED` are all rejected. A re-run is
always a new scan id.

**Immutable scope.** `scope_from`, `scope_to`, `scope_transaction_id`,
`scope_product_id` and `domains` are written when the scan is created and marked
`updatable = false`. The running scan reads its own persisted scope, never the
request, so changing a dashboard filter mid-scan cannot affect it.

**Single active scan** — see §14.

## 5. DB Data Fluctuation

Thirteen aggregates — orders, order items, invoices, payments, refunds, returns,
journals, journal lines, SALE, REVERSAL, AR, output GST, inventory rows — with the
delta and the count of anomalies in the related domain.

A delta is reported as movement, not as a defect: a legitimate new order moves
several of these. Only a deterministic rule decides that something is wrong.

**Known limit, stated on the page rather than hidden:** per-scan aggregate
snapshots are not stored yet, so the previous column equals the current one and
every delta reads nil. Inventing a previous figure would make every metric look
like it had changed.

## 6. Transaction 360

Search by order code. Shows financial reconciliation, anomalies on that
transaction, a timeline, and the order, GST, invoice, items, payments, returns,
refunds and journals.

**Actual records only.** A section with no rows says "None recorded"; a lifecycle
event with no stored timestamp is absent rather than inferred. Reconciliation uses
the approved waterfall from stored snapshots, with the COD charge grossing as
`taxable + tax` — the one expression correct under either tax basis (P0-13).

Reached in one click from any anomaly carrying a transaction id.

## 7. Compliance Data Gaps

Only checks the application already enforces, so nothing is newly declared
mandatory: place of supply on a finalised taxed order, HSN on a taxed line, SAC on
a taxed COD charge.

The wording is deliberate. A gap is missing information, classified as a potential
compliance risk. The page says in terms that this is *not* a determination that a
statutory requirement has been breached.

## 8. Anomaly rules

Nineteen rules, all reading through the read-only datasource:

| Domain | Rules |
|---|---|
| GST | `GST_TOTAL_MISMATCH` · `GST_SUPPLY_TYPE_CONFLICT` · `COD_FEE_ARITHMETIC_MISMATCH` |
| Compliance | `ORDER_PLACE_OF_SUPPLY_REQUIRED` · `ORDER_ITEM_HSN_REQUIRED` · `COD_FEE_SAC_REQUIRED` |
| Accounting | `JOURNAL_UNBALANCED` · `JOURNAL_LINE_ORPHAN` · `SALE_JOURNAL_DUPLICATE` · `AR_MISMATCH` |
| Payment | `COLLECTION_EXCEEDS_INVOICE` · `PAYMENT_WITHOUT_ORDER` |
| Return / refund | `REFUND_EXCEEDS_COLLECTED` · `REFUND_BEFORE_VERIFICATION` · `RETURN_QUANTITY_EXCEEDS_PURCHASED` · `COD_REFUND_WITHOUT_APPROVED_DESTINATION` |
| Inventory | `INVENTORY_RELEASE_EXCEEDS_CONSUMPTION` |
| Invoice | `INVOICE_TOTAL_MISMATCH` · `INVOICE_SUPPLY_TYPE_MISMATCH` |

Comparisons are exact integer paise. No rule carries a rounding tolerance, because
a tolerance would hide the one- and two-paise reconciliation failures these checks
exist to find — which is exactly what the first real scan surfaced.

**Deliberately absent:** transportation and gateway-customer-charge rules. Both
have no approved expected value (P0-14 records them UNDECIDED), so a rule comparing
against one would invent the business rule it claims to test.

### Two false positives, found and fixed

The first scan on real data reported 14 anomalies: 7 duplicate SALE journals and 7
AR mismatches, each off by exactly 4×. A uniform factor across every order is a
signal of a broken query, not broken data, and it was:

- **`SALE_JOURNAL_DUPLICATE`** counted every SALE journal regardless of status.
  Three of each order's four are `REVERSED` — superseded by a re-posting, not
  duplicated. Now `POSTED` only.
- **`AR_MISMATCH`** summed account 1100 over SALE and COD_FEE journals but not the
  21 REVERSAL journals, because a reversal keys on the journal it reverses rather
  than on the order. It counted four debits and none of the three reversing
  credits. Now a union brings reversals into the same order's balance.

After the fix: 3 findings, each a genuine 1–2 paise difference between the standing
SALE journal and the order's own invoice total.

### Deduplication

A fingerprint over `ruleId`, `entityType`, `entityId` and `transactionId` — **never**
over the encrypted values, so it cannot be used to confirm a guess about the schema.
A unique index on it makes deduplication a database guarantee. A persistent anomaly
keeps its `first_detected_at` and one row; `last_detected_at` and
`occurrence_count` advance.

**A counting bug this exposed:** `anomaliesFound` was first computed as
`WHERE scan_id = this scan`. Because a re-detected anomaly keeps the scan that first
saw it, every scan after the first reported zero while three were open. The count is
now what the scan actually detected.

## 9. Encryption format

```
AES-256-GCM · 256-bit data key · fresh 12-byte nonce per value · 128-bit tag
```

Stored per finding:

```
encrypted_table_name · table_nonce · table_auth_tag
encrypted_column_name · column_nonce · column_auth_tag
encrypted_data_key · key_provider · key_version · algorithm
```

The table and column share a key version but never a nonce — two values means two
nonces, because nonce reuse under one key destroys GCM's guarantee outright.

Only the table and column names are encrypted. `rule_id`, `severity`, `domain`,
`transaction_id`, `entity_id` and timestamps stay plaintext, which is what keeps
the list filterable without decrypting anything.

## 10. Local key provider

```
engineering.crypto.provider=local          # never hardcoded in Java
engineering.crypto.local.key-directory=    # empty → ~/.veloria/engineering-keys/
engineering.crypto.local.require-secure-permissions=true
```

```
local master key (file, 0600, outside every repository)
        ↓ AES-256-GCM wrap, hour stamp as AAD
hourly AES-256 data key → version 2026-09-28-13
        ↓
AES-256-GCM encrypt table / column
```

The hourly key is **derived** from the master key and the hour stamp rather than
generated randomly and stored. That is what makes an old finding readable: hour 13's
key needs only the master key and the string `2026-09-28-13`, so there is no per-hour
key file to lose and nothing to delete on rotation. The wrapped key still travels
on the finding, so the same code path works unchanged for KMS — where the key really
is random and really must travel with the record.

### Key storage and Git protection

```
~/.veloria/engineering-keys/local-master.key     mode 0600, generated on first use
```

Never in `src/main/resources`, `src/test/resources`, a repository root, the frontend,
or any YAML. `EngineeringCryptoConfig` refuses a key directory inside a project
checkout outright — an ignore rule is a second line of defence, not the first.

Verified with `git check-ignore` (§62):

```
veloria.backend/.gitignore    .veloria/  engineering-keys/  *.key  *.keystore
~/.gitignore                  .veloria/  engineering-keys/  *.key  *.keystore
```

The second entry matters and was easy to miss: the **home directory is itself a git
worktree** (one commit), so the documented default path sits inside a repository.
Without that rule a `git add -A` in `$HOME` would stage the master key.
`git check-ignore -v ~/.veloria/engineering-keys/local-master.key` now resolves to
`.gitignore:6:.veloria/`, and the file is untracked.

The key is never printed, never logged (only its path), and never sent to the
frontend.

## 11. Admin decryption

```
Engineering UI → GET /engineering/anomalies/{id} → authorization
   → load the finding's wrapped data key → unwrap → AES-GCM decrypt → safe DTO
```

No generic decrypt endpoint. Decryption happens only inside an authorized retrieval
of one specific anomaly.

| Reader | Sees |
|---|---|
| `ENGINEERING_VIEW` | the real table and column, decrypted server-side |
| `ENGINEERING_ANOMALY_VIEW` only | `ENCRYPTED`, and no ciphertext at all |
| key version unrecoverable, or tampering | `DECRYPTION_UNAVAILABLE` |

Never a fallback to plaintext, Base64, another key or a guess. A tampered
ciphertext, a tampered tag and a wrong nonce are indistinguishable by design — an
integrity failure is not a decryption result.

The DTO carries no ciphertext, nonce, tag, wrapped key or key version. A Playwright
test captures every Engineering response and fails if any of those strings appears.

### Audit

```
AUDIT RUN_ANOMALY_SCAN        scanId, triggeredBy, requestedAt, scope
AUDIT ENGINEERING_SCHEMA_DECRYPT   adminUser, anomalyId, scanId, operation
```

Never a password, token, AWS secret, KMS key or database password — and not the
decrypted identifiers either, since logging them would defeat encrypting them.

## 12. Read-only architecture

```
Dashboard → Scan API → AnomalyRules → engineeringReadOnlyJdbc
                                    → veloria_scan_ro (SELECT only)
findings → primary datasource → engineering_anomaly_* only
```

The guarantee is the PostgreSQL role, not application discipline.
`@Transactional(readOnly = true)` sets a JDBC flag and disables dirty checking, but
a native `UPDATE` on that connection still reaches the server; a privilege the role
does not have cannot be exercised by any statement, however written.

Pool size **2**, not a copy of the business pool: one scan runs globally, so one
connection plus a spare. `max_connections` 100 less 3 superuser-reserved leaves 97;
at 20 business + 2 engineering per instance, three instances use 66 and four use 88.

## 13. Authorization

Five permissions in the existing `GstPermission`, expanded from the token's
`gst_roles` claim by the existing converter:

```
ENGINEERING_VIEW · ENGINEERING_ANOMALY_VIEW · ENGINEERING_ANOMALY_RUN
ENGINEERING_TRANSACTION_AUDIT · ENGINEERING_COMPLIANCE_VIEW
```

Three roles: `ENGINEERING_ANALYST` (findings only — no schema, no scan),
`ENGINEERING_VIEWER` (adds schema and transaction audit), `ENGINEERING_ADMIN`
(adds run).

**Its own filter chain**, ordered ahead of the admin-token chain:

```java
.securityMatcher(ENGINEERING_PATHS)
.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
```

This follows the GST chain, not the admin-token chain. The latter is `permitAll()`
at the HTTP layer with all enforcement in `@PreAuthorize` — which fails open if an
endpoint ever forgets the annotation. For a feature whose purpose is to show where
the data is wrong, the failure mode has to be a 401. The method annotations stay as
well, and are what separate running a scan from reading one.

No `permitAll()` on any Engineering path.

**Filter safety.** Every scan and query parameter is typed and validated: ISO dates,
an order code, a product id, a domain enum. There is no parameter through which a
table name, a column name, a WHERE clause or SQL could reach the database — and
none accepting a schema identifier, which is why the anomaly list filters on
severity, domain, status and transaction instead.

## 14. Concurrency

Two mechanisms, because they answer different questions:

- **`pg_try_advisory_lock`** guarantees only one scan *executes*, across instances.
  Session-scoped, so PostgreSQL releases it if the instance dies and a crashed scan
  cannot wedge the feature permanently.
- **A partial unique index** guarantees only one scan is *created*:
  ```sql
  CREATE UNIQUE INDEX uq_eng_scan_single_active
  ON engineering_anomaly_scan ((status IS NOT NULL))
  WHERE status IN ('QUEUED', 'RUNNING');
  ```

The second was added because the first is not enough. A read-then-insert loses every
race: a concurrency test fired three simultaneous requests and got one 202 and two
**500s** from the `scan_number` unique index, where the contract requires 409. The
index makes the decision atomic, and the service tells the two constraints apart —
`uq_eng_scan_single_active` is a 409 and must not be retried; `uq_eng_scan_number`
is a number collision and is retried with the next one.

`ResponseCode.CONFLICT` now maps to **HTTP 409** rather than 400. A conflict is not a
malformed request, and a client needs to tell "you sent something invalid" from "this
is temporarily not possible".

Verified: three simultaneous requests → **one 202, two 409 SCAN_ALREADY_RUNNING**,
one scan created. Eight callers in the backend test → one accepted, seven refused.

## 15. Security

| Check | Result |
|---|---|
| Keys in source | none |
| Keys in Git | none — ignored in both repositories and in `$HOME` |
| Keys in the frontend | none; a test asserts no key material reaches the browser |
| Keys in logs | only the file path is logged |
| Plaintext table/column at rest | none; asserted against the stored row |
| Generic decryption endpoint | none |
| Customer payment secrets, card/CVV | none introduced |
| Production using local encryption | refused at startup |

**Fail-fast.** `engineering.crypto.provider=local` outside a local profile throws at
startup with a message naming the misconfiguration. Startup, not first use: a runtime
check would let the application serve traffic and only break when the first anomaly
was written, by which time the misconfiguration is already deployed.

`engineering.crypto.provider=aws-kms` constructs `AwsKmsKeyProvider`, which refuses
with the exact dependency list until Gate 1 is closed. It does not silently degrade
to local keys.

## 16. Test coverage

| Suite | Result |
|---|---|
| `SchemaCipherTest` | **11 tests** |
| `EngineeringScanPostgresTest` | **9 tests** |
| Playwright `engineering.spec.js` | **9 tests** |

Encryption: round trip · unique nonce over 200 operations · tampered ciphertext ·
tampered tag · wrong key version · wrong master key · hourly rotation with both
versions still decryptable · same hour reuses its version · key file 0600 · key
outside the source tree · master key survives a restart.

Scan: completes and reaches a terminal state · business data untouched including the
journal checksum · the read-only role refuses UPDATE, DELETE, INSERT and TRUNCATE ·
eight simultaneous requests produce one scan · terminal scans are final · scope is
immutable · repeated scans deduplicate and still report what they detected ·
findings carry encrypted schema that decrypts only for an authorized reader · the
scanner only ever writes OPEN.

UI: menu · dashboard · confirmation and a 202 through to a terminal state ·
anomaly detail with decrypted table and column · no key material in any response or
in browser storage · an analyst sees `ENCRYPTED` · anonymous is 401 and an analyst
running a scan is 403 · all four pages open · Transaction 360 on a real order.

## 17. KMS future migration

Nothing needs redesigning. `engineering.crypto.provider=aws-kms` switches the
provider; `AnomalyScanService`, `AnomalyRules`, `EngineeringQueryService`, the
dashboard, Transaction 360 and Compliance Data Gaps all go through
`SchemaCipherService` and never touch a provider.

When Gate 1 closes:

```
1. Verify KMS          2. Verify IAM (kms:GenerateDataKey, Decrypt, DescribeKey)
3. Verify GenerateDataKey                4. Verify Decrypt
5. Implement AwsKmsKeyProvider           6. Test historical compatibility
7. Migrate or re-encrypt if required     8. Disable local outside local
```

`key_provider` is stored on every finding precisely so that step 6 is possible:
findings written now are tagged `LOCAL` and are distinguishable from KMS-encrypted
ones. A `LOCAL` finding read under the KMS provider reports
`DECRYPTION_UNAVAILABLE` rather than being attempted — the honest answer, since a
KMS key cannot unwrap it.

**Local-encrypted data is not production-valid.** The UI says so on every anomaly:
*"Key provider LOCAL · development encryption, not production"*.

## 18. Known limitations

```
BLOCKED    AWS KMS — Gate 1 remains ON HOLD. The local provider is a development
           unblock, not a closure. Production and staging still require KMS.

UNDECIDED  Anomaly and scan retention. No organizational policy exists and
           inventing one is forbidden. Nothing is auto-deleted.

UNDECIDED  Transportation and gateway-customer-charge rules, while their business
           rules remain UNDECIDED (P0-14).

LIMIT      DB Data Fluctuation has no stored per-scan snapshots, so deltas read
           nil. Stated on the page rather than papered over.

LIMIT      The local provider's master key has no rotation of its own, no audit
           trail and no hardware protection. It is one file on one machine.

NOTE       The read-only role's local password lives in the gitignored
           application-local.yaml beside the existing postgres one. A deployed
           environment supplies it from VELORIA_SCAN_RO_PASSWORD.

FINDING    Three genuine 1–2 paise AR discrepancies exist in the seeded ledger:
           the standing SALE journal debits more than the order's invoice total.
           Detected, recorded, and deliberately NOT repaired — the scanner
           detects and reports, it does not fix.
```
