# P0 Phase 1 Follow-up — Frontend Authorization & Real PostgreSQL Concurrency

Two objectives: make the admin screens authenticate against the newly guarded
backend mutations, and verify `addStock` against real PostgreSQL rather than an
in-memory model.

**The real database test found a genuine bug in the code I wrote last phase.** It is
described in §2 and fixed.

---

## 1. Frontend authentication fixes

### Correction to the Phase 1 report

That report listed four affected screens and said `AddProduct`/`UpdateProduct` were
"unaffected". **That was wrong.** Both call `ensureGstToken` only before their GST
*reads* (HSN master, tax rules); their product **create/update** calls sent no
headers at all. A full scan of every call site hitting a now-guarded endpoint found
**six call sites across five files**, not four.

| Screen | Call | Before | After |
|---|---|---|---|
| `components/inventory/updateStock/StockTableRow.tsx` | `PATCH …/stock/add` (add to existing size) | `{ method: "PATCH" }` — **no headers, no token** | `ensureGstToken` → `headers: authHeaders()` |
| `components/inventory/updateStock/StockTableRow.tsx` | `PATCH …/stock/add` (add new size) | same | same |
| `views/inventory/AddProduct.tsx` | `POST /inventory-product/create/{uuid}` | **no headers** (FormData only) | `headers: authHeaders()` |
| `views/inventory/UpdateProduct.tsx` | `PUT /inventory-product/update/{uuid}` | **no headers** (FormData only) | `headers: authHeaders()` |
| `views/AddExpense.tsx` | `POST /expense/create` | `authHeaders()`, but nothing guaranteed a token existed → intermittent | `ensureGstToken` gate, then `authHeaders()` |
| `views/SalaryPaymentDetail.tsx` | `POST /salary-payment/{id}/mark-paid` | same | same |

`views/SalaryPayment.tsx` needed **no change**: it only performs a `/list` read. The
Phase 1 report named it as affected; that was over-inclusive.

Notes on the approach:

- **No second authentication mechanism.** Every change uses the existing
  `ensureGstToken` + `authHeaders` pair from `utils/auth.ts`. No new token type, no
  direct `localStorage`/`sessionStorage` access, no manual JWT handling.
- The FormData calls receive **only** `authHeaders()` — no `Content-Type` — so the
  browser keeps its multipart boundary.
- `AddExpense` reported failures through a bare `catch { alert("Failed to post
  expense…") }`, which would have swallowed "not authorised" and shown a transient-
  failure message. The catch now surfaces the actual reason, because those two cases
  need different responses from the user.
- `StockTableRow` and `SalaryPaymentDetail` report through the mechanisms already in
  those files (`showToast`, `setError`); no new error surface was invented.

Frontend typecheck passes (`tsc -b`).

---

## 2. PostgreSQL concurrency test — and the bug it found

### Infrastructure

| | |
|---|---|
| Strategy | `@SpringBootTest` against the project's **real PostgreSQL** |
| Why not Testcontainers | Not a dependency in `pom.xml`, and the **Docker daemon is not running** on this machine. Adding it would have been a second database strategy for no gain when a real PostgreSQL is already the project's database |
| Why not H2 | Explicitly forbidden, and rightly — H2 cannot demonstrate PostgreSQL row-locking |
| Database | `jdbc:postgresql://localhost:5432/postgres`, PostgreSQL 18.3 |
| Tables | `inventory_product`, `inventory_product_size_stock` |
| Isolation | Each test seeds its own product (random UUID) and deletes it in `@AfterEach` |
| Transaction | The test is deliberately **not** `@Transactional` — a test-owned transaction would hide the production boundary and stop workers seeing each other's commits. `addStock`'s own `@Transactional` is what is exercised |

### Scenario and result

| | |
|---|---|
| Initial stock | 100 |
| Concurrent operations | 20 |
| Quantity each | 10 |
| Start synchronisation | `CountDownLatch` — all 20 wait on one gate, so transactions genuinely contend |
| Expected final | 300 |
| **Actual final (size row)** | **300** |
| **Actual final (product roll-up)** | **300** |
| Successful operations | 20 / 20 |
| Failed operations | 0 |
| Lost updates | **none** |
| Final value read via | `SELECT initial_stock … ` through `JdbcTemplate`, not a mock |
| Repeat runs | 3 consecutive, all green |
| Leftover rows | 0 |

### Proof the test has teeth

A concurrency test that cannot fail is worthless, so I verified it directly: I
temporarily reverted `addStock` to read-modify-write and re-ran it.

```
lost update: 20 additions of 10 against 100 should give 300, PostgreSQL holds 120
expected: <300> but was: <120>
```

**18 of 20 additions lost.** The original implementation is restored (verified byte-
identical), and the test passes again. The test will fail if anyone reintroduces that
shape.

### The bug this found in my own Phase 1 code

Running the full suite, the second scenario — concurrent additions to **different
sizes** of one product — failed at **390 instead of 400**. The per-size rows were
each correct; the **product roll-up** had lost an increment.

Cause: Phase 1 rolled the product total up with

