# P0-16 — Security gate verification & Engineering readiness

> **OUTCOME A — infrastructure unavailable at Gate 1.**
>
> Gate 1 (AWS KMS) is **BLOCKED**. Gates 2, 3 and 4 were verified empirically and
> **PASS**. Per §22, the Engineering Dashboard and anomaly system were not built.
>
> This phase verified rather than assumed: the read-only role and the advisory lock
> were tested against the live database, not reasoned about.
>
> Baseline unchanged: checksum `eea1e82cfa93ce16f7963524e3eaf56f`, SALE 28,
> REVERSAL 21, AR ₹19,796.33, 13 account balances.

## Gate summary

| Gate | Status | How it was established |
|---|---|---|
| 1 · AWS KMS | **BLOCKED** | No SDK, no credentials in the whole provider chain, no CMK |
| 2 · Read-only PostgreSQL | **PASS** | Role created; 13-statement permission matrix executed |
| 3 · Advisory lock | **PASS** | Two concurrent sessions; mutual exclusion and auto-release proven |
| 4 · Authorization | **PASS** | Framework inspected; an authenticated-chain precedent exists |

## 1. KMS verification — BLOCKED

Checked every element §3 lists, and the whole AWS credential provider chain:

| Requirement | Result |
|---|---|
| KMS SDK dependency | **absent** — `grep -c kms pom.xml` = **0**. The AWS SDK is present for `s3`, `ses`, `sns` only |
| `~/.aws/credentials` | **absent** |
| `~/.aws/config` | **absent** |
| `AWS_*` environment variables | **none** in the shell; the running backend was started with placeholder values so it would boot |
| Instance / workload identity (IMDS) | **unreachable** — HTTP 000; not an EC2 or ECS runtime |
| AWS region | **present** — `us-east-2`, configured in all four profiles |
| KMS key id / `arn:aws:kms:…` | **none** anywhere in configuration or code |
| KMS client configuration | **none** |
| IAM policy to inspect | **none available** |
| Deployment configuration | `Dockerfile` sets only `SPRING_PROFILES_ACTIVE=dev` and passes no AWS values; they come from the runtime environment |
| Any encryption code in the application | **none**. The single `SecretKeySpec` is HMAC-SHA256 verifying dev JWTs under the `local` profile |

No secret values were read or printed — only the presence or absence of each
capability.

**The region is the one part already in place.** What is missing is the SDK
dependency, a symmetric customer-managed key, and credentials carrying the three
runtime permissions.

### Consequently not performed

- **§7 KMS test** — no key to call `GenerateDataKey` against.
- **§9 historical key recovery** — cannot generate two hourly data keys without KMS.
- **§10 failure modes** — testing "permission denied" and "invalid key ARN" requires
  a working baseline to deviate from.

These are not skipped; they are unreachable, and claiming otherwise would be false.

## 2. Envelope encryption — designed, not built

```
AWS KMS symmetric CMK
      ↓ GenerateDataKey(KeySpec = AES_256, EncryptionContext = …)
plaintext data key (memory only, zeroed after use) + encrypted data key
      ↓ AES-256-GCM, fresh 12-byte nonce, 16-byte tag
encrypted table name · encrypted column name
      ↓ persisted with the ENCRYPTED data key and its version
```

The CMK is never the hourly key and is never rotated hourly. Because each finding
stores its own encrypted data key, historical decryptability depends only on the CMK
remaining available — there is no archive of thousands of raw keys to manage, which
is the property that makes §9 satisfiable at all.

Stored envelope:

```json
{ "v": 1, "kv": "2026-09-28-13", "alg": "AES-256-GCM",
  "nonce": "…", "ct": "…", "tag": "…", "edk": "…" }
```

`kv`, `alg` and `edk` are metadata or wrapped material — safe at rest, useless
without the CMK.

## 3. Hourly data-key rotation

```
2026-09-28-13 → 2026-09-28-14 → 2026-09-28-15
```

A new data key per clock hour, from `GenerateDataKey`. Findings written in an hour
carry that hour's version and its encrypted data key. Rotation writes nothing to
existing rows — re-encrypting history hourly would put the whole table at risk of a
partial failure every hour for no benefit.

