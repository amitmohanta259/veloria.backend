# P0-15 — Engineering anomaly monitoring

> **STATUS: BLOCKED — nothing was implemented.**
>
> §13 of the phase specification requires that encryption keys live in AWS KMS or
> "the application's already-approved secure secret/key-management system", and that
> if no secure key-management mechanism exists the phase **STOPs and reports the
> dependency** rather than implementing a homemade key vault. §63 repeats this as a
> stop condition: *"A secure key-management system is unavailable."*
>
> **No such mechanism exists in this application.** The evidence is in §9 below.
>
> No migration was written, no table created, no scanner built, no endpoint added.
> The financial baseline is untouched: checksum `eea1e82cfa93ce16f7963524e3eaf56f`,
> SALE 28, REVERSAL 21, AR ₹19,796.33 — verified before and after this phase's work,
> which was inspection only.
>
> This document records the inspection, the design the blocker gates, and the safe
> next step. It is a design and decision record, not an implementation report.

## 1. Engineering menu

The specification opens by saying the Engineering section is to be *extended*.
**There is no Engineering section.** Searched both repositories:

```
DB Data Fluctuation        absent
Transaction 360            absent
Compliance Data Gaps       absent
Daily Anomaly Monitor      absent
any anomaly/fluctuation code, route, view or endpoint   absent
```

The only occurrences of "engineering" anywhere are `StaffDepartment.ENGINEERING`
and its label in the roles and staff tables — a **staff department**, unrelated to
an Engineering tooling section.

So all four menu items are new work, not extensions:

```
Engineering
├── DB Data Fluctuation        new
├── Transaction 360            new
├── Compliance Data Gaps       new
└── Daily Anomaly Monitor      new
```

This matters for sizing: the phase is a greenfield subsystem — schema, scanner,
rule engine, crypto layer, four screens and an API surface — not an addition to
something established.

## 2. Daily cron

**Not blocked.** The scheduling infrastructure already exists and is reusable:

```java
MasterServiceApplication        @EnableScheduling
ClientSessionStore:111          @Scheduled(cron = "0 0 3 * * *")   // existing precedent
```

The intended configuration follows the application's existing convention of
externalised properties under the `veloria:` root in `application.yaml`:

```yaml
engineering:
  anomaly-scan:
    enabled: true
    cron: "0 0 2 * * *"
    time-zone: Asia/Kolkata
```

The timezone is a property, never a Java literal — consistent with the existing
`ZoneId.of("Asia/Kolkata")` usages being replaced by configuration in new code.

**Design note on the existing precedent.** `ClientSessionStore`'s job carries no
zone, so it runs in the JVM default. A new job must not copy that.

## 3. Read-only architecture

**Partly available, needs one infrastructure decision.**

The application has a single `DataSource` (`postgres` / `postgres`, full
privileges). A read-only path as §7 prefers requires either:

```
A. a second DataSource bound to a read-only PostgreSQL role
B. a read replica (none is configured; §7 says it must not be mandatory)
C. Spring's @Transactional(readOnly = true) alone
```

Option C is **not** a guarantee — it is a hint that sets the JDBC connection
read-only flag and disables Hibernate dirty-checking; a native `UPDATE` issued
through that connection still reaches PostgreSQL. §7's requirement is that the
scanner "should be unable to modify business tables even if an accidental SQL
statement is introduced", which only A or B deliver.

Recommended: **A**, a distinct `GRANT SELECT`-only role and a second DataSource
used exclusively by the scan service. It is local to this feature and needs no
replica. The read-only role is a database-level grant, so creating it is a
deployment action requiring approval — recorded here rather than assumed.

## 4. Scan lifecycle

Designed, not built:

```
scanId UUID · scanStartedAt · scanCompletedAt · status · duration
rulesExecuted · transactionsScanned · anomaliesFound · failureReason

RUNNING → COMPLETED
        → PARTIAL    one or more independent rules failed; the rest completed
        → FAILED     the scan itself could not proceed
```

A failing rule marks itself failed and does not abort independent rules (§39). No
status transition ever writes to a business table. A failed scan records only the
monitoring outcome.

### Idempotency and multi-instance execution — **not blocked**

