# P0-16 — Engineering manual anomaly monitoring

> **STATUS: BLOCKED at encrypted persistence — nothing was implemented.**
>
> §19 requires AWS KMS or an already-approved enterprise key-management system, and
> forbids implementing encrypted anomaly persistence with an environment secret plus
> HKDF, a homemade vault, a static AES key, a database-held key, or a frontend key.
> §16 is explicit: *"Do not create these tables until the security/encryption
> requirements are safely satisfied."* §48 lists "secure KMS infrastructure is
> unavailable" as a stop condition.
>
> **No KMS is available.** Re-verified for this phase, not assumed: §10 below.
>
> No migration, no table, no scanner, no endpoint, no screen. **No cron** — §45 is
> satisfied by construction, since P0-15 stopped before building one.
>
> Baseline verified before and after: checksum `eea1e82cfa93ce16f7963524e3eaf56f`,
> SALE 28, REVERSAL 21, AR ₹19,796.33.
>
> This document supersedes the execution model in
> `p0-15-engineering-anomaly-monitoring.md`, which was designed around a daily cron.
> **That design is withdrawn.** Everything here is manual-trigger only.

## 1. Engineering dashboard

The section does not exist yet — confirmed again by search. All of it is new:

```
Engineering
├── Dashboard                    primary entry point, carries the run button
├── DB Data Fluctuation
├── Transaction 360 / Product Transaction Audit
└── Compliance Data Gaps
```

The Dashboard shows, all from the most recent scan record:

```
Last Scan · Status · Started · Completed · Duration
Transactions Scanned · Rules Executed · Rules Failed
Total Anomalies · Critical · High · Medium · Low
Compliance Gaps
GST · Payment · Return · Refund · Inventory · Accounting anomaly counts
```

with `RUN ANOMALY SCAN` as the primary action, plus scan history and the current
scan's live state.

## 2. Manual execution

**One execution model, one engine, no alternative paths.**

```
Engineering Dashboard
      ↓  RUN ANOMALY SCAN  (authorized user presses it)
POST /engineering/anomaly-scans/run
      ↓  authenticate → authorize ENGINEERING_ANOMALY_RUN
      ↓  create scan record, STATUS = QUEUED, capture immutable scope
      ↓  acquire PostgreSQL advisory lock
      ↓  STATUS = RUNNING
      ↓  execute read-only rules
      ↓  persist findings to Engineering monitoring tables only
      ↓  STATUS = COMPLETED | PARTIAL | FAILED
Dashboard polls and displays
```

Never triggered by a cron, a schedule, application startup, login, a page load, an
API request, or an automatic retry. There is no synchronous scan, no "quick scan",
no "full scan" and no second engine.

### Asynchronous, and why the HTTP request must not hold the scan

`POST …/run` returns as soon as the scan is queued and the lock is held:

```
HTTP 202 Accepted
{ "scanId": "SCAN-20260928-0001", "status": "QUEUED" }
```

**Two conventions this needs.** The project's `Response` wrapper is built from
`ResponseCode`, which today has `OK`, `BAD_REQUEST` and `CONFLICT` but no
`ACCEPTED` — so 202 means adding that value (the wrapper stays unchanged). And
`CONFLICT` is already the right code for a second caller: `SCAN_ALREADY_RUNNING`
answers **409**, not an error page.

### Async infrastructure — reuse, with one caveat

`@EnableAsync` and a `taskExecutor` bean already exist in
`config/AsyncConfig.java`, so no framework is introduced (§6). But that bean is a
shared pool (`ChatAsync-`, core 2 / max 10 / queue 500) serving other features, and
a multi-minute database scan parked in it would contend with them.

Because only one scan may run globally, the scan gets its **own single-thread,
queue-capacity-1 executor** — bounded by the same rule the advisory lock enforces,
and unable to starve the shared pool.

## 3. Scan lifecycle

```
QUEUED ──→ RUNNING ──→ COMPLETED
                   ├──→ PARTIAL     ≥1 independent rule failed, the rest finished
                   └──→ FAILED      infrastructure failed before meaningful execution
```