Explicitly rejected, per §6: environment secret + HKDF, a static AES key, a database
master key, a frontend key, a homemade vault.

## 4. Historical key recovery

The §9 proof (encrypt under V1 and V2, rotate, decrypt both) **could not be run** —
Gate 1 blocked. The design satisfies it structurally: the encrypted data key travels
with the finding, so recovery needs only `kms:Decrypt` on the CMK, not a key store
that could lose an hour.

The operational rule that must accompany it: **never schedule deletion of the CMK,
and never disable it**, or every historical finding becomes permanently
undecryptable. Key *rotation* of the CMK is safe — AWS retains prior CMK backing
material for decryption — but deletion is irreversible.

## 5. IAM

Least privilege for the runtime role, exactly the three §4 actions:

```
kms:GenerateDataKey · kms:Decrypt · kms:DescribeKey
```

on the single CMK ARN, conditioned on the encryption context. Not `kms:*`, and no
key-administration action — `kms:ScheduleKeyDeletion`, `kms:DisableKey`,
`kms:PutKeyPolicy` and `kms:CreateKey` stay with a separate administrative
principal the application cannot assume.

### Encryption context (§8)

```
purpose     = engineering-anomaly-schema
environment = <the actual deployment environment>
```

Supplied identically on encrypt and decrypt, so a ciphertext cannot be decrypted
under a different purpose or environment. It carries no password, credential,
customer or payment data — encryption context is authenticated but **not** encrypted,
and is visible in CloudTrail.

## 6. PostgreSQL read-only role — PASS

No read-only role existed. Roles found before this phase:

```
amitkumarmohanta   superuser
postgres           superuser
veloria_user       not superuser
```

A least-privilege role was created and verified:

```sql
CREATE ROLE veloria_scan_ro LOGIN PASSWORD <generated, not recorded in the repo>;
REVOKE ALL   ON SCHEMA public FROM veloria_scan_ro;
GRANT CONNECT ON DATABASE postgres TO veloria_scan_ro;
GRANT USAGE   ON SCHEMA public     TO veloria_scan_ro;
GRANT SELECT  ON ALL TABLES IN SCHEMA public TO veloria_scan_ro;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO veloria_scan_ro;
```

### The §14 matrix, executed as `veloria_scan_ro`

On a controlled probe table created for the test:

| Statement | Result |
|---|---|
| `SELECT` | **1 row** — succeeds |
| `INSERT` | `ERROR: permission denied for table engineering_ro_probe` |
| `UPDATE` | `ERROR: permission denied` |
| `DELETE` | `ERROR: permission denied` |
| `TRUNCATE` | `ERROR: permission denied` |
| `ALTER TABLE` | `ERROR: must be owner of table` |
| `DROP TABLE` | `ERROR: must be owner of table` |
| `CREATE TABLE` in `public` | `ERROR: permission denied for schema public` |

On real business tables — reads succeed, and every mutation is refused. Each
mutation was wrapped in `BEGIN … ROLLBACK` so that even an unexpected success could
not persist:

| Statement | Result |
|---|---|
| `SELECT journal_entry` | **54 journals** |
| `SELECT customer_order` | **7 orders** |
| `UPDATE journal_entry` | `ERROR: permission denied for table journal_entry` |
| `DELETE customer_order` | `ERROR: permission denied for table customer_order` |
| `INSERT journal_entry_line` | `ERROR: permission denied for table journal_entry_line` |
| `TRUNCATE payment_attempt` | `ERROR: permission denied for table payment_attempt` |
| `UPDATE gst_configuration` | `ERROR: permission denied for table gst_configuration` |
| `DELETE payment_refund` | `ERROR: permission denied for table payment_refund` |
| `UPDATE inventory_product` | `ERROR: permission denied for table inventory_product` |

The probe table was dropped afterwards. The role was kept as the gate artifact. Its
generated password is not stored in either repository; when a datasource is
eventually wired, set it from an environment variable and rotate it with
`ALTER ROLE veloria_scan_ro PASSWORD …`.

