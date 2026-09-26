# P0-5A — Sales order security, status state machine and inventory integrity

**Scope:** sales-order endpoint authorization, server-side status validation, correct inventory
consumption across statuses, concurrency-safe transitions, tests, documentation.

**Deliberately not in scope and not changed:** payment collection, gateways, COD collection,
refunds, accounting postings, GST, the P0-2 inventory locking design, P0-3 checkout idempotency.

---

## 1. Original defects and root causes

The three P0-4 findings were re-verified against current code before anything was edited. All three
were confirmed, and inspection found the first two to be **wider than reported**.

### D1 — `/api/master/sales-order/**` was completely unauthenticated

`SalesOrderController` carried no `@PreAuthorize`, and the path was in neither `GST_PATHS` nor the
P0-1 `ADMIN_TOKEN_PATHS`, so it fell through to `WebSecurityConfig`'s
`anyRequest().permitAll()`. Verified by probing with no token against a **nonexistent** order code,
so nothing could change:

```
PATCH /sales-order/order/VO-DOES-NOT-EXIST/status  → 400 "Order not found: VO-DOES-NOT-EXIST"
POST  /sales-order/order/VO-DOES-NOT-EXIST/cancel  → 400 "Order not found: VO-DOES-NOT-EXIST"
GET   /sales-order/stats                           → 200 + live revenue
```

The service was reached in every case. Anyone who could reach the port could mark an order
`DELIVERED`, cancel it, or read every customer's name, email and the business's revenue.

### D2 — the status "state machine" existed only in the admin React component

`SalesOrderServiceImpl.updateOrderStatus(orderCode, newStatus)` wrote whatever string it was
given — no enum, no validation, no transition check. The sequence was enforced solely by which
buttons `SalesOrderTable.tsx` chose to render. Consequences:

- any string could be stored as a status;
- any step could be skipped;
- **a cancelled order could be walked back into the live pipeline** by an ordinary status update,
  because `cancelOrder` and `updateOrderStatus` did not know about each other;
- two concurrent operators could both read the old status and both write, last-write-wins.

### D3 — the stock-consuming status set was wrong, inconsistent, and had a second defect behind it

The reported finding was that `OUT_FOR_DELIVERY` was missing. Inspection found more:

| Problem | Evidence |
|---|---|
| **Two different lists** across 9 query sites | 5 sites used a 6-status list; 4 used a 5-status list missing `DONE`. Checkout availability and the admin stock views already disagreed with each other. |
| `OUT_FOR_DELIVERY` in neither | Live order `VO-20260824-D7ECB6D9` held 1 unit physically on a van and counted as available. |
| The **whole return leg** in neither | `READY_TO_PICKUP`, `PICKED_UP`, `IN_TRANSIT_TO_SELLER`, `RECEIVED` all released stock while the goods were with the customer or in transit back. |
| A **12th status nobody knew about** | `PARTIALLY_RETURNED`, written by `ReturnProcessingService:173`, appeared in no list, no UI metadata, and not in the P0-4 inventory. |
| Two names in the list that no code writes | `DISPATCHED`, `DONE` — unreachable. |

**The root cause behind the root cause.** Requesting a return writes `reason_for_return` on *every*
line immediately (`ClientOrderController:101`). The old query used that field twice:

```sql
-  … AND coi.reason_for_return IS NULL AND co.status IN (…)      -- stopped counting as sold
+  … AND (co.status = 'RETURNED' OR coi.reason_for_return IS NOT NULL)  -- and credited it back
```

So the instant a customer *asked* to return something, the unit both stopped being sold **and** was
added back — counted twice over, while still in the customer's house. Measured on live data before
the fix:

| Product | initial_stock | computed available |
|---|---|---|
| Mini Satchel | 1 | **2** |
| Mini Leather Backpack | 140 | **141** |
| Pato Saree | 45 | **46** |

Three products reported more stock than had ever existed. Fixing only the status list would not have
touched this, because `reason_for_return` overrode the status in both directions.

---

## 2. Files changed

**Backend — new**

| File | Purpose |
|---|---|
| `core/order/OrderStatus.java` | The canonical statuses, their stock meaning, the transition table, and `CONSUMING_SQL` |

**Backend — modified**

