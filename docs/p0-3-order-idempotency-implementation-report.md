# P0-3 — Order idempotency: implementation report

**Scope delivered:** checkout/order idempotency only. Payment idempotency, reservation,
`reserved_quantity`, GST, accounting and authorization were not touched.

**Invariant:** one idempotent checkout = one committed order = one inventory deduction.

---

## 1. What the problem actually was

Placing an order is what deducts stock in this application — availability is derived
(`initial_stock − sold + returned`, summed over `customer_order_item.quantity`), not stored in a
counter. So a duplicate order is not an untidy record: it is a second deduction, and the store
sells units it does not have.

Before this change, nothing identified a checkout *attempt*:

| Candidate | Why it could not serve |
|---|---|
| `customer_order.uuid` | Server-generated per request; a retry produces a new one |
| `customer_order.order_code` | Same — `VO-yyyyMMdd-` + 8 fresh hex characters per call |
| Cart / checkout-session id | Never sent; no such concept crosses the API |

A retried `POST /client/order/place` was therefore indistinguishable from a new purchase, and
produced a second order and a second deduction.

## 2. Design

### The reference

The client generates one opaque value per checkout attempt and resends it unchanged on a retry.
Two nullable columns were added to `customer_order`:

- `client_order_reference VARCHAR(64)` — the attempt identifier.
- `request_fingerprint VARCHAR(64)` — SHA-256 of a canonical rendering of the request.

### The database is the authority

```sql
CREATE UNIQUE INDEX ux_customer_order_client_reference
  ON customer_order (client_order_reference)
  WHERE client_order_reference IS NOT NULL
```

Protection rests on this index, not on the application's lookup. No `synchronized`, no static
map, no cache, no Redis: those guard one JVM, and correctness here must survive several behind a
load balancer.

### Decisions and why

| Decision | Reasoning |
|---|---|
| **Columns nullable, index partial** | `customer_order` already held 7 rows with no reference. A blind `NOT NULL UNIQUE` would have failed or required backfilling invented values. |
| **Field optional in the API** | Existing clients and the automation suite send no reference. Without one, behaviour is byte-for-byte what it was — unprotected, but unchanged. Transitional. |
| **Uniqueness scope: global** | A reference names one checkout attempt in the whole system. Per-customer scope would let a second customer quietly create their own order under the same reference, making the identifier mean two different things. Ownership is checked before replaying, so a reference belonging to someone else is refused without disclosing anything. |
| **Fingerprint alongside the reference** | A reused reference carrying a *different* cart must not silently return the first order — the customer asked for something else. Nor may the committed order be rewritten. It is refused. |
| **Lines sorted into the fingerprint** | The same bag listed in a different order is the same request; line order is presentation, not intent. |
| **A separate bean (`ClientOrderIdempotencyService`)** | Recovery must happen *outside* the order transaction: a failed attempt has to be fully rolled back before the winning order can be read. `@Transactional` self-invocation would not cross the proxy. |
| **Failure recovery keys on the reference, not on the exception type** | See §3 — this is the part that is easy to get wrong. |

### §3 The correction that mattered

The obvious implementation catches `DataIntegrityViolationException` and re-reads. **That is
insufficient here**, and the tests proved it.

Threads serialise on the inventory row lock (`SELECT … FOR UPDATE`, from P0-2) *before* they
reach the insert. So the loser of a race usually never reaches the unique index — it acquires the
product lock after the winner committed, sees the stock already consumed, and is refused with
`"Only 0 left of …"`. A shopper retrying a checkout that had actually succeeded would be told the
item was out of stock.

The implementation therefore treats **any** failure as a possible race: the attempt's transaction
has rolled back, so if the reference has since materialised, a simultaneous request for this same
checkout won and this caller receives that order. If the reference is still absent, the original
failure is rethrown unchanged — a genuine out-of-stock is still an out-of-stock.

Both failure shapes were observed directly (§6).

## 3. Files changed