This is a database-enforced guarantee, not a Spring hint.
`@Transactional(readOnly = true)` flags the JDBC connection and disables Hibernate
dirty checking, but a native `UPDATE` on such a connection still reaches PostgreSQL —
which is why §12 is right to insist the role itself be restricted.

**One consequence to note:** the grant is `ON ALL TABLES IN SCHEMA public`, so when
the Engineering monitoring tables are created they will also be readable by this
role — harmless, since it can never write them. Findings are written through the
**primary** datasource, never the read-only one.

## 7. Connection budget

```
max_connections                 100
superuser_reserved_connections    3
reserved_connections              0
usable by applications           97

business pool   local profile     10
business pool   dev / default     20
engineering pool proposed          2     one scan runs globally (advisory lock)
```

| Instances | Business | Engineering | Total | Headroom of 97 |
|---|---|---|---|---|
| 1 | 20 | 2 | 22 | 75 |
| 2 | 40 | 4 | 44 | 53 |
| 3 | 60 | 6 | 66 | 31 |
| 4 | 80 | 8 | 88 | 9 — **too tight** |

**The Engineering pool must be 2, not a copy of the business pool.** Only one scan
may run globally, so one connection plus one spare is sufficient, and a second
20-connection pool per instance would halve the deployable instance count.

This is not hypothetical: an earlier phase hit `FATAL: sorry, too many clients`
when five Spring test contexts each held a pool (160 requested against 100), which
is exactly this arithmetic going wrong.

## 8. Advisory lock — PASS

Tested with concurrent sessions against the live database, on a feature-scoped key:

| Step | Result |
|---|---|
| Session A `pg_try_advisory_lock` | **true** — acquired |
| Session B, concurrent, same key | **false** — refused, does not block or queue |
| A `pg_advisory_unlock` | **true** |
| Session C after release | **true** — the lock is reusable |
| Session D acquires, then its backend exits | — |
| Session E after D's session died | **true** — PostgreSQL released it automatically |

Mutual exclusion holds across sessions, which means across application instances,
and a crashed instance cannot wedge the feature permanently. That last property is
why a session-scoped advisory lock is the right primitive here and a JVM lock is not.

The second caller maps to `409 CONFLICT` / `SCAN_ALREADY_RUNNING`.

## 9. Authorization — PASS, with one requirement

The existing framework is reusable as-is:

```
GstPermission          permission strings
authentication converter   expands the token's gst_roles claim into authorities
@PreAuthorize          enforced per endpoint, used throughout the admin controllers
role_permissions       the Admin UI's module × {view, create, edit, delete} directory
```

**The requirement comes from how the filter chains are arranged.** There are three:

| Chain | Matcher | HTTP layer |
|---|---|---|
| Order 1 — GST | `GST_PATHS` | `anyRequest().authenticated()` |
| Order 2 — admin token | `ADMIN_TOKEN_PATHS` | `permitAll()`, token decoded so `@PreAuthorize` can judge |
| Application-wide | everything else | `permitAll()` |

So outside the GST paths, **all real authorization is method-level**. That works, but
it fails open: an Engineering controller method that forgets `@PreAuthorize` would be
publicly reachable, because the HTTP layer permits everything.

Engineering must therefore follow the **Order 1 pattern**, not Order 2 — its own
`securityMatcher` with `anyRequest().authenticated()`, *plus* `@PreAuthorize` on each
method. Defence in depth, and a forgotten annotation then yields a 401 rather than an
open endpoint. The precedent already exists in this codebase, so this is reuse.

The five permissions belong in `GstPermission` alongside the existing ones:

```
ENGINEERING_VIEW · ENGINEERING_ANOMALY_VIEW · ENGINEERING_ANOMALY_RUN
ENGINEERING_TRANSACTION_AUDIT · ENGINEERING_COMPLIANCE_VIEW
```

They were **not added in this phase**: with no Engineering endpoints they would be
unused constants, and §22 gates the endpoints on Gate 1.

`RUN ANOMALY SCAN` is gated on `ENGINEERING_ANOMALY_RUN`; a user holding only
`ENGINEERING_ANOMALY_VIEW` sees results and no button. No `permitAll()` on any
Engineering path.

## 10. Engineering dashboard — BLOCKED