No backward transition is legal — `COMPLETED → RUNNING`, `FAILED → RUNNING` and
`PARTIAL → RUNNING` are all rejected. A re-run is always a new `scanId`.
Transitions are enforced in an enum in the shape of the existing `PaymentStatus`
and `RefundStatus`, which both already do exactly this, rather than being checked
ad hoc at call sites.

### Immutable scope

Captured when the scan record is created and used for the whole execution:

```
scanId · triggeredBy · requestedAt · startedAt · scopeFrom · scopeTo · selectedDomains
```

Changing dashboard filters after a scan starts cannot affect it — the running scan
reads its own persisted scope, never the request or live UI state.

### Rule-level failure

A failing rule records its own failure and independent rules continue; the scan ends
`PARTIAL`. Nothing is hidden: `rules_failed` and the reason are part of the scan
record and the dashboard shows them. A failure never writes to a business table.

### Identity

Following the project's convention of a human-readable number beside a uuid
(`journal_number`, `order_code`, `invoice_number`, each with a unique constraint):

```
scan_number   SCAN-YYYYMMDD-NNNN     unique, what people quote
uuid          for API addressing
id            bigint identity
```

## 4. Scan history

Every scan is retained and immutable. An earlier scan's findings are never
overwritten or reattributed; each finding references the `scanId` that produced it.

```
Scan              Started   Triggered By   Status      Transactions   Anomalies
SCAN-20260928-0003  02:12   a.mohanta      COMPLETED         2,340           7
SCAN-20260927-0002  18:03   a.mohanta      COMPLETED         2,331           5
SCAN-20260927-0001  11:44   a.mohanta      PARTIAL           2,311           3
```

History is never auto-deleted. Retention: **UNDECIDED** (§18).

## 5. DB Data Fluctuation

Compares aggregates between the previous successful scan and this one:

```
row counts · AR · journal totals by source type · payments · refunds
returns · inventory position · GST totals · invoice totals · orphan counts
```

A legitimate new order is not an anomaly. Only relationships that cannot be
explained by ordinary activity are — an AR movement with no collection or sale
behind it, a journal count that changed without a corresponding transaction, an
orphan appearing. The fluctuation view reports *deltas*; the rules decide what is
wrong.

## 6. Transaction 360

From any finding, one click to the whole lifecycle, built from real records only —
a missing event is shown as missing, never fabricated:

```
Product → Order → Order Items → Customer → Address
        → Invoice → GST snapshot → COD snapshot
        → Payment → Payment Allocation → Gateway fee
        → Return → Refund
        → Journals (SALE · COD_FEE · PAYMENT_COLLECTION · REVERSAL · REFUND)
        → AR position → Inventory movement → Transportation
```

## 7. Compliance Data Gaps

Only checks the application itself already enforces, so nothing is newly declared
mandatory:

```
finalised order without place of supply     the order path already requires it
invoice missing tax classification          SalesInvoiceService already requires it
product without HSN                         GstCalculationService → NO_HSN
service line without SAC                    CodFeeTaxResolver → NO_SAC_CONFIGURED
captured payment without gateway reference   the capture path already requires it
refund without a refund reference            the refund path already requires it
return without verification information      eligibility already requires VERIFIED
journal without posting date                 the column is NOT NULL
```

Anything whose legal significance is not established by an approved rule is
classified `POTENTIAL_COMPLIANCE_RISK`. No finding asserts a legal violation.

## 8. Anomaly rules

Mapped against the real schema. Computable today:

| Domain | Rules |
|---|---|
| GST | total ≠ CGST+SGST+IGST · inclusive carve-out (`taxable + tax ≠ gross`) · exclusive mismatch · missing HSN / SAC / place of supply · rule expired or not yet effective for the order's date · snapshot inconsistent with `cod_fee_tax_resolution` · tax basis mismatch |
| Payment | attempt without order · amount ≠ invoice total · gateway mismatch · duplicate capture · duplicate collection · collection without SALE/AR · collection > invoice · collection > outstanding AR · refund > collected · transition illegal under `PaymentStatus` |
| Return / refund | return without order · qty > purchased · qty > delivered · refund before verification · duplicate refund per return · refund without return · refund without accounting effect · **COD refund with no approved destination** (authoritative: P0-14 records D-COD-REFUND as UNDECIDED) |
| Accounting | unbalanced journal (Σdr ≠ Σcr) · missing/duplicate SALE · missing/duplicate PAYMENT_COLLECTION · duplicate/missing REVERSAL · posting into a closed period · `transaction_date` ≠ event date · orphan journal · orphan line · AR ≠ invoice − collections |
| Inventory | consumption without a consuming order status · release without consumption · release > consumed · return double count — all through the **existing** derived model (`findCurrentStockByProductId`); no second inventory calculation |
| Invoice | missing · duplicate · total ≠ snapshot · total ≠ AR · total ≠ paid + outstanding · GST mismatch |