**Backend — schema**
- `src/main/resources/db/changesets/OrderIdempotency.yaml` *(new)* — two nullable columns, partial unique index.
- `src/main/resources/db/master.yaml` — changeset registered.

**Backend — code**
- `core/entity/CustomerOrderEntity.java` — the two fields.
- `repository/admin/CustomerOrderRepository.java` — `findByClientOrderReference`.
- `core/request/client/PlaceOrderRequest.java` — `clientOrderReference`, `@Size(max = 64)`.
- `service/client/ClientOrderIdempotencyService.java` *(new)* — replay, conflict, race recovery, fingerprint.
- `service/client/impl/ClientOrderServiceImpl.java` — records reference and fingerprint on the order.
- `controller/client/ClientOrderController.java` — `/order/place` routed through the idempotency service.

`recordFailedOrder` deliberately does **not** store a reference: a PAYMENT_FAILED record must not
consume it, or a later successful retry would replay the failure.

**Frontend (client)**
- `src/services/checkoutReference.ts` *(new)* — mint once per attempt, reuse on retry, clear on success. Held in `sessionStorage` so it survives the reload a shopper reaches for when a payment appears to hang. Keyed on the fields the server treats as the request, so a changed bag mints a new reference rather than colliding.
- `src/views/Payment.tsx` — sends the reference; clears it once an order exists.

**Tests**
- `src/test/java/com/app/master/service/order/OrderIdempotencyPostgresTest.java` *(new, 12 tests, real PostgreSQL)*
- `automation-test/src/test/resources/features/order/checkout_idempotency.feature` *(new, 7 scenarios)*
- `automation-test/src/test/java/com/veloria/automation/steps/IdempotencySteps.java` *(new)*
- `automation-test/playwright/tests/client/checkout/idempotency.spec.js` *(new, 3 tests)*

## 4. Migration applied and verified

```
client_order_reference  character varying(64)  nullable=YES
request_fingerprint     character varying(64)  nullable=YES

CREATE UNIQUE INDEX ux_customer_order_client_reference ON public.customer_order
  USING btree (client_order_reference) WHERE (client_order_reference IS NOT NULL)

customer_order: total 7, with_ref 0      ← the pre-existing rows, untouched
```

The changeset was also replayed from scratch (changelog row deleted, Liquibase re-ran it) to
confirm it applies cleanly rather than only having worked once.

## 5. Test results

| Suite | Result |
|---|---|
| Backend (surefire) | **352 tests, 0 failures** — baseline 340 + 12 new |
| `OrderIdempotencyPostgresTest` | **12 / 12**, real PostgreSQL |
| Cucumber, every scenario (0 skipped) | **77 run, 71 pass, 6 pre-existing failures** |
| Playwright client | **12 / 12** (3 new) |
| Playwright admin | **9 / 9** |

### The 6 Cucumber failures are not regressions

Verified empirically rather than asserted. `/order/place` was temporarily routed back to
`clientOrderService.placeOrder`, bypassing idempotency entirely; the backend was rebuilt and
restarted; the suite was re-run. **The same 6 failures appeared, with identical messages.**

They are:
- 3 × missing `Authorization` header returns 400 rather than 401 (`/bag`, `/bag/add`, `/favourites`)
- 1 × `"Place of supply is unknown"` 500 on a free-text delivery location
- 1 × financial reconciliation check (`Revenue: ledger vs orders`)
- 1 × `/products/new-in` returns `[]` — the newest seed product is **34 days old** and that endpoint has a 30-day window; a fixture that aged out, not a code fault

None involve `client_order_reference`. They are listed here rather than fixed because they are
outside this task's scope.

## 6. Proof the tests have teeth

Tests that cannot fail prove nothing, so the implementation was deliberately broken twice.

**Experiment 1 — remove the post-failure re-read** (keep only the naive find-then-create lookup):

