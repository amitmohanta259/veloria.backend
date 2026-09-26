# P0 Phase 1 — Security, Validation & Stock Administration

Implemented: Bean Validation, the order-read IDOR, authorization on state-altering
administrative endpoints, and a concurrency-safe `addStock`. Nothing else.

---

## 1. Changes implemented

1. **Bean Validation enabled.** `spring-boot-starter-validation` added. The
   annotations were already on the DTOs; only the implementation was missing.
2. **Validation errors name the offending field**, inside the project's existing
   handler.
3. **Order-read IDOR closed** at the service boundary, with no observable
   difference between "not yours" and "does not exist".
4. **13 state-altering admin endpoints guarded** with `@PreAuthorize`, using only
   authorities this application already defines.
5. **A security filter chain added for the administrative paths** so a bearer token
   is decoded there — without it the annotations would have denied everyone.
6. **`addStock` made atomic and transactional.**
7. **28 tests added** across four classes.

---

## 2. Files changed

> The working tree also carries changes from earlier tasks in this conversation
> (session refresh, GST debit notes, roles/modules, Finance department, the
> credential remediation). **Those are not part of this task.** Only the files
> below were touched now.

| File | Change | Reason |
|---|---|---|
| `pom.xml` | + `spring-boot-starter-validation` | `jakarta.validation-api` was present without an implementation, so every `@Valid` was a no-op |
| `core/exception/AppExceptionHandler.java` | `handleMethodArgumentNotValid` now lists `field: message` | §6 asks the invalid field to be identified. The existing override and envelope were kept — no second mechanism |
| `controller/client/ClientOrderController.java` | `getOrderConfirmation` now takes `@RequestHeader("Authorization")` | The endpoint previously accepted **no** credential at all |
| `service/client/ClientOrderService.java` | signature `getOrderConfirmation(token, orderCode)` | Ownership must be decidable at the service boundary |
| `service/client/impl/ClientOrderServiceImpl.java` | session resolution + ownership check | The IDOR fix itself |
| `core/security/GstSecurityConfig.java` | new `ADMIN_TOKEN_PATHS` + `adminTokenFilterChain` (`@Order(2)`) | These paths were outside every authenticated chain, so no token was parsed and `@PreAuthorize` would have denied legitimate admins too |
| `controller/admin/AccountingController.java` | `@PreAuthorize` × 5 | backfill, period close/reopen, journal reverse, opening balances were callable by anyone |
| `controller/admin/InventoryProductController.java` | `@PreAuthorize` × 5 | create, update, toggle, delete, **addStock** |
| `controller/admin/ExpenseController.java` | `@PreAuthorize` × 1 | create |
| `controller/admin/SalaryPaymentController.java` | `@PreAuthorize` × 2 | create, mark-paid |
| `repository/admin/InventoryProductSizeStockRepository.java` | `addStockToSize` | atomic increment |
| `repository/admin/InventoryProductRepository.java` | `addStockToProduct`, `recalculateStockFromSizes` | atomic increment and SQL-side roll-up |
| `service/admin/impl/InventoryProductServiceImpl.java` | `addStock` rewritten, `@Transactional` | removes the read-modify-write |

New test files: `validation/PlaceOrderRequestValidationTest`,
`security/OrderOwnershipTest`, `security/AdminEndpointAuthorizationTest`,
`inventory/AddStockConcurrencyTest`, `inventory/InventoryProductServiceImplStockTest`.

---

## 3. Security changes

### Order read

| | Before | After |
|---|---|---|
| `GET /api/master/client/order/{orderCode}` | **no Authorization header parameter at all** — fully unauthenticated | requires a valid session; returns the order only to the buyer who placed it |

The refusal is `NOT_FOUND` with the same message as a genuinely missing order, so
the endpoint cannot be used as an order-code oracle. Nothing about the order is
assembled before the check — a refused read never loads its items.

### Administrative endpoints

Authorization uses **existing** authorities from `GstPermission`. No role was invented.

| Endpoint | Authority | Reason |
|---|---|---|
| `POST /accounting/backfill` | `ADMIN_GST` | writes journal entries |
| `POST /accounting/periods/{period}/close` | `LOCK_PERIOD` | an authority that exists for exactly this |
| `POST /accounting/periods/{period}/reopen` | `UNLOCK_PERIOD` | likewise |
| `POST /accounting/journals/{id}/reverse` | `ADMIN_GST` | reverses a posted journal |
| `POST /accounting/opening-balances` | `ADMIN_GST` | sets the position the books start from |
| `POST /inventory-product/create/{subCategoryUuid}` | `ADMIN_GST` | |
| `PUT /inventory-product/update/{productUuid}` | `ADMIN_GST` | |
| `PUT /inventory-product/toggle` | `ADMIN_GST` | |
| `DELETE /inventory-product/{uuid}` | `ADMIN_GST` | |
| `PATCH /inventory-product/{uuid}/stock/add` | `ADMIN_GST` | stock creation |
| `POST /expense/create` | `ADMIN_GST` | |
| `POST /salary-payment/create` | `ADMIN_GST` | |
| `POST /salary-payment/{id}/mark-paid` | `ADMIN_GST` | records money paid out |