Not built (§22). The design is unchanged from
`p0-16-engineering-manual-anomaly-monitoring.md` §1: Dashboard, DB Data Fluctuation,
Transaction 360, Compliance Data Gaps, with the dashboard carrying the run button and
the last-scan panel. Engineering is an admin-only module and is never exposed to
customer-facing clients.

## 11. Manual execution — BLOCKED

Not built. The model is fixed and unchanged:

```
authorized admin → RUN ANOMALY SCAN → POST /engineering/anomaly-scans/run
  → 202 Accepted { scanId, status: "QUEUED" } → advisory lock → RUNNING
  → read-only scan → monitoring tables → COMPLETED | PARTIAL | FAILED → dashboard polls
```

### §37 verified — there is no anomaly cron

```
@Scheduled occurrences in src/main/java:  1
  ClientSessionStore:111  "0 0 3 * * *"   pre-existing session cleanup
```

That is not an anomaly scan and predates this work. No cron, scheduled, startup or
automatic anomaly scan exists anywhere — satisfied by construction, since no scanner
was ever built.

### Async executor (§21)

`@EnableAsync` and a `taskExecutor` bean exist in `config/AsyncConfig.java`, but that
bean is shared (`ChatAsync-`, core 2 / max 10 / **queue 500**) and serves other
features. A multi-minute database scan must not sit in it, and an unbounded-ish
500-deep queue is the wrong shape for a job that may only ever have one instance.

Specified instead: a dedicated executor — core 1, max 1, **queue capacity 0**,
thread name prefix `EngScan-`, `setWaitForTasksToCompleteOnShutdown(true)` with a
timeout, and rejection surfaced rather than swallowed. The advisory lock already
guarantees one logical scan, so a queue would only hide a condition the lock has
already decided.

## 12. Scan state machine

```
QUEUED → RUNNING → COMPLETED
                 → PARTIAL    ≥1 independent rule failed, the scan otherwise finished
                 → FAILED     infrastructure failed before meaningful execution
```

No backward transition. `COMPLETED → RUNNING`, `PARTIAL → RUNNING` and
`FAILED → RUNNING` are all rejected; a re-run is always a new `scanId`. Enforced in
an enum shaped like the existing `PaymentStatus` and `RefundStatus`, which already do
exactly this, rather than checked ad hoc at call sites.

### Immutable scope (§27)

`scopeFrom`, `scopeTo`, `domains`, `triggeredBy` are persisted when the scan record
is created, and the running scan reads its own persisted scope. Changing dashboard
filters mid-scan cannot affect it.

## 13. Anomaly storage — NOT CREATED

Per §29 and §16 of the previous spec, the tables are not created until the encryption
gate passes. The model:

```
engineering_anomaly_scan
  id · uuid · scan_number · triggered_by · requested_at · started_at · completed_at
  status · scope_from · scope_to · domains
  rules_executed · rules_failed · transactions_scanned · anomalies_found
  critical_count · high_count · medium_count · low_count · failure_reason
  created · modified

engineering_anomaly_finding
  id · uuid · scan_id → engineering_anomaly_scan
  fingerprint · severity · domain · rule_id
  entity_type · entity_id · transaction_id · order_id · product_id
  actual_value · expected_value · description
  encrypted_table_name · encrypted_column_name · encrypted_data_key
  key_version · nonce · auth_tag
  detected_at · first_detected_at · last_detected_at · occurrence_count · status
  created · modified

engineering_anomaly_evidence
  id · uuid · anomaly_id → engineering_anomaly_finding
  evidence_type · evidence_reference · encrypted_payload · payload_key_version · created
```

Following project convention: Liquibase YAML changesets registered in
`db/master.yaml`, `bigint` identity keys with a `uuid` beside them, a human-readable
number under a unique constraint (as `journal_number` and `order_code` have), money
in integer paise, `created`/`modified` audit columns.

Only IDs that actually exist are populated (§32) — a finding never fabricates an
`orderId` or `productId` to fill the column.

## 14. Encrypted table/column

`encrypted_table_name` and `encrypted_column_name` never hold plaintext. Only those
two values are encrypted; `rule_id`, `severity`, `domain`, `transaction_id`,
`entity_id`, `scan_id` and timestamps stay plaintext so findings remain filterable
without decrypting anything.