Classification per §26: `GST_ANOMALY`, `CONFIGURATION_GAP`, `VALIDATION_FAILURE`,
`POTENTIAL_COMPLIANCE_RISK`.

**Rules that cannot be written yet**, because they would require inventing the rule
under test:

```
TRANSPORTATION_*         pricing is UNDECIDED (P0-14) — there is no expected value.
                         Only "snapshot changed after the order was placed" is checkable.
GATEWAY_FEE_CUSTOMER_*   the customer half's accounting and GST treatment are UNDECIDED.
```

### Deduplication

A deterministic fingerprint over non-sensitive identifiers only — and deliberately
**not** over the encrypted table or column, so the fingerprint leaks nothing:

```
fingerprint = hash(ruleId, entityType, entityId, transactionId, fieldRef)
```

A persistent anomaly stays one logical finding: `firstDetectedAt` keeps its original
value, `lastDetectedAt` advances, `occurrenceCount` increments. Repeated manual
scans cannot multiply it. The scanner may only create findings with status `OPEN`,
and may never move one to `RESOLVED`, because it repairs nothing.

## 9. Encryption

```
AES-256-GCM · 256-bit data key · fresh 12-byte random nonce per operation
16-byte authentication tag · key version recorded per value
```

Stored envelope, serialized into the column:

```json
{ "v": 1, "kv": "KV-2026092801", "alg": "AES-256-GCM",
  "nonce": "…", "ct": "…", "tag": "…", "edk": "…" }
```

`edk` is the **encrypted** data key. `kv` and `alg` are metadata, not key material,
so they are safe at rest.

Only the table name and the column name are encrypted. `ruleId`, `severity`,
`domain`, `transactionId`, `entityId`, `scanId` and timestamps stay plaintext, which
is what keeps findings searchable (§54 of the previous spec, §35–36 here) — search is
never built on decrypted values, and encryption is never weakened to enable search.

## 10. Key management — **THE BLOCKER**

### What §19 requires

```
AWS KMS Customer Managed Key
      ↓ GenerateDataKey
hourly application data key (the CMK itself is NOT rotated hourly)
      ↓ AES-256-GCM
encrypted table / column, stored with its encrypted data key + version
```

This is textbook envelope encryption, and it is the right design: because each
finding carries its own encrypted data key, historical decryptability needs only the
CMK — no archive of thousands of raw keys.

### What is actually available

Re-verified for this phase:

| | |
|---|---|
| `kms` in `pom.xml` | **0 occurrences** — the AWS SDK is present for `s3`, `ses`, `sns` only |
| AWS Secrets Manager / Vault / Azure Key Vault / GCP KMS | **absent** |
| `AWS_*` variables in this environment | **none** |
| `aws` CLI | **not installed** |
| A CMK id or `arn:aws:kms:…` in configuration or code | **none** |
| Any encryption code in the application | **none** — the one `SecretKeySpec` is HMAC-SHA256 verifying dev JWTs under the `local` profile |

The backend currently runs with placeholder AWS values supplied only to let it
start, so even adding the SDK would yield no working key service to build or test
against.

### Why this stops the phase rather than part of it

§17 requires every finding to carry `encryptedTableName`, `encryptedColumnName` and
`keyVersion`; §18 forbids storing plaintext table and column names in the finding.
Those columns are load-bearing, so a finding row cannot be written correctly without
a key service — and a scanner that cannot record a finding has no product.

