# P0-2 — Checkout Inventory, Atomic Deduction & Concurrency

Checkout now refuses to sell stock that is not there, under concurrency, without a
schema change.

---

## A. Existing architecture — the stock model, confirmed from the code

Stock in this application is **derived, not stored as a counter**:

```
available = initial_stock − sold + returned
```

- `inventory_product.initial_stock` and `inventory_product_size_stock.initial_stock`
  are *accumulated stocked quantity*, maintained by `addStock`.
- "Sold" and "returned" are computed from `customer_order_item` joined to
  `customer_order`, filtered by order status.
- **There is no stock column to decrement.** Placing the order *is* the deduction.

Three things the re-inspection established that materially shaped the design:

1. **The existing formula counts rows, not units.** Every copy uses
   `COUNT(CASE … THEN 1 END)`, so an order for five units consumed *one* unit of
   derived stock. Every existing `customer_order_item.quantity` is `1`, so the bug
   has never shown — but it makes overselling trivial.
2. **Checkout sends no size.** The payload is
   `{productUuid, selectedDimension: null, quantity}`; all seven existing order
   items have `size = NULL`. The size-level formula joins on `coi.size = pss.size`,
   which therefore never matches, so size-level "current stock" has always been
   exactly `initial_stock`.
3. **All 26 products have size rows**, so the size rows are the *restock* grain and
   their sum is the product total.

Because the order carries no size, the only grain at which a claim can be attributed
is the **product**. That is the grain implemented.

---

## B. Changes made

Two backend files plus one frontend regression fix. No schema change, no trigger,
no new table.

| File | Change |
|---|---|
| `repository/admin/InventoryProductRepository.java` | `lockForInventoryUpdate(productId)` — `SELECT id … FOR UPDATE`; `availableStock(productId)` — quantity-aware, **not** clamped with `GREATEST(0, …)` |
| `service/client/impl/ClientOrderServiceImpl.java` | `reserveInventory(...)` — claims stock for every line, called from `placeOrder` before anything is written |
| `frontend-client/src/views/OrderConfirmation.tsx` | sends the session token on the order read — a regression from the P0-1 IDOR fix, caught by this task's test run (see §H) |

`availableStock` differs from the display queries in two deliberate ways: it sums
`coi.quantity` instead of counting rows, and it is unclamped so an oversold state
shows as a negative rather than being hidden as zero.

---

## C. Inventory invariant

For every product in a committed order:

```
initial_stock − Σ sold.quantity + Σ returned.quantity − requested ≥ 0
```

where *sold* is order items in `ORDER_PLACED, PACKED, IN_TRANSIT, DISPATCHED, DONE,
DELIVERED` with no `reason_for_return`, and *returned* is items whose order is
`RETURNED` or which carry a `reason_for_return`. Quantities for the same product
across several order lines are **summed before the check**, so two lines of three
against five available is refused.

---

## D. Transaction boundary

`placeOrder` already had one `@Transactional`; the claim was placed inside it rather
than adding a second boundary.

```
BEGIN  (existing @Transactional on ClientOrderServiceImpl.placeOrder)
   resolve session, user, place of supply
   resolve products
   ── reserveInventory: lock + availability check (new) ──
   compute GST                      (unchanged)
   insert customer_order
   insert gst_output_tax
   insert customer_order_item[]     ← this *is* the deduction
   insert sales_order[]
   record gst_movement_ledger
COMMIT
```

The locks acquired by `reserveInventory` are held until COMMIT, so no other
transaction can read the same availability in between.

**Deviation from the sequence in the brief:** the brief places "atomically deduct
inventory" before "create order". Here the deduction *is* the order-item insert, so
what happens first is the **claim** — the lock plus the availability check. The
effect is the same: nothing is written until the stock is secured, and the lock
prevents anyone else from claiming it in the gap.

---

## E. Concurrency strategy

**A lock, not a conditional UPDATE.** The brief's preferred
`UPDATE … WHERE available >= :qty` needs a counter column to decrement. This schema
has none — availability is a query over order rows — so there is no row whose update
can carry the predicate. The per-product row is used as the serialisation point
instead:

```sql
SELECT id FROM inventory_product WHERE id = :productId FOR UPDATE
```

A second checkout for the same product blocks here until the first commits, then —
under `READ COMMITTED`, where each statement takes a fresh snapshot — re-reads
availability *including* the order the first one just wrote. That is what makes
"last unit sold once" hold.