The deduplication fingerprint is computed over non-sensitive identifiers only —
`ruleId`, `entityType`, `entityId`, `transactionId`, a stable field reference — and
deliberately **not** over the encrypted values, so it leaks nothing about schema
names. `firstDetectedAt` is preserved, `lastDetectedAt` advances, `occurrenceCount`
increments; repeated manual scans cannot multiply one logical finding.

## 15. Admin decryption

```
Engineering UI → authorized anomaly-detail endpoint (by anomaly id)
  → authorization check → load the finding's encrypted data key
  → kms:Decrypt (server-side, encryption context supplied)
  → AES-GCM decrypt → safe DTO { table, column }
```

No generic decrypt endpoint. The browser never receives a KMS key, an AES key, a
plaintext data key, or raw ciphertext with its key metadata; nothing is decrypted in
JavaScript or stored in `localStorage` or `sessionStorage`. An unauthorized caller
sees table and column as `ENCRYPTED`, with no ciphertext at all.

A key version that cannot be loaded, or a GCM authentication failure on a tampered
ciphertext or tag, yields exactly `DECRYPTION_UNAVAILABLE` — never a fallback to
plaintext, Base64, another key or a guess. Authentication failures are surfaced, not
swallowed (§7).

Audited per decryption: `adminUser`, `timestamp`, `anomalyId`, `scanId`, `operation`.
Audited per run: `scanId`, `triggeredBy`, `startedAt`, `completedAt`, `status`,
`operation = RUN_ANOMALY_SCAN`. Never a password, token, AWS secret, KMS key,
database password or payment credential.

## 16. Transaction 360 — BLOCKED

Not built. Traversal designed over the real relationships discovered in §17, using
actual records only — a missing event is shown as missing, never fabricated.

## 17. Schema discovery (§15)

Real table names, verified against the live database. No name here is invented:

| Domain | Tables |
|---|---|
| Products | `inventory_product` (26 cols) · `inventory_product_variants` · `inventory_product_size_stock` · `inventory_product_images` · `inventory_category` · `inventory_sub_category` · `inventory_collection` |
| Customers & addresses | `users` · `user_address` · `customer_bag` · `customer_cart` · `customer_favourite` |
| Orders | `customer_order` (53 cols) · `customer_order_item` (33 cols) |
| Invoices | `sales_invoice` (48) · `sales_invoice_item` (26) · `purchase_invoice` · `purchase_invoice_item` · `einvoice_document` |
| GST / tax | `gst_tax_rules` · `gst_configuration` · `gst_hsn_master` · `gst_state_master` · `gst_output_tax` · `gst_input_tax` · `gst_movement_ledger` · `gst_tax_period` · `gst_credit_note`(+`_item`) · `gst_debit_note`(+`_item`) · `gst_itc_transaction` · `gst_return_snapshot` · `gst_invoice_series` · `gst_registration` · `gstin_verification` · `gstr2b_import` · `gstr2b_record` · `gst_payment` · `gst_interest_rule` · `gst_late_fee_rule` |
| Payments | `payment_attempt` (29) · `payment_refund` (20) · `payment_webhook_event` |
| Returns / exchanges | `order_return_request` · `order_return_item` · `order_exchange_request` · `order_exchange_item` |
| Accounting | `journal_entry` (17) · `journal_entry_line` (7) · `chart_of_accounts` · `accounting_period` · `gst_accounting_exception` |
| Audit | `gst_audit_log` |
| Authorization | `roles` · `role_permissions` |

Notes that matter for the scanner:

- **Inventory is derived, not stored.** There is no inventory-movement table; the
  current position comes from `inventory_product_size_stock.initial_stock` less
  consuming order items plus returns, in `findCurrentStockByProductId`. The scanner
  must reuse that query, not build a second calculation.
- **AR is derived**, from `journal_entry_line` on account `1100`. There is no AR
  table.
- **COD and transportation are columns on `customer_order`**
  (`cod_fee_*`, `shipping_value`), not separate tables — so "which table" for a COD
  anomaly is `customer_order`.