| File | Change |
|---|---|
| `core/security/GstSecurityConfig.java` | `/api/master/sales-order/**` added to `ADMIN_TOKEN_PATHS` so a bearer token is decoded there |
| `controller/admin/SalesOrderController.java` | `@PreAuthorize` on all 7 endpoints |
| `service/admin/impl/SalesOrderServiceImpl.java` | Transition validation, row locking, idempotent repeats, `@PreAuthorize` at the service boundary, deterministic mirror ordering |
| `repository/admin/CustomerOrderRepository.java` | `lockForStatusChange` — `SELECT status … FOR UPDATE` |
| `repository/admin/InventoryProductRepository.java` | 4 query sites moved to `CONSUMING_SQL`; credit condition changed to `return_condition IS NOT NULL` |
| `repository/admin/InventoryProductSizeStockRepository.java` | Size-level stock, same treatment |
| `repository/admin/InventorySubCategoryRepository.java` | Sub-category stock, same treatment |
| `pom.xml` | `spring-security-test` (test scope) so authorization can be asserted |

`service/admin/SalesOrderService.java` (the interface) is **unchanged** — no signature moved.

**Frontend (admin) — modified**

| File | Change |
|---|---|
| `components/sales/SalesMetrics.tsx` | Sends `authHeaders()` on `/sales-order/stats` |
| `views/Sales.tsx` | Sends `authHeaders()` on `/sales-order/create` |

These two were the only call sites that sent no token — they never had to, because the endpoints
were open. Found by auditing all six `sales-order` call sites; the other four already sent one.
Without this the Sales screen would have shown zeros. Verified in the browser afterwards (§7).

**Tests — new**

`src/test/java/.../order/OrderStatusTest.java` (10) ·
`OrderLifecyclePostgresTest.java` (13, real PostgreSQL) ·
`OrderLifecycleAuthorizationTest.java` (9) ·
`automation-test/…/features/order/order_lifecycle.feature` (9 scenarios) ·
`automation-test/…/steps/OrderLifecycleSteps.java` ·
`automation-test/playwright/tests/admin/order-lifecycle.spec.js` (6)

**Tests — modified**

`automation-test/…/api/Http.java` (added `patch`) ·
`automation-test/…/steps/InventorySteps.java` (its private copy of the availability SQL was stale
and had to be brought in line — see §7).

---

## 3. Authorization matrix

Authorization is applied **twice**: on the controller, and again on the service methods, so a future
scheduled job or internal caller cannot reach a transition without it. `ADMIN_GST` is the authority
P0-1 already established for non-GST administrative mutations (inventory, expense, payroll); no new
role was invented.

| Endpoint | Authority | Anonymous | `GST_VIEWER` | `GST_ADMIN` |
|---|---|---|---|---|
| `POST /sales-order/create` | `ADMIN_GST` | 403 | 403 | allowed |
| `GET /sales-order/{uuid}` | `VIEW_GST` | 403 | allowed | allowed |
| `GET /sales-order/all` | `VIEW_GST` | 403 | allowed | allowed |
| `GET /sales-order/stats` | `VIEW_GST` | 403 | allowed | allowed |
| `GET /sales-order/report` | `VIEW_GST` | 403 | allowed | allowed |
| `PATCH /sales-order/order/{code}/status` | `ADMIN_GST` | 403 | 403 | allowed |
| `POST /sales-order/order/{code}/cancel` | `ADMIN_GST` | 403 | 403 | allowed |
| `SalesOrderService.updateOrderStatus` | `ADMIN_GST` | denied | denied | allowed |
| `SalesOrderService.cancelOrder` | `ADMIN_GST` | denied | denied | allowed |

Verified live against the running server:

```
                              anon   viewer  admin
GET   /sales-order/stats       403     200     200
PATCH /order/VO-NOPE/status    403     403     400 ← reached the service: "Order not found"
```

The admin's 400 proves authorization passed and the request reached the business logic; the
viewer's and anonymous 403s prove it did not. The existing response envelope is preserved
(`{"code":"ACCESS_DENIED","message":"Access denied", …}` via the existing
`AppExceptionHandler`), and a denial no longer doubles as an existence oracle — a refused caller
cannot learn whether an order code is real.

**Anonymous returns 403, not 401.** The chain permits all at the URL layer so that read endpoints on
these controllers keep working for token-bearing callers, and the method annotation decides. This is
the P0-1 pattern, kept for consistency rather than introducing a second behaviour.