**Deadlock prevention.** Locks are taken in ascending `inventory_product.id`, so two
concurrent multi-item orders containing the same products acquire them in the same
sequence. The ordering key is documented in the method's Javadoc.

**Cost.** One lock and one availability query per distinct product, not per line —
repeated lines are aggregated first. For an N-item order that is N locks and N
queries, with no N+1 over order rows.

**Trade-off, stated plainly:** locking at the product grain serialises checkouts of
*different sizes* of the same product. That is the price of a model where stock is
derived and the order carries no size. It is per-product, not global.

---

## F. Inventory movement

**No movement table was created**, because the history already exists:
`customer_order_item` records product, quantity, the order and the time, and the
stock formula is derived from exactly those rows. A separate SALE ledger would be a
second copy of the same facts and a second thing to keep in step.

The hot-path read is unchanged: current stock is still `initial_stock` adjusted by
the order rows, never a scan of a growing ledger.

**Where returns and damage will attach later:** `customer_order_item` already carries
`reason_for_return` and `return_condition`, and the formula already adds returned
units back. A future returns phase sets those columns; damaged/lost handling adds its
own branch. Nothing here blocks that.

**Known gap:** `addStock` still has no history — restocks change `initial_stock`
with no record of who or why. That is a restock-audit gap, not a checkout-correctness
one, and is left for the inventory-movement phase.

---

## G. Failure and rollback behaviour

| Scenario | Behaviour | Verified by |
|---|---|---|
| Insufficient stock | `VeloriaException(BAD_REQUEST)`, no order, no items, stock untouched | `insufficientStockIsRefused` |
| One line of a multi-item order short | Whole order refused; **no** product is touched | `multiItemIsAtomic` |
| Two lines of the same product exceeding stock together | Refused on the combined quantity | `repeatedProductLinesAreSummed` |
| Exact stock | Succeeds, leaves 0, next request refused | `exactStockIsAllowed` |
| Quantity 0 / negative | Rejected by Bean Validation at the edge; `reserveInventory` also refuses, so a bypassed edge cannot deduct | `nonPositiveQuantity` guard + P0-1 validation tests |
| Any later step failing | The claim is inside the same transaction, so the whole thing rolls back — there is no committed order without its stock consumed, and no stock consumed without an order | transaction boundary (§D) |

Because the deduction *is* the order-item insert, "inventory deducted but order
missing" is not merely prevented — it is **not representable**. The two cannot
diverge, since one is derived from the other.

Errors use the project's existing envelope. The message names the product and what is
left (`"Only 1 left of …"`); no SQL, exception type or stack trace reaches the client.

---

## H. Test results

```
Backend full suite (mvn test)
  Before this task:  331 tests — 331 passed, 0 failed   (P0-1 follow-up final)
  After  this task:  340 tests — 340 passed, 0 failed   BUILD SUCCESS
  New:               +9  CheckoutInventoryPostgresTest

PostgreSQL integration — CheckoutInventoryPostgresTest
  9 passed, 0 failed          (real PostgreSQL 18.3, no mocks)

Cucumber @inventory
  4 passed, 0 failed

Cucumber @regression (whole suite)
  70 scenarios — 64 passed, 6 failed   (all six pre-existing, see below)

Playwright — client project
  9 passed, 1 skipped, 0 failed        (skip = real-login journey, needs credentials)

Playwright — admin project
  9 passed, 0 failed

Playwright — cross-browser (@critical on firefox + webkit)
  4 passed, 0 failed

Leftover automation data after every run: 0 products, 0 orders, 0 users
```

### Proof the tests detect overselling

A concurrency test that cannot fail proves nothing, so the claim was temporarily
disabled and the tests re-run:

```
concurrentLastUnit        exactly one buyer may win the last unit ==> expected: <1> but was: <8>
insufficientStockIsRefused  Expected VeloriaException to be thrown, but nothing was thrown
multiItemIsAtomic           Expected VeloriaException to be thrown, but nothing was thrown
```

**All eight buyers won the same single unit.** The claim was restored
(verified byte-identical) and the tests pass again.

### Live end-to-end check (running server, real HTTP)

```
stock seeded: 2
order qty=2                      → OK  "Order placed successfully"
order qty=1                      → BAD_REQUEST  "Only 0 left of Automation Live Stock…"
3 concurrent attempts for 1 unit → 400, 400, 400
final: initial=2  sold=2         → never oversold
```