§40 names "database advisory lock" as an acceptable pattern, and PostgreSQL
provides one natively, so no library and no custom mechanism is needed:

```sql
SELECT pg_try_advisory_lock(<feature-scoped key>)
```

Exactly one instance acquires it; the others skip the tick rather than queueing.
Combined with a unique constraint on the scan's logical day, a restart, a scheduler
retry, an overlapping execution or a connection retry cannot produce a second scan
or duplicate findings for the same day.

This is deliberately **not** a JVM-level lock, which §5 forbids for multi-instance
deployments.

## 5. Anomaly model

The conceptual model in §50, reconciled with this project's actual conventions —
Liquibase YAML changesets registered in `db/master.yaml`, `bigint` identity keys, a
`uuid` column beside the id, `created`/`modified` audit columns, money in integer
paise, `archive` flags on business tables:

```
engineering_anomaly_scan
  id · uuid · scan_day · started_at · completed_at · status
  rules_executed · rules_failed · transactions_scanned · anomalies_found
  failure_reason · created · modified

engineering_anomaly_finding
  id · uuid · scan_id → engineering_anomaly_scan
  fingerprint            deterministic, non-sensitive (§31)
  severity · domain · rule_id · entity_type · entity_id · transaction_id
  actual_value · expected_value · description
  encrypted_table_name · table_key_version
  encrypted_column_name · column_key_version
  detected_at · first_detected_at · last_detected_at · occurrence_count
  status                 OPEN | ACKNOWLEDGED | RESOLVED | FALSE_POSITIVE
  created · modified

engineering_anomaly_evidence
  id · uuid · anomaly_id → engineering_anomaly_finding
  evidence_type · evidence_reference · encrypted_payload · payload_key_version
  created
```

Monitoring tables, deliberately named apart from every business table and never
joined into a financial write path. The scanner may insert here and nowhere else.

**These tables are the blocker's blast radius.** `encrypted_table_name` and
`encrypted_column_name` are not optional decoration — §8 forbids storing plaintext
table and column names in the finding at all. So the finding row cannot be written
correctly until keys can be managed securely, and a scanner that cannot record a
finding has no product. That is why this phase stops here rather than delivering a
partial subsystem.

## 6. Anomaly rules

The rule set from §21–§27 was mapped against the real schema. Recorded so the
work is not re-derived later, and so the rules needing a business decision are
visible before anyone implements them.

Rules that are **computable today** from existing snapshots and constraints:

| Domain | Examples |
|---|---|
| GST | `GST_TOTAL_MISMATCH` (total ≠ CGST+SGST+IGST) · inclusive carve-out mismatch (`taxable + tax ≠ gross`) · exclusive mismatch · missing HSN · missing SAC where a service line exists · missing place of supply on a finalised order · rule expired / not yet effective for the order's date · COD snapshot inconsistent with `cod_fee_tax_resolution` |
| Payment | attempt without order · amount ≠ invoice total · duplicate capture · collection without SALE/AR · collection > invoice · collection > outstanding AR · refund > collected · status inconsistent with `PaymentStatus.isPaid` |
| Return / refund | return without order · return qty > purchased · refund without verified return · refund before verification · duplicate refund per return · refund without accounting effect · **COD refund without an approved destination** (authoritative: P0-14 records D-COD-REFUND as UNDECIDED) |
| Accounting | unbalanced journal (Σdebit ≠ Σcredit) · missing/duplicate SALE · missing/duplicate PAYMENT_COLLECTION · duplicate REVERSAL · posting into a closed period · `transaction_date` ≠ event date · orphan journal · orphan journal line · AR ≠ invoice − collections |
| Inventory | consumption without a consuming order status · release > consumed · return > delivered — all read through the **existing** derived model (`findCurrentStockByProductId`), not a new calculation |
| Invoice | missing · duplicate · total ≠ snapshot · total ≠ AR · total ≠ paid + outstanding · GST mismatch |

Rules that **need a decision before implementation** — they would otherwise
require inventing the rule they test against:

```
TRANSPORTATION_*        transportation pricing is UNDECIDED (P0-14). A scanner
                        cannot flag a transportation amount as wrong with no
                        approved rule to compare against. Only "snapshot changed
                        after the order was placed" is checkable today.
GATEWAY_FEE_CUSTOMER_*  the customer half's accounting and GST treatment are
                        UNDECIDED, so there is no expected value.
```

Per §27, any check whose legal significance is not established by an approved
business rule is classified `POTENTIAL_COMPLIANCE_RISK`, and no finding asserts a
legal violation.

## 7. Transaction 360

Designed as a read-only traversal from any finding:

```
Order → Order Items → Product → Customer → Address
      → Invoice → GST snapshot → COD snapshot
      → Payment → Payment Allocation → Gateway fee
      → Return → Refund
      → Journals (SALE · COD_FEE · PAYMENT_COLLECTION · REVERSAL · REFUND)
      → AR position → Inventory movement
```

Every finding carries `transaction_id` where a transaction exists, so the screen
opens directly on the affected order rather than making an engineer search.

## 8. Compliance gaps

Mandatory-data validation, drawn only from rules the application already enforces
(so nothing is newly declared mandatory):

```
finalised order without place of supply      — the order path already requires it
invoice missing tax classification           — SalesInvoiceService already requires it
product without HSN                          — GstCalculationService returns NO_HSN
service line without SAC                     — CodFeeTaxResolver returns NO_SAC_CONFIGURED
captured payment without gateway reference    — the capture path already requires it
refund without a refund reference             — the refund path already requires it
return without verification information       — eligibility already requires VERIFIED
journal without a posting date                — the column is NOT NULL
```

## 9. Encryption architecture — **THE BLOCKER**

### What the specification requires

```
AES-256-GCM · 256-bit key · unique random nonce per operation · auth tag
key version per value · hourly key rotation · historical keys retained
keys in AWS KMS or an already-approved secure key-management system
never in source, Git, frontend, database plaintext, constants or logs
no homemade key vault
```

### What the application actually has

Swept both repositories and the build:

| Looked for | Result |
|---|---|
| `aws-java-sdk-kms`, `software.amazon.awssdk:kms` | **absent** from `pom.xml` |
| AWS Secrets Manager client | **absent** |
| HashiCorp Vault, `spring-cloud-vault` | **absent** (Spring Cloud is present for OpenFeign and Resilience4j only) |
| Azure Key Vault, GCP KMS | **absent** |
| Java keystore / PKCS#12 / JCEKS in the repo | **absent** |
| Any `KmsClient`, `generateDataKey`, `Cipher.getInstance`, `SecretKeySpec` for encryption | **absent** — the single `SecretKeySpec` is HMAC-SHA256 used to *verify* dev JWTs under the `local` profile |
| Any key/secret/crypto service abstraction | **absent** |

The AWS SDK is present, but only `s3`, `ses` and `sns`. There is no encryption
code anywhere in the application.

**How secrets reach the application today:** environment variables read by
`@Value` with empty or dummy defaults — `AWS_ACCESS_KEY`, `AWS_SECRET_KEY`,
`KEYCLOAK_CLIENT_SECRET`, `RAZORPAY_KEY_ID/KEY_SECRET/WEBHOOK_SECRET`,
`SPRING_DATASOURCE_*`, and `VELORIA_GST_LOCAL_SECRET` (explicitly
"local-development-only", local profile only).

An environment variable is a fine way to inject *one* long-lived secret. It is not
a key-management system, and it cannot satisfy this feature:

- **Hourly rotation** produces 24 keys a day, ~8,760 a year. Nothing in the
  application can create, version, store or serve them.
- **§14 historical-key retention** requires every one of those keys to stay
  securely retrievable for as long as findings are retained, or older anomaly
  reports become permanently undecryptable — which §63 names as its own stop
  condition.
- The obvious shortcut — one master secret in an env var, hourly subkeys derived
  from it by HKDF — **is a homemade key vault**, which §13 forbids in terms. It
  also concentrates every historical key in one unrotatable secret.
- In *this* environment AWS credentials are placeholders
  (`unset-placeholder-s3-disabled`), so even adding the KMS SDK would not yield a
  working key service to build or test against.