---

## 4. Status transitions — before and after

### Before

Any string → any string. There was no table.

### After

| From | Allowed next | Notes |
|---|---|---|
| `ORDER_PLACED` | `PACKED`, `CANCELLED` | |
| `PACKED` | `IN_TRANSIT`, `CANCELLED` | last point at which cancellation is possible |
| `IN_TRANSIT` | `OUT_FOR_DELIVERY` | |
| `OUT_FOR_DELIVERY` | `DELIVERED` | sets `delivered_at` if unset |
| `DELIVERED` | `READY_TO_PICKUP` | a return is requested |
| `READY_TO_PICKUP` | `PICKED_UP` | |
| `PICKED_UP` | `IN_TRANSIT_TO_SELLER` | |
| `IN_TRANSIT_TO_SELLER` | `RECEIVED` | |
| `RECEIVED` | `RETURNED`, `PARTIALLY_RETURNED` | the returns verify flow decides which |
| `RETURNED` | — | terminal |
| `PARTIALLY_RETURNED` | — | terminal |
| `CANCELLED` | — | terminal |
| `PAYMENT_FAILED` | — | terminal (see §8) |
| `DISPATCHED`, `DONE` | — | legacy names; no transitions in **or** out |

Every transition is taken from behaviour the application already performs — the forward leg is the
sequence the admin screen offers, the return leg is the sequence the returns flow drives. Nothing
was invented. Where meaning could not be established (`DISPATCHED`, `DONE`) the status is marked
legacy and left inert rather than guessed at.

Also enforced:

- **Unknown statuses are refused** — `"Unknown order status: SHIPPED_TO_MARS"`.
- **Repeats are safe** — asking for the status an order already holds returns success and writes
  nothing. A second `cancelOrder` explicitly does *not* overwrite the original reason.
- **Cancellation cannot be bypassed.** `CANCELLED` is terminal, so the ordinary status update that
  previously walked over it is refused.
- **The frontend cannot bypass any of this** — the rules live in the service, and the Playwright
  suite asserts them against the API directly, not through the screen.

---

## 5. Inventory-consuming status definitions

One definition, in one place: `OrderStatus.consumesStock()`, mirrored for SQL by
`OrderStatus.CONSUMING_SQL` and applied to **every** stock query.

**The rule is physical:** a unit is consumed for as long as it is away from the shelf.