### A regression this task's test run caught

The client Playwright suite failed on the order-confirmation step. Cause: the
**P0-1 order-read IDOR fix** made `GET /client/order/{orderCode}` require an
`Authorization` header, but `frontend-client/src/views/OrderConfirmation.tsx`
fetched it without one — so the confirmation page went blank after a successful
order. It was missed at the time because only the *admin* Playwright project was
re-run, not the client one. Fixed here (the view now sends the session token) and
the critical flow is green again.

### Pre-existing failures — not caused by this task

| Failure | Status |
|---|---|
| "bag cannot be read without signing in" (400 not 401) ×3 | Known defect #3 in the Phase 1 report — missing `Authorization` header fails Spring's header binding before the session check |
| "new shopper with no address … not 500" | Known defect #4 — bag GST preview throws when place of supply is unresolved |
| "Reconciliation has no failing check" | Known defect #5 — September books do not reconcile |
| "New arrivals are listed" | **Data age, not a code defect.** `/products/new-in` has a 30-day window and the newest product was created 2026-08-22, 32 days ago. The endpoint is behaving correctly; there is simply nothing new. Assertion deliberately **not** weakened |

---

## I. Concurrency results

| Test | Initial | Buyers | Requested | Successful orders | Final stock | Deadlocks | Negative stock |
|---|---|---|---|---|---|---|---|
| Last unit | 1 | 8 concurrent | 1 each | **1** | 0 | 0 | never |
| Mixed quantities | 10 | 4 concurrent | 2, 3, 4, 5 | sold ≤ 10 always | 10 − sold | 0 | never |
| Opposing multi-item | 20 + 20 | 12 concurrent | (A,B) and (B,A) alternating | 12 | 8 + 8 | **0** | never |

All workers are released by a single `CountDownLatch`, so the transactions genuinely
contend rather than running in turn. Every worker uses its own session.

The opposing multi-item test is the deadlock case from the brief: half the orders ask
for (product 1, product 2) and half for (product 2, product 1). Ascending-id ordering
normalises them, and **no deadlock surfaced**.

The whole class was run to completion twice plus a targeted three-test re-run, all
green.

---

## J. Database changes

**No schema changes.** No migration, table, column, index, trigger, function or
constraint was created or altered. The two new statements are
`@Query(nativeQuery = true)` methods over existing columns.

---

## K. Explicitly NOT implemented

```
Payment                        NOT IMPLEMENTED
Payment gateway                NOT IMPLEMENTED
Refunds                        NOT IMPLEMENTED
Reservation / reserved_quantity NOT IMPLEMENTED
Damaged inventory              NOT IMPLEMENTED
Returns processing             NOT IMPLEMENTED
Accounting integration         NOT IMPLEMENTED
GST redesign                   NOT IMPLEMENTED
New triggers                   NOT IMPLEMENTED
Partitioning                   NOT IMPLEMENTED
Materialized views             NOT IMPLEMENTED
```

Verified unchanged: `AccountingPostingService`, `JournalService`,
`GeneralLedgerService`, `GstCalculationService`. The only two `CREATE TRIGGER`
occurrences in the repository are the pre-existing period locks, untouched.

---

## L. Remaining risks

1. **Duplicate checkout submission is still possible.** There is no idempotency key
   on the order API. A network timeout followed by a user retry creates a *second*
   order — and that second order now also **consumes stock again**, because the
   deduction is the order row. The inventory claim is correct for each request; it
   cannot tell that two requests were meant to be one. `order_code` is unique but is
   generated per request, so it does not help. This needs the payment-phase
   idempotency key and is the single most important follow-up.
2. **The display queries still count rows.** `findCurrentStockByProductId` and the
   product listing use `COUNT(… THEN 1)`, so once an order for more than one unit
   exists they will show *more* stock than checkout will sell. Identical today
   because every quantity is 1. Deliberately not changed here — it is a display-layer
   fix, and touching four repositories was outside this task.
3. **Size-grain enforcement is impossible until checkout sends a size.** Stock is
   tracked per size but the order does not carry one, so a shopper can buy the last
   unit of a product whose remaining stock is all in a size they did not want.
4. **Product-grain locking serialises sizes** of the same product (§E).
5. **`addStock` has no history** (§F).