**Read endpoints on these controllers were deliberately left open.** Two reasons,
both worth stating plainly rather than quietly doing something else:

- §13 of the task names state-altering operations; reads are not in that list.
- There is **no authority in this application that means "may view inventory"**.
  The `role_permissions` module grid (INVENTORY, EXPENSES, FINANCIALS…) exists as
  *data* and is not wired into Spring Security. Guarding a product listing with
  `ADMIN_GST` would be inventing a meaning that authority does not have.

`ADMIN_GST` is itself an imperfect fit for inventory, expense and payroll mutations —
it is the only general administrative authority that exists. Recorded as a follow-up
in §11.

---

## 4. Validation changes

| Request | Field | Rule | Was enforced? |
|---|---|---|---|
| `PlaceOrderRequest` | `deliveryLocation` | `@NotBlank` | **no** — now yes |
| `PlaceOrderRequest` | `items` | `@Valid @NotEmpty` | **no** — now yes |
| `PlaceOrderRequest.OrderItemRequest` | `quantity` | `@Min(1)` | **no** — now yes |
| `InventoryProductServiceImpl.addStock` | `qty` | `> 0`, service-level | new |

No annotation was added to a DTO and no limit was invented: the rules were already
declared and simply never ran. The only new rule is the `addStock` quantity guard,
which is the same invariant the order path already declares.

Error shape is unchanged — the project's `Response` envelope at HTTP 400. The message
now reads `items[0].quantity: quantity must be at least 1` instead of just the
message. No stack trace, no exception type, and the submitted value is never echoed.

---

## 5. Stock concurrency solution

Before — three steps with a window between them:

```java
sizeEntity.setInitialStock(sizeEntity.getInitialStock() + qty);   // read, add in Java
sizeStockRepository.save(sizeEntity);                             // write the total
long total = ...stream().mapToLong(...).sum();                    // read all sizes
product.setInitialStock(total); productRepository.save(product);  // write the total
```

Two concurrent `+10` against 100 could each read 100 and each write 110 → **110**.

After — the addition happens inside the statement:

```sql
UPDATE inventory_product_size_stock
   SET initial_stock = COALESCE(initial_stock, 0) + :quantity
 WHERE product_id = :productId AND LOWER(TRIM(size)) = LOWER(TRIM(:size)) AND archive = false
```

PostgreSQL takes a row lock for the UPDATE and holds it to commit, so concurrent
callers serialise on that row and **every addition lands**: 100 + 20×10 = **300**.

The product roll-up is also derived in SQL:

```sql
UPDATE inventory_product p
   SET initial_stock = (SELECT COALESCE(SUM(s.initial_stock), 0)
                          FROM inventory_product_size_stock s
                         WHERE s.product_id = p.id AND s.archive = false)
 WHERE p.id = :productId
```

Summing in Java and writing the total back would have reintroduced the same lost
update one level up.

**The stock model is unchanged.** `initial_stock` still means "total ever stocked";
current stock is still derived on read as `initial − sold + returned`. No
`available_quantity`, `reserved_quantity` or `damaged_quantity` was added.

**Known residual:** two concurrent *first* additions for a brand-new size could each
find no row and insert one. A `UNIQUE (product_id, size)` index would close it; that
is a schema change and is listed in §11 rather than taken unilaterally.

---

## 6. Transaction boundary

```java
@Transactional(rollbackFor = Exception.class)
public void addStock(UUID productUuid, String size, long qty)
```

Spans exactly the size-row increment (or insert) and the product roll-up, so the two
can never be left disagreeing. It does not extend to image upload or any other
unrelated work, and no other service gained a transaction.

---

## 7. Tests added

**`validation/PlaceOrderRequestValidationTest`** (9) — a validator is actually on the
classpath; valid order passes; quantity 0 rejected; quantity −1 rejected; blank
delivery location; null delivery location; empty items; violation path names
`items[0]`; message is useful and does not echo the input.

**`security/OrderOwnershipTest`** (5) — owner passes the gate; another buyer is
refused as `NOT_FOUND`; no session is `UNAUTHORIZED`; a refused read loads no order
items; a non-existent order is **indistinguishable** from someone else's.

**`security/AdminEndpointAuthorizationTest`** (5) — every POST/PUT/PATCH/DELETE on
the four controllers carries `@PreAuthorize` (by reflection, so a new endpoint fails
until someone decides who may call it); every rule names an authority that already
exists; period close/reopen use `LOCK_PERIOD`/`UNLOCK_PERIOD`; backfill, reverse and
opening-balances require `ADMIN_GST`; `addStock` is guarded. The fourth test asserts
it actually inspected all three methods, so a rename cannot make it silently pass.