| Consumes stock | Does not |
|---|---|
| `ORDER_PLACED`, `PACKED`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`, `DELIVERED` | `CANCELLED` |
| `READY_TO_PICKUP`, `PICKED_UP`, `IN_TRANSIT_TO_SELLER`, `RECEIVED` | `PAYMENT_FAILED` |
| `RETURNED`, `PARTIALLY_RETURNED` (credited back on inspection) | |
| `DISPATCHED`, `DONE` (legacy, so historical rows still count as sold) | |

**Units come back when they are handled back in**, not when a return is requested:

```sql
+ SUM(quantity) WHERE coi.return_condition IS NOT NULL
```

`return_condition` is written by admin return processing at inspection time, which is the first
moment anyone has actually had the goods in hand. The old credit keyed on `reason_for_return`, which
a customer's request writes.

**The constant cannot drift.** Annotation values must be compile-time constants, so `CONSUMING_SQL`
is a literal. `OrderStatusTest.sqlConstantMatchesTheEnum` asserts it equals the set of statuses for
which `consumesStock()` is true — adding a status without classifying it fails the build. That guard
was itself proven to fail when the constant was deliberately broken (§7, Experiment A).

**Queries updated:** `InventoryProductRepository.availableStock` (checkout), its admin product report
(`in_sold` / `in_inventory`), `findInventoryStats`, its per-product current-stock query,
`InventoryProductSizeStockRepository` (size-level), `InventorySubCategoryRepository` (sub-category
rollup).

### Effect on live data

No product now reports more stock than it ever had:

| Product | initial | before | after | why |
|---|---|---|---|---|
| Mini Satchel | 1 | 2 | **1** | return no longer double-credited |
| Mini Leather Backpack | 140 | 141 | **140** | same |
| Pato Saree | 45 | 46 | **45** | same |
| Veloria Box Bag | 100 | 100 | **99** | `OUT_FOR_DELIVERY` unit now counted |
| Veloria Crossbody | 10 | 9 | 9 | unchanged |
| Patent Mini Box | 50 | 48 | 48 | unchanged |

### Deliberately left alone

`CustomerOrderItemRepository.sumGrossSalesValue` / `sumReturnedItemValue` and five demand/share
analytics queries in `InventoryProductRepository` use a different, inverted definition
(`status NOT IN ('RETURNED','CANCELLED')`). They compute **money and demand, not stock**, and
changing them would move reported revenue — a financial-reporting change beyond this phase. Two
consequences are recorded in §8 as open items, including that they currently count `PAYMENT_FAILED`
orders as sales.

---

## 6. PostgreSQL concurrency strategy

A status change now takes the order's row lock before it reads the status it is about to judge:

```sql
SELECT status FROM customer_order WHERE order_code = :orderCode AND archive = false FOR UPDATE
```

Two operators acting at once would otherwise both read the old status, both find their transition
legal against it, and both write. With the lock the second waits, then re-reads and judges its move
against what actually committed — so a cancellation followed by a pack attempt is *refused* rather
than silently overwriting.

**Lock ordering, and why this cannot deadlock against P0-2.** This path locks only
`customer_order`; checkout (P0-2) locks only `inventory_product`. The two never hold each other's
locks, so no cycle is possible between them. Within this path, the `sales_order` mirror rows are now
updated in ascending `id` order, so two concurrent transitions on different orders take those locks
in the same sequence.

**No in-memory locking is used.** No `synchronized`, no static map, no cache. The database is the
serialisation point, so correctness survives more than one application instance.

**P0-3 is untouched.** Checkout idempotency still resolves through
`ClientOrderIdempotencyService` and the `ux_customer_order_client_reference` partial unique index;
nothing in this phase touches that path, and its 12 tests still pass.

Concurrency proven, not assumed — and the tests were run three times to check for flakiness
(13/13 each time):

- **12 simultaneous conflicting transitions** (half `PACKED`, half `CANCELLED`) on one order: the
  order settles on one reachable status, and once a cancellation commits nothing moves it off.
- **10 simultaneous cancellations**: no caller errors, stock is released once, not ten times.

---

## 7. Tests and deliberate regression experiments

### Coverage added

| Suite | Tests | Covers |
|---|---|---|
| `OrderStatusTest` | 10 | transition table, terminal states, legacy inertness, unknown strings, **SQL-constant drift guard** |
| `OrderLifecyclePostgresTest` | 13 | stock across the full delivery and return journeys, cancellation releasing once, transition rules, 2 concurrency tests |
| `OrderLifecycleAuthorizationTest` | 9 | anonymous, no-context, viewer, other-permissions, admin; and that a denial leaks nothing |
| `order_lifecycle.feature` | 9 | the same rules over HTTP, including anonymous 403 and viewer 403 |
| `order-lifecycle.spec.js` | 6 | anonymous refusals, viewer-can-read-not-write, admin walk-through, and that the screen sends a token |

Required cases from the brief, all covered: anonymous mutation · authenticated-but-unauthorized ·
authorized · unknown status · valid transition · invalid transition · duplicate transition ·
cancellation then attempted reactivation · `READY_TO_PICKUP → OUT_FOR_DELIVERY → DELIVERED` without
release (covered by the two journey tests, which walk every status in both legs) · cancellation
releasing inventory once · concurrent conflicting updates · existing checkout-inventory and
order-idempotency regressions (unchanged and passing).

### Deliberate regression experiments

Each fix was reverted in turn to prove the new tests actually catch the old behaviour. All edits were
restored and verified by checksum afterwards.

**A — `OUT_FOR_DELIVERY` removed from the consuming set (the reported P0-4 defect)**

```
deliveryJourneyHoldsStock:150  goods on a van are not available to sell  expected: <9> but was: <10>
sqlConstantMatchesTheEnum:36   … the inventory numbers silently stop matching the lifecycle
```

The defect reproduced, **and** the drift guard fired — proving the guard is not decorative.

**B — the old return-credit condition restored**

```
returnRequestDoesNotReleaseStock:201  … must not be sellable, and certainly not twice
                                      expected: <0> but was: <2>