There is no reading of the current state under which a secure key-management
mechanism exists. The phase's own instruction is therefore to stop.

### The design that is ready to build once a key service exists

```
{ "v": 1, "kv": "KV-2026092801", "alg": "AES-256-GCM",
  "nonce": <12 bytes, base64>, "ct": <ciphertext, base64>, "tag": <16 bytes, base64> }
```

Serialized into the `encrypted_*` column with `*_key_version` stored beside it as a
plain, searchable column. A fresh 12-byte random nonce per operation, never reused.
The key version is metadata, not key material, so it is safe at rest.

Only the table name and the column name are encrypted (§53). `rule_id`, `severity`,
`domain`, `transaction_id`, `entity_id`, `scan_id` and timestamps stay plaintext so
the findings remain searchable and filterable (§54) — encryption is not weakened to
support search, and search is not built on decrypted values.

## 10. Hourly key rotation

```
hour boundary → current key becomes previous → new key becomes active
new findings encrypt under the new version; existing rows are never rewritten
old keys are retained, never deleted on rotation
```

Rotation must never trigger a bulk re-encryption of history: that would rewrite
monitoring records for no benefit and would put the whole table at risk of a
partial failure every hour.

## 11. Key versioning

```
KV-YYYYMMDDHH        e.g. KV-2026092801, KV-2026092802
```

Every encrypted value carries the version that produced it. If that version cannot
be loaded, the authorized user sees exactly:

```
DECRYPTION_UNAVAILABLE
```

Never a fallback to plaintext, to Base64, to another key, or to a guess. A
tampered ciphertext or tag fails the GCM authentication check and surfaces the same
way — an integrity failure is not a decryption result.

## 12. Admin decryption

```
Admin UI → authorized anomaly-detail endpoint → authorization check
         → key fetched by version, server-side → decrypt → safe DTO
```

No generic `/decrypt?ciphertext=` endpoint (§56). Decryption happens only inside an
authorized retrieval of a specific anomaly by id. The browser receives plaintext
table and column names or the `DECRYPTION_UNAVAILABLE` marker — never a key, never
raw ciphertext with its key-management metadata, and nothing is decrypted in
JavaScript or held in `localStorage` or `sessionStorage`.

Unauthorized callers receive the finding with table and column reported as
`ENCRYPTED` and no ciphertext at all.

### Authorization — **not blocked**

The existing framework is reused, not duplicated. `GstPermission` holds permission
strings expanded from token roles into authorities and enforced with
`@PreAuthorize`, which is how every admin endpoint in the application is already
protected. The four permissions §2 asks for are added there:

```
ENGINEERING_VIEW · ENGINEERING_ANOMALY_VIEW
ENGINEERING_TRANSACTION_AUDIT · ENGINEERING_COMPLIANCE_VIEW
```

No `permitAll()` on any Engineering endpoint. Separately, the role/permission
directory used by the Admin UI (`role_permissions.module`, 19 modules today) gains
an `ENGINEERING` module so access can be granted through the existing Roles &
Permissions screen rather than a second mechanism.

### Decryption audit

Every decryption records `adminUser`, `timestamp`, `anomalyId`, `scanId`,
`operation` — and never the key, the key material, a plaintext secret or any
customer payment data.

## 13. Security

Findings from this phase's review (§47), reported as presence and location only:

| Check | Result |
|---|---|
| Encryption keys in source | None — there is no encryption code to hold any |
| Encryption keys in Git | None |
| Encryption keys in the frontend | None |
| Encryption keys in logs | None |
| Plaintext table/column names in anomaly persistence | N/A — nothing was persisted |
| Unauthorized decryption endpoint | None added |
| Customer payment secrets, card/CVV data | None, re-confirmed |

**One pre-existing finding worth raising, unrelated to this phase's code:**

```
/Users/amitkumarmohanta/Desktop/Veloria/veloria.pem     an RSA private key
```

> **Path corrected in P0-16.** An earlier revision of this line gave the path as
> `/Users/amitkumarmohanta/veloria.pem`. The file is in the `Desktop/Veloria`
> directory, not the home directory. The full determination — tracked, ignored,
> committed, referenced, file mode — is in
> `p0-16-security-and-engineering-implementation-report.md` §18.