§16 therefore forbids creating the tables yet, and the dashboard's counts would all
be structurally zero. Building the menu, the API, the run button and the polling
around an unwritable findings table would be a shell, so none of it was built.

The tempting shortcut — a master secret in an environment variable with hourly
subkeys derived by HKDF — is named and forbidden by §19, and it also concentrates
every historical key in one unrotatable secret.

## 11. Admin decryption

```
Engineering user opens an anomaly
      ↓ authorized anomaly-detail endpoint (by anomaly id)
      ↓ load the finding's encrypted data key
      ↓ KMS Decrypt → plaintext data key, in memory, server-side only
      ↓ AES-GCM decrypt table + column
      ↓ safe DTO
```

No generic `/decrypt?ciphertext=` endpoint. Decryption happens only inside an
authorized retrieval of one specific anomaly. The browser never receives a KMS key,
an AES key, a plaintext data key, or raw ciphertext with its key metadata. Nothing
is decrypted in JavaScript or kept in `localStorage` or `sessionStorage`.

An unauthorized caller gets the finding with table and column reported as
`ENCRYPTED`, and no ciphertext at all.

If the key version cannot be loaded, or GCM authentication fails on a tampered
ciphertext or tag, the authorized user sees exactly `DECRYPTION_UNAVAILABLE` — never
a fallback to plaintext, Base64, another key, or a guess.

### Audit

Each decryption records `adminUser`, `timestamp`, `anomalyId`, `scanId`,
`operation`. Each run records `scanId`, `triggeredBy`, `startedAt`, `completedAt`,
`status`, `operation = RUN_ANOMALY_SCAN`. Never a password, token, AWS secret, KMS
key, database password or payment credential. No anonymous execution.

## 12. Read-only architecture

```
Dashboard → Scan API → Anomaly Scan Service → READ-ONLY DataSource → business tables
                                            → primary DataSource → engineering_anomaly_* only
```

`@Transactional(readOnly = true)` is **not** the guarantee §14 asks for — it is a
hint that flags the JDBC connection and disables dirty checking, but a native
`UPDATE` on that connection still reaches PostgreSQL. The guarantee needs a database
role:

```sql
-- conceptual; a deployment action, not applied
CREATE ROLE veloria_scan_ro LOGIN;
GRANT CONNECT ON DATABASE postgres TO veloria_scan_ro;
GRANT USAGE ON SCHEMA public TO veloria_scan_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO veloria_scan_ro;
```

Creating that role is a privileged deployment change requiring approval, so it is
recorded here rather than assumed. The application currently has one DataSource
(`postgres`, full privileges) and no read replica; the design treats a replica as
optional, never required.

## 13. Authorization

The existing framework is reused, not duplicated. `GstPermission` holds permission
strings that the authentication converter expands from token roles into authorities,
enforced by `@PreAuthorize` on every admin endpoint already. The five permissions go
there:

```
ENGINEERING_VIEW · ENGINEERING_ANOMALY_VIEW · ENGINEERING_ANOMALY_RUN
ENGINEERING_TRANSACTION_AUDIT · ENGINEERING_COMPLIANCE_VIEW
```

`RUN ANOMALY SCAN` is visible and enabled only with `ENGINEERING_ANOMALY_RUN`; a
user with only `ENGINEERING_ANOMALY_VIEW` sees results and no button. **No
`permitAll()` on any Engineering endpoint.**

Separately, the Roles & Permissions directory the Admin UI already uses
(`role_permissions.module`, 19 modules today) gains an `ENGINEERING` module so
access is granted through the existing screen rather than a parallel mechanism.

### Filter safety

Filters are typed, validated parameters only — `dateFrom`, `dateTo`,
`transactionId`, `productId`, `domain` (enum). A client may **never** submit a raw
SQL fragment, a `WHERE` clause, a table name or a column name to the scan endpoint.
Note the asymmetry deliberately: table and column names are *output* to authorized
users after decryption, and never *input*.

## 14. Concurrency

One active scan globally, across every instance:

```sql
SELECT pg_try_advisory_lock(<feature-scoped key>)
```

PostgreSQL-native, which §5 accepts and which is not a JVM-only lock. The second
caller does not queue and does not create a scan — it receives:

```
409 CONFLICT   SCAN_ALREADY_RUNNING
"An anomaly scan is already running."
```

A restart, a retry, a double click or two instances cannot produce two scans. The
lock is released in a `finally` block, and PostgreSQL drops a session-scoped
advisory lock automatically if the instance dies, so a crashed scan cannot wedge the
feature permanently.

## 15. Performance

```
bounded windows    records created or modified since the previous successful scan
open findings       re-evaluated for persistence
aggregate queries   for fluctuation comparison, not row-by-row reads
pagination          every rule bounded; no unbounded result set
existing indexes    order_code, customer_order_id, source_type/source_id
read replica        used when configured, never required
```

No `SELECT *` sweep of every table. A full historical sweep is a separate,
explicitly-invoked operation, never the default.

## 16. Historical protection

Baseline the implementation must preserve, verified this phase before and after
(inspection only, so no change was possible):

```
checksum   eea1e82cfa93ce16f7963524e3eaf56f
SALE 28 · REVERSAL 21 · journals 54 · lines 187
AR         1,979,633 paise (₹19,796.33)
13 accounts with balances
```

The scanner writes to `engineering_anomaly_*` and nowhere else. It never modifies
orders, products, customers, addresses, GST, payments, payment attempts, payment
allocations, returns, refunds, inventory, invoices, journals, journal lines, AR or
transportation.

## 17. Test strategy

Designed, so the blocker does not also erase the plan:

| Area | Tests |
|---|---|
| Scan lifecycle | creation · QUEUED→RUNNING→COMPLETED · →PARTIAL on a rule failure · →FAILED on infrastructure failure · every backward transition rejected · immutable scope unaffected by later filter changes |
| Concurrency (§38) | two simultaneous run requests → one scan, one `SCAN_ALREADY_RUNNING` · two simulated instances → one scan · lock released after completion and after a crash |
| Authorization | each of the five permissions enforced · no endpoint reachable unauthenticated · the run button gated on `ENGINEERING_ANOMALY_RUN` · a view-only user cannot run |
| Encryption (§44) | AES-256-GCM round trip · unique nonce per operation · wrong key fails · wrong key version fails · tampered ciphertext fails · tampered tag fails · hourly version advance · older findings still decrypt · new findings use the newest version · no key material in any response |
| Read-only (§40) | as a `SELECT`-only role: SELECT succeeds; INSERT, UPDATE, DELETE, TRUNCATE all fail; the scan still completes |
| Immutability | orders, payments, GST, returns, refunds, inventory, journals, AR, invoices asserted unchanged after a full scan; only monitoring tables gain rows |
| Historical | checksum, SALE, REVERSAL, AR, 13 balances captured before and compared after |
| Cleanup (§42) | teardown deletes only test-owned rows in the three monitoring tables and cannot reach a business table or journal |
| Playwright (§43) | menu · dashboard · permission enforcement · run button · running state · completed state · history · count cards · detail · Transaction 360 navigation · decrypted table/column for an authorized user · blocked state for an unauthorized one |

## 18. Known limitations

```
BLOCKER    No KMS. Gates §16–§20 and therefore the findings table, so the whole
           subsystem is gated. §10 above.

DECISION   A read-only database role (§12) is a privileged deployment grant and
           needs approval before the §14 guarantee can be claimed.

UNDECIDED  Anomaly/scan retention (§9). No organizational policy exists and
           inventing a period is forbidden. Nothing is auto-deleted.

UNDECIDED  Transportation and gateway-customer-charge rules, while their
           business rules remain UNDECIDED (P0-14).

NOTE       ResponseCode has no ACCEPTED; 202 requires adding it. CONFLICT
           already exists and suits SCAN_ALREADY_RUNNING.

NOTE       The shared taskExecutor is not suitable for the scan; a dedicated
           single-thread executor is specified instead (§2).

NOTE       No read replica is configured. Optional by design.

NOTE       The Engineering section does not exist. This is a new subsystem.

WITHDRAWN  The cron execution model in p0-15-engineering-anomaly-monitoring.md.
           Manual trigger is now the only execution path.
```