returnJourneyHoldsStockUntilReceived:176  expected: <4> but was: <5>
```

With `initial_stock = 1`, merely *requesting* a return made the system report **2** available — the
live Mini Satchel defect, reproduced in a test.

**C — transition validation removed**

```
cancelledCannotBeReactivated:296  CANCELLED → ORDER_PLACED must be refused — nothing was thrown
invalidTransitionRefused:264      ORDER_PLACED → DELIVERED — nothing was thrown
concurrentConflictingTransitions:373
        a cancellation was accepted and then bypassed: […] expected: <CANCELLED> but was: <PACKED>
```

The third is the sharpest: under concurrency a **committed cancellation was overwritten** by a later
`PACKED`. That is precisely the bypass this phase had to close, demonstrated failing.

**D — service-level `@PreAuthorize` removed**

6 of 9 authorization tests failed — anonymous, no-context, viewer and other-permission callers all
succeeded in moving the order.

### One test of mine was wrong, and the run said so

The first version of `concurrentConflictingTransitions` asserted that every accepted transition must
equal the final status. It failed: three callers were accepted for `PACKED` and six for `CANCELLED`,
final `CANCELLED`. That is **correct history** — `ORDER_PLACED → PACKED → CANCELLED` is a legal
chain — not a lost update. The assertion was replaced with the property that actually matters: once a
cancellation commits, nothing may move the order off it. The implementation was not changed.

### A stale copy in the test infrastructure

`InventorySteps.available()` in the Cucumber module held its own copy of the old availability SQL.
It failed after the fix (`expected: <9> but was: <10>`) — the helper, not the application. It was
brought in line and commented as a mirror of the production query. The Playwright suite had no such
copy.

### Results

| Suite | Baseline (verified, not assumed) | After |
|---|---|---|
| Backend | 352 / 352 | **384 / 384**, 0 failures |
| Cucumber | 77 run, 71 pass, 6 known failures | **86 run, 80 pass, the same 6** |
| Playwright client | 12 passed | **12 passed**, 1 pre-existing conditional skip |
| Playwright admin | 9 passed | **15 passed** |

The backend baseline was re-run before any edit and measured 352/352, matching the reported figure.

**The 6 Cucumber failures are the same six established in P0-4** — same assertions, same messages:
three `Authorization`-header 400-vs-401 cases on `/bag`, `/bag/add` and `/favourites`; a
place-of-supply 500 on a free-text address; a financial reconciliation check; and `/products/new-in`
returning `[]` because the newest seed product is now 35 days old against a 30-day window. None
involves `/sales-order`, order status or stock. No new failure appeared, and no test was weakened or
seed data altered to obtain a green result.

### Browser verification

The Sales screen was checked in the browser with an authorised token after the frontend change:
8 orders listed, metrics populated (Order Placed 1, Delivered 3, Revenue ₹20,796.28), the seeded
`ORDER_PLACED` order offering exactly `PACKED` and `CANCEL`, the `OUT_FOR_DELIVERY` order offering
only `DELIVERED`, and terminal orders offering no action. The demo order was then removed and the
database returned to its original 7 rows.

---

## 8. Remaining unresolved business decisions

Recorded rather than guessed at, as the brief requires.

| # | Question | Why it is open |
|---|---|---|
| 1 | **Is `ADMIN_GST` the right authority for order fulfilment?** | It is the authority P0-1 established for all non-GST admin mutations, so it was reused rather than inventing a role. But a warehouse operator who packs boxes should probably not need GST administration rights. A fulfilment role is a product decision. |
| 2 | **Reads now require `VIEW_GST`.** | Any administrator whose token carries no `gst_roles` claim will lose the Sales screen. Intended — the endpoints exposed customer names, emails and revenue anonymously — but it is an operational change that needs confirming against how administrators are actually provisioned. |
| 3 | **Can a `PAYMENT_FAILED` order be retried?** | The client offers a "retry payment" link; the admin screen offers that status no action at all. Treated as terminal here because nothing in the code defines a way out. P0-4 D34. |
| 4 | **What do `DISPATCHED` and `DONE` mean?** | Named in the old stock SQL, written by no code, held by no row. Kept as stock-consuming so historical rows would not change, and given no transitions. |
| 5 | **Are `DAMAGED` / `LOST` returns resellable?** | Today any recorded return condition credits the unit back, damaged included. Behaviour preserved exactly — changing it would alter real availability figures and is a business call. |
| 6 | **Should `RECEIVED` release stock before inspection?** | Chosen as still-consuming: the goods are in the building but nobody has confirmed their condition. |
| 7 | **Sales and demand analytics use a different definition of "sold"** | They count `PAYMENT_FAILED` orders as sales and were left untouched (§5). Fixing them changes reported revenue and demand figures. |
| 8 | **Size-level and product-level stock still disagree** | Checkout does not persist a size, so size rows cannot be decremented by a sale. Both now use the same status policy, but the underlying mismatch is unchanged and out of scope, as the brief directs. |

---

## 9. Accounting: the inconsistency, documented and untouched

**No accounting behaviour was changed.** No journal was written, reversed or altered; no GST logic,
no backfill behaviour, no ledger balance. The cancellation path posts nothing, exactly as before.

The inconsistency P0-4 raised — cancellation releases stock but leaves the receivable — was
investigated rather than "fixed" by reflex, and the conditions P0-5B must handle are these, all
verified:

1. **Checkout does not post a sale.** `postSale` has exactly one caller:
   `AccountingPostingService.backfill()`. An order placed today has **no** journal entry until a
   backfill is run. So "reverse the sale journal on cancellation" has nothing to reverse for a fresh
   order.
2. **Historical orders do have one.** All 7 existing orders carry a backfilled `SALE` journal
   (`JV-2026-08-…`). So the correct action on cancellation is conditional on whether a posting
   exists — it cannot be unconditional.
3. **Backfill would post a sale for a cancelled order.** Neither `backfill()` nor `postSale()`
   filters on status; the only guards are `orderPlacedAt != null` and `total != 0`. A cancelled
   order whose stock has been released would therefore be booked as revenue and a receivable by the
   next backfill run. This is the most important condition for P0-5B and is **not** introduced by
   this phase — it is pre-existing, and now documented.
4. **AR is never credited by a collection.** `credit(RECEIVABLE, …)` appears nowhere; the only AR
   credits in the ledger come from manual `REVERSAL` entries. Account `1010 CASH` has no rows.

P0-5B therefore has to decide: whether sale posting becomes real-time, what ledger event a
cancellation raises, whether backfill must exclude non-revenue statuses, and how the existing
₹19,796.33 of uncollected receivables is treated. Those are accounting decisions, deliberately left
to that phase.

---

## 10. Remaining risk

| Risk | Severity | Mitigation / status |
|---|---|---|
| Administrators without `gst_roles` lose the Sales screen | **Operational, likely** | Intended lock-down, but confirm provisioning before deploying. Locally, `/api/master/dev-auth/token` mints a role-bearing token. |
| `ADMIN_GST` is semantically wrong for fulfilment staff | Medium | Follows the P0-1 precedent; a proper fulfilment role is §8 item 1. |
| Availability figures changed for 4 products | Low, intended | All four moved toward correctness; three no longer exceed `initial_stock`. Listed in §5 for reconciliation. |
| `CONSUMING_SQL` is a literal | Low | A test fails the build if it drifts, and that test was proven to fail (§7 Experiment A). |
| The `sales_order` mirror still does `findAll()` and filters in memory | Low | Pre-existing; ordering was made deterministic for deadlock safety, but the full-table scan was left alone as out of scope. |
| Backfill would post a cancelled order as a sale | **High, pre-existing** | Documented in §9 for P0-5B. Not introduced here and not fixed here. |
| Analytics still count failed checkouts as sales | Medium, pre-existing | §8 item 7. |
| iCloud duplicates in `target/` stall class scanning | Environmental | Recurred during this phase (1,017 files) and was cleared with `find target -name "* [0-9]*.class" -delete`. No source file was touched. |

---

## Verification summary

- **Authorization tests pass**: 9 service-level, plus live HTTP probes of all three outcomes.
- **Status validation tests pass**: 10 unit, 13 PostgreSQL integration, 9 Cucumber, 6 Playwright.
- **Stock-consumption tests pass**: both journey tests, cancellation-releases-once, and the
  return-request test, all against the production `availableStock` query rather than a copy.
- **Full regression**: backend 384/384; Cucumber 86 run / 80 pass with the same 6 pre-existing
  failures; Playwright 12 client + 15 admin.
- Database returned to its pre-existing 7 orders; no seed data altered.