**`inventory/InventoryProductServiceImplStockTest`** (6) — sizeless addition is one
atomic increment; sized addition increments atomically and rolls up **in SQL**, never
via `save()`; an unknown size creates the row then rolls up; quantity 0 and −1 are
refused **before anything is written**; an unknown product writes nothing;
`addStock` declares `@Transactional`.

**`inventory/AddStockConcurrencyTest`** (3) — read-modify-write vs atomic increment
under 20 concurrent threads; the atomic case must reach exactly 300; additions to
different sizes are independent and the roll-up equals their sum.

On the concurrency test, honestly: it exercises the *shape* of the operation against a
shared cell, not PostgreSQL row locking — it needs no database. The companion
`InventoryProductServiceImplStockTest` is what pins the production code to that shape
(it fails if `save()` reappears), so the pair is meaningful rather than a claim about
untested code. A true database-level concurrency test belongs with the checkout
decrement work, where real row contention is the thing being designed.

---

## 8. Test results

```
New tests:  28 / 28 passing
  PlaceOrderRequestValidationTest       9 passed
  OrderOwnershipTest                    5 passed
  AdminEndpointAuthorizationTest        5 passed
  InventoryProductServiceImplStockTest  6 passed
  AddStockConcurrencyTest               3 passed
```

Full-suite before/after is recorded in §9.

---

## 9. Existing failures

Before this task the suite stood at **300 tests, 300 passing** (recorded earlier in
this conversation, after the `MasterServiceApplicationTests` placeholder-credential
fix). The after-figure is reported in the final summary accompanying this document.

Two failures found **during** this task were caused by my own new tests and fixed
before the numbers above: a Mockito `useConstructor()` failure on the wide
`ClientOrderServiceImpl` constructor, and a wrong method-name guess
(`closePeriod`/`reverseJournal` are actually `close`/`reverse`). No pre-existing test
was modified, disabled, or weakened.

---

## 10. Database changes

**No database schema changes.** No migration, table, column, index, trigger,
function, view or constraint was created or altered by this task.

The two atomic statements are `@Query(nativeQuery = true)` methods on existing
repositories against existing columns.

(`ClientSessionRefresh.yaml` in the working tree is from the earlier session-refresh
task, not this one.)

---

## 11. Scope confirmation

Not implemented, as required:

```
Payment domain                  ✗ not implemented
Payment gateway                 ✗ not implemented
Refunds                         ✗ not implemented
Inventory checkout decrement    ✗ not implemented — placeOrder untouched (0 stock lines added)
Inventory reservation           ✗ not implemented
reserved_quantity               ✗ not added
damaged_quantity                ✗ not added
Inventory movement ledger       ✗ not implemented
GST redesign                    ✗ not implemented
Accounting redesign             ✗ not implemented
New triggers                    ✗ none
New PostgreSQL functions        ✗ none
Partitioning                    ✗ none
Materialized views              ✗ none
Broad index optimization        ✗ none
Broad foreign-key migration     ✗ none
Frontend changes                ✗ none
```

Verified: `AccountingPostingService`, `JournalService` and `GeneralLedgerService` are
byte-for-byte unchanged.

### Follow-ups this task deliberately did not take

1. **Four admin screens will now receive 403** on their mutating calls until they
   hold a GST token. Verified by inspection:

   | Screen | Guarded call | `ensureGstToken` | `authHeaders` | Status |
   |---|---|---|---|---|
   | `views/inventory/AddProduct.tsx` | product create | ✅ 2 | 3 | unaffected |
   | `views/inventory/UpdateProduct.tsx` | product update | ✅ 2 | 3 | unaffected |
   | `components/inventory/updateStock/StockTableRow.tsx` | `stock/add` | ❌ 0 | **0** | **will 403** |
   | `views/AddExpense.tsx` | `expense/create` | ❌ 0 | 3 | **will 403** unless a GST token happens to be cached |
   | `views/SalaryPayment.tsx` | `salary-payment/create` | ❌ 0 | 2 | **will 403** likewise |
   | `views/SalaryPaymentDetail.tsx` | `mark-paid` | ❌ 0 | 3 | **will 403** likewise |

   `StockTableRow` sends no Authorization header at all, so it fails
   unconditionally. The other three send whatever `authHeaders()` returns, which is
   a GST token only if one was cached by visiting a GST screen first — so they fail
   intermittently, which is worse to diagnose.

   The fix is one `ensureGstToken` call per screen, using the helper that already
   exists. That is a frontend change and outside this task's scope. **Failing closed
   is the correct outcome of adding authorization, but this is a visible consequence
   and should be scheduled immediately.**
2. **`ADMIN_GST` is a semantic stretch** for inventory, expense and payroll. The
   right answer is to wire the existing `role_permissions` module grid into Spring
   Security as authorities. That is an authorization-model design task.
3. **Read endpoints on those four controllers remain unauthenticated** (§3).
4. **`UNIQUE (product_id, size)`** would close the concurrent-first-insert gap (§5).
5. In `keycloak` mode the admin token must carry `gst_roles` for these endpoints to
   be reachable; in `local` mode the dev issuer already provides it.