- **Gateway fee is columns on `payment_attempt`** (`gateway_fee_*`,
  `net_settlement_paise`).

## 18. Security finding — `veloria.pem` (§40)

Not modified, not deleted, not moved. Contents never read or output.

```
Path        /Users/amitkumarmohanta/Desktop/Veloria/veloria.pem
Type        RSA PRIVATE KEY (first line only)
Size        1674 bytes        Modified   Aug 9
File mode   -rw-r--r--  (644) — world-readable
```

| Question | Answer |
|---|---|
| Tracked? | **No** — untracked |
| Committed? | **No** — absent from all history of the enclosing repository |
| Ignored? | **No** — `git check-ignore` matches no rule, so `git add -A` would stage it |
| Inside either app repo? | **No** — outside both `veloria.backend` and `veloria.frontend` |
| Inside a repo at all? | **Yes** — the worktree of a repository rooted at `/Users/amitkumarmohanta` (the home directory, one commit) |
| Used by the application? | **No reference** in backend `src` or either frontend — it appears to be an infrastructure/SSH key, not an application credential |
| Exposed? | No evidence of Git exposure. The real issue is the **file mode**: 644 is world-readable, and SSH itself requires 600 |
| Requires rotation? | **Not on the evidence of exposure** — it was never committed. Rotation is a judgement call for the owner: warranted if this machine is shared, backed up somewhere untrusted, or the key's provenance is uncertain |

**Correction:** an earlier report gave this path as
`/Users/amitkumarmohanta/veloria.pem`. It is in `Desktop/Veloria`, not the home
directory. `p0-15-engineering-anomaly-monitoring.md` has been annotated.

Remediation is the owner's to choose: `chmod 600` at minimum; add the key and other
credential-bearing paths to the home repository's `.gitignore`; move the key out of
that worktree; or remove the home-directory repository if it was created by accident.

## 19. Tests

**No new tests were added**, because no feature code was written. The verification in
this phase was executed directly against the live database and is reproduced in §6
and §8 above.

No regression suite was re-run: no source file changed. The last verified run stands
from P0-14 — backend 544/544, Cucumber 87 run / 5 failures (the sixth was genuinely
fixed that phase), Playwright client 17 passed + 1 skipped, Playwright admin 15
passed. The admin suite was re-run after the Admin role was created and passed 15/15.

The test plan for the gated work is in
`p0-16-engineering-manual-anomaly-monitoring.md` §17 and remains current.

## 20. Historical integrity

Captured before the gate verification and re-checked after:

```
checksum   eea1e82cfa93ce16f7963524e3eaf56f      unchanged
SALE 28 · REVERSAL 21 · journals 54 · lines 187 · orders 7
AR         1,979,633 paise (₹19,796.33)
13 accounts with balances
business records modified = 0 · deleted = 0
```

Every mutation attempted during the read-only test was refused by PostgreSQL, and
each was additionally wrapped in `BEGIN … ROLLBACK` so that an unexpected success
could not have persisted. The only database objects created were a probe table
(since dropped) and the `veloria_scan_ro` role (retained as the gate artifact).

## 21. Remaining blockers

```
BLOCKER    AWS KMS unavailable — no SDK dependency, no credentials anywhere in the
           provider chain (~/.aws absent, no AWS_* env, IMDS unreachable), no CMK.
           Region us-east-2 is configured. Gates §3–§10 unreachable; §22 therefore
           prevents building the Engineering system.

RESOLVED   Read-only PostgreSQL role — created and proven (§6).
RESOLVED   Advisory lock — proven across concurrent sessions (§8).
RESOLVED   Authorization framework — verified, with the authenticated-chain
           requirement recorded (§9).

DECISION   Engineering connection pool sized at 2, and the instance ceiling that
           implies (§7) — needs confirmation against the real deployment topology.

UNDECIDED  Anomaly and scan retention. No organizational policy exists; inventing a
           period is forbidden. Nothing would be auto-deleted.

UNDECIDED  Transportation and gateway-customer-charge anomaly rules, while their
           business rules remain UNDECIDED (P0-14). §33 already excludes them.

OWNER      veloria.pem file mode and .gitignore coverage (§18).
```