```sql
UPDATE inventory_product p
   SET initial_stock = (SELECT COALESCE(SUM(s.initial_stock),0)
                          FROM inventory_product_size_stock s
                         WHERE s.product_id = p.id AND s.archive = false)
 WHERE p.id = :productId
```

Under `READ COMMITTED` the sub-select reads a snapshot taken before a concurrent
transaction's addition to a *different* size commits, so the total can be written
stale. Additions to the *same* size were safe (they serialise on that row); additions
to *different* sizes were not.

**Fix:** the product total now moves by the same atomic delta as the size row —

```sql
UPDATE inventory_product SET initial_stock = COALESCE(initial_stock,0) + :quantity WHERE id = :productId
```

— which is correct by construction and needs no lock. `recalculateStockFromSizes`
was removed rather than left available to be reused.

I also had a bug in the *fixture*: the second scenario inserted a second size row
without moving the product total, so the product started at 100 while its sizes summed
to 200. With a delta roll-up that inconsistency surfaced as 300-vs-400. The fixture now
seeds consistently and asserts that before exercising anything.

---

## 3. Test results

```
Backend full suite (mvn test)
  Before this follow-up:  328 tests — 328 passed, 0 failed   (Phase 1 final)
  After  this follow-up:  331 tests — 331 passed, 0 failed   BUILD SUCCESS

  New in this follow-up:  +3  AddStockPostgresConcurrencyTest
  Modified:               InventoryProductServiceImplStockTest (2 assertions
                          retargeted from the removed recompute to the delta)

Playwright — admin project
  9 passed, 0 failed, 0 skipped
  New: guarded-mutations-auth.spec.js (2)
  Existing 7 unaffected by the authorization change

Live HTTP verification (backend rebuilt and running)
  PATCH /inventory-product/{uuid}/stock/add   no token          → 403
  PATCH /inventory-product/{uuid}/stock/add   ADMIN_GST token   → 200
  POST  /accounting/backfill                  no token          → 403
  GET   /client/order/{orderCode}             no token          → 400 (header required)
```

No pre-existing failures were found, and none were hidden, disabled, or weakened.

---

## 4. Files changed

### Frontend

| File | Change | Reason |
|---|---|---|
| `components/inventory/updateStock/StockTableRow.tsx` | `ensureGstToken` + `authHeaders()` on both `stock/add` calls | sent no credential at all |
| `views/inventory/AddProduct.tsx` | `authHeaders()` on product create | sent no credential |
| `views/inventory/UpdateProduct.tsx` | `authHeaders()` on product update | sent no credential |
| `views/AddExpense.tsx` | `ensureGstToken` gate; catch surfaces the reason | token was only hoped for; failure reason was swallowed |
| `views/SalaryPaymentDetail.tsx` | `ensureGstToken` gate before `mark-paid` | token was only hoped for |

### Backend

| File | Change | Reason |
|---|---|---|
| `service/admin/impl/InventoryProductServiceImpl.java` | roll-up changed from SQL recompute to atomic delta | fixes the lost update found by the new test |
| `repository/admin/InventoryProductRepository.java` | `recalculateStockFromSizes` removed | racy and now unused |

### Tests

| File | Change |
|---|---|
| `src/test/java/.../inventory/AddStockPostgresConcurrencyTest.java` | **new** — 3 real-PostgreSQL concurrency tests |
| `src/test/java/.../inventory/InventoryProductServiceImplStockTest.java` | 2 assertions retargeted to the delta contract |
| `automation-test/playwright/tests/admin/guarded-mutations-auth.spec.js` | **new** — 2 tests |

**No database schema changes.** No migration, table, column, index, trigger,
function or constraint was created or altered.

---

## 5. Scope confirmation

```
Payment:                       NOT IMPLEMENTED
Checkout inventory decrement:  NOT IMPLEMENTED   (placeOrder untouched)
Reservation:                   NOT IMPLEMENTED
Damaged inventory:             NOT IMPLEMENTED
Refunds:                       NOT IMPLEMENTED
Accounting redesign:           NOT IMPLEMENTED
GST redesign:                  NOT IMPLEMENTED
New triggers:                  NOT IMPLEMENTED
Schema redesign:               NOT IMPLEMENTED
```

Backend authorization was **not weakened**: no `@PreAuthorize` was removed or
loosened, no new role invented, `ADMIN_GST` unchanged, and no read endpoint gained or
lost protection. The only backend change is the roll-up statement inside `addStock`.

---

## 6. Still outstanding

1. **`ADMIN_GST` remains a semantic stretch** for inventory, expense and payroll.
   Wiring the existing `role_permissions` module grid into Spring Security as
   authorities is the real answer, and is an authorization-model design task.
2. **Read endpoints on the four admin controllers remain unauthenticated**, for the
   reason given in the Phase 1 report: no authority meaning "may view inventory"
   exists.
3. **`UNIQUE (product_id, size)`** would close the remaining gap where two concurrent
   *first* additions for a brand-new size could each insert a row. Schema change,
   deliberately not taken here.
4. In `keycloak` mode the admin token must carry `gst_roles`; `ensureGstToken` uses
   the dev-only issuer, which exists under the `local` profile only.