```
Tests run: 12, Failures: 2
  concurrentDuplicatesCommitOnce    → DataIntegrityViolationException:
        duplicate key value violates unique constraint "ux_customer_order_client_reference"
  concurrentDuplicatesOnTheLastUnit → 7 × VeloriaException: Only 0 left of …
```

Both predicted failure shapes appeared — the unique index in one case, the stock check in the
other. This is the evidence behind §3. Note that even with broken application code the database
still held the line: one order, one deduction.

**Experiment 2 — correct code, unique index dropped:**

```
concurrentDuplicatesCommitOnce:
  exactly one order may exist for one reference; got 12 ==> expected: <1> but was: <12>
theIndexIsTheRealProtection:
  Expected java.lang.Exception to be thrown, but nothing was thrown.
```

20 concurrent duplicates committed **12 orders** under one reference — despite the
application-level lookup being present and correct. The index is load-bearing; the application
check is only a fast path. The index was then restored by replaying the Liquibase changeset, and
all 12 tests returned to green.

## 7. Coverage against the required cases

| Case | Test | Result |
|---|---|---|
| A — sequential retry | `sequentialRetryIsIdempotent`, `repeatedRetriesStayIdempotent` | Same order code and uuid; 1 row; stock deducted once |
| B — 20 concurrent duplicates | `concurrentDuplicatesCommitOnce` | 1 order, 1 unit sold, all 20 callers given the same code, no caller errored |
| B2 — race on the last unit | `concurrentDuplicatesOnTheLastUnit` | 1 order; duplicates are **not** told "out of stock" |
| C — different references | `differentReferencesCreateDifferentOrders`, `requestWithoutReferenceStillWorks` | Distinct orders; many NULL references coexist |
| D — same reference, different payload | `sameReferenceDifferentPayloadIsRefused`, `sameReferenceChangedQuantityIsRefused`, `itemOrderDoesNotChangeTheFingerprint` | Refused; first order untouched; line order is not a difference |
| E — another customer's reference | `otherCustomersReferenceIsRefused` | Refused; message discloses no order code, uuid or owner |
| Database is the authority | `theIndexIsTheRealProtection`, `theIndexIgnoresNullReferences` | Direct SQL insert rejected by the named index; NULLs never collide |

## 8. Constraints honoured

Payment untouched · no payment idempotency · inventory not redesigned · no reservation · no
`reserved_quantity` · GST untouched · accounting untouched · authorization not weakened (the
ownership check is additional) · no in-memory locks, static maps or caches as the source of truth
· no H2 used for PostgreSQL concurrency claims · no business rules invented · no `NOT NULL UNIQUE`
added blindly to a populated table · no Redis or external cache introduced · database remains the
final correctness authority.

## 9. Known limitations

1. **The reference is optional.** A client that sends none is exactly as exposed as before. This
   is deliberate for backward compatibility and should be made mandatory once every client sends
   one; that will be a breaking API change and is not part of this task.
2. **`record-failed` orders carry no reference.** Correct for the invariant, but it means a failed
   payment record is not itself deduplicated.
3. **The fingerprint covers what the server sees** — delivery location, currency and lines. It does
   not include `paymentMethod`, which the server does not currently read from the request.

## 10. Environmental note (not a code issue)

The backend would not start: it hung for 13+ minutes at 0% CPU, wedged in a single native file
read inside Spring's component scan. The cause was **7,272 iCloud sync-conflict duplicates** in
`target/classes` (`MasterServiceApplication 9.class`, `…Builder 15.class`, …) — the project lives
under an iCloud-synced Desktop, and dataless placeholder files block on read. Spring's scan reads
every `*.class` it finds, including those.

Deleting the duplicated build output fixed it: startup went from wedged to **15.9 seconds**, and
`CheckoutInventoryPostgresTest` from a previously observed 1271s to **0.786s**. Only generated
files under `target/` were removed; no source was touched. This will recur as iCloud re-duplicates
build output — `find target -name "* [0-9]*.class" -delete` clears it.