It sits in the worktree of a git repository rooted at the **home directory**
(`/Users/amitkumarmohanta`, one commit, "first commit"). The file is currently
untracked, but `git check-ignore` matches no rule for it, so a `git add -A` in that
repository would commit an RSA private key — alongside `.ssh/`, `.claude.json` and
other credential-bearing paths in the same worktree.

Remediation is the owner's call, so nothing was changed. The options are to add the
key and other credential paths to that repository's `.gitignore`, move the key out
of the worktree, or remove the home-directory repository if it was created
accidentally. Flagged here because this phase is about keeping key material out of
version control, and the largest instance of that risk in this workspace is not in
either application repository.

## 14. Performance

§41 rules out nightly full-table scans. The design is incremental:

```
bounded window   orders, payments, returns, refunds, invoices and journals
                 created or modified since the previous successful scan
plus              all findings currently OPEN, re-evaluated for persistence
aggregate queries for fluctuation comparison, not row-by-row reads
pagination        every rule bounded; no unbounded result set
indexes           existing ones on order_code, customer_order_id, source_type/source_id
read replica      used when configured, never required
```

Full historical sweeps are a separate, explicitly-invoked operation, never part of
the daily tick.

## 15. Multi-instance execution

Covered in §4: `pg_try_advisory_lock`, a PostgreSQL-native mechanism §40 names as
acceptable, plus a uniqueness constraint on the scan day. Not blocked.

## 16. Test strategy

Designed, and written here so the blocker does not also erase the test plan:

| Area | Tests |
|---|---|
| Encryption (§43) | round-trip AES-256-GCM · a unique nonce per operation · wrong key fails · wrong key version fails · tampered ciphertext fails · tampered tag fails · rotation produces a new version on the hour · findings from an earlier version still decrypt · new findings use the newest version · the API never returns key material · an unauthorized admin cannot decrypt · an authorized engineer can |
| Cron (§44) | `runAnomalyScan()` invoked directly, never waiting on the clock · the schedule expression and zone asserted from configuration separately · the same scan cannot run twice · two simulated instances produce one scan · a failed scan records `FAILED` with a reason · business tables unchanged |
| Read-only (§45) | the whole scanner run as a `GRANT SELECT`-only role, then orders, payments, GST, returns, refunds, inventory, journals, AR and invoices all asserted unchanged; only monitoring tables gain rows |
| Historical (§46) | checksum, SALE, REVERSAL, AR and the 13 account balances captured before and compared after |
| Cleanup (§48) | teardown deletes only test-owned rows in the three monitoring tables, and cannot reach a business table or a journal |

## 17. Historical-data protection

Verified this phase, before and after (the work was inspection only, so no change
was possible — the figures are recorded as the baseline the implementation must
preserve):

```
checksum   eea1e82cfa93ce16f7963524e3eaf56f
SALE       28
REVERSAL   21
AR         1,979,633 paise (₹19,796.33)
journals   54        lines 187
```

The scanner, when built, writes to `engineering_anomaly_*` and nowhere else. It
never fixes, deletes, rewrites, recalculates or forces anything; it only reads,
compares, detects, classifies, records and reports. The scanner may create
findings with status `OPEN` only — it may never mark one `RESOLVED`, because it
repairs nothing.

## 18. Known limitations

```
BLOCKER    No secure key-management system. The whole encrypted-schema
           requirement, and therefore the finding persistence model, is gated
           on it. §9 above.

UNDECIDED  Anomaly data retention (§51). No organizational retention policy
           exists in the application or its configuration, and inventing a
           regulatory period is forbidden. Recorded as UNDECIDED. Anomaly
           history is never deleted automatically.

DECISION   A read-only database role (§3 option A) is a deployment-level grant
           and needs approval before the scanner can claim the §7 guarantee.

UNDECIDED  Transportation and gateway-customer-charge anomaly rules cannot be
           written while their underlying business rules are UNDECIDED (P0-14).

NOTE       No read replica is configured; the design treats one as optional.

NOTE       The Engineering section does not exist. This phase is a new
           subsystem, not an extension.
```
