# P0 Transaction Architecture — Design Review

**Design only. No schema, Java, migration, trigger, function, index or frontend code has been changed.**

Traced from the checkout screen to the database. Everything below is what the code
actually does today; where a thing does not exist, it says so rather than assuming.

---

## 1. Current architecture

```
frontend-client/views/Payment.tsx
   POST /api/master/client/order/place        (Bearer = client_session.token)
        │
ClientOrderController.placeOrder              ← no @PreAuthorize
        │
ClientOrderServiceImpl.placeOrder             ← @Transactional (one boundary)
        │
        ├─ ClientSessionStore.get(token)              → client_session
        ├─ UserRepository.findByEmailAndArchiveFalse  → users
        ├─ PlaceOfSupplyResolver.resolve              → user_address / state master
        ├─ GstIdentityService.sellerStateCode()       → business_details, gst_registration
        ├─ InventoryProductRepository.findByUuid      → inventory_product   (READ ONLY)
        ├─ GstCalculationService.calculate            → gst_tax_rules
        ├─ CustomerOrderRepository.save               → customer_order
        ├─ GstOutputTaxRepository.save                → gst_output_tax
        ├─ CustomerOrderItemRepository.saveAll        → customer_order_item
        ├─ SalesOrderRepository.saveAll               → sales_order
        └─ GstMovementService.recordSaleMovement      → gst_movement_ledger
                                                         (trigger: period lock)
   COMMIT
        │
   frontend then separately: DELETE /client/bag  → customer_bag
```

### Where records are created today

| Record | Created by | When |
|---|---|---|
| `customer_order`, `customer_order_item`, `sales_order` | `ClientOrderServiceImpl.placeOrder` | checkout |
| `gst_output_tax`, `gst_movement_ledger` | same transaction | checkout |
| `sales_invoice` | `SalesInvoiceService.issue` via `POST /sales-invoices/{id}/issue` | **manual admin action** |
| `journal_entry`, `journal_entry_line` | `AccountingPostingService.backfill` via `POST /accounting/backfill` | **manual admin action** |
| **inventory change** | — | **never** |
| **payment record** | — | **never** |

Two findings follow directly from this table and were verified, not inferred:

- **`AccountingPostingService.postSale` has no callers.** The ledger is populated only
  by an operator running the backfill endpoint. Checkout does not post to the ledger.
- **No code path anywhere decrements stock.** `grep` for any subtraction against
  `initialStock` across the whole backend returns nothing.

---

## 2. Current order lifecycle

Statuses actually written by code: `ORDER_PLACED` (checkout), `PAYMENT_FAILED`
(`recordFailedOrder`), `READY_TO_PICKUP` (return requested, set in the **controller**),
`CANCELLED` (`SalesOrderServiceImpl`). Statuses present in data additionally include
`OUT_FOR_DELIVERY`, `DELIVERED`, `RETURNED`.

There is **no state machine** — `setStatus(String)` is called directly from three
different layers including a controller. No transition is validated.

---

## 3. Current inventory lifecycle

```
inventory_product.initial_stock          (aggregate, maintained by summing sizes)
inventory_product_size_stock.initial_stock  (per size — the real grain)
inventory_product_variants               (table exists, 0 rows, unused)
```

- **Increase:** `InventoryProductServiceImpl.addStock(uuid, size, qty)` —
  read → add → `save`, **no `@Transactional`, no lock** (lost-update race).
- **Decrease:** never.
- **Display:** `ClientBagServiceImpl.mapStockStatus` and `ClientFavouriteServiceImpl`
  classify OUT_OF_STOCK / LOW_IN_STOCK / IN_STOCK from the stock number. It is a
  *label*, never a constraint — checkout does not consult it.
- No warehouse, no seller inventory, no reservation, no transfer.

`ReturnProcessingService` injects `InventoryProductRepository` and its Javadoc states
*"returnCondition (PRODUCT_OK / DAMAGED / LOST) governs inventory only"* — but the
class performs **no inventory write**. The intent is documented and unimplemented.

---

## 4. Current payment lifecycle

The checkout screen builds this payload:

```js
orderPayload() = { deliveryLocation, currency, items[], paymentMethod: "CARD"|"UPI"|"COD"|"PARTIAL" }
```

`PlaceOrderRequest` has three fields — `deliveryLocation`, `currency`, `items`. There
is no `paymentMethod`, so **Jackson silently discards it**. That is the exact
mechanism by which the payment selection is lost.

Also computed client-side only and never transmitted:

- COD fee: `grandTotalRupees + 50`
- Partial: `grandTotalRupees / 2`

So the amount the customer is shown is **not** the amount recorded anywhere. No
payment table, no gateway integration, no provider reference, no idempotency key.
`POST /order/record-failed` creates a **second order row** with status
`PAYMENT_FAILED` — a failed checkout leaves an order, not a payment attempt.

---

## 5. Current accounting lifecycle

`AccountingPostingService.postSale` (called only by backfill) posts:

```
Dr 1100 Accounts Receivable   gross
   Cr 4010 Sales                      taxable
   Cr 2100 Output CGST                cgst
   Cr 2110 Output SGST                sgst      (or 2120 IGST inter-state)
```

**This posting is correct.** Revenue and the GST liability arise on supply. What is
missing is the *second* event — collection — which would be `Dr Cash/Bank, Cr 1100`.
Measured: account 1100 has 49 lines, Dr ₹79,185.23 / Cr ₹59,388.90; account 1020 Bank
has exactly one line, the GST remittance.

So the correct fix is **additive**, not a change to existing treatment.

---

## 6. Current GST lifecycle

Working and to be preserved unchanged:

- Rate resolution from `gst_tax_rules` by HSN + price band at transaction time.
- Snapshot onto `customer_order_item`: `hsn_code`, `cgst_rate_bp`, `sgst_rate_bp`,
  `igst_rate_bp`, amounts, `unit_price_paise`, `taxable_value_paise`. Same on
  `sales_invoice_item`.
- Intra-state CGST+SGST vs inter-state IGST from resolved place of supply.
- Unresolvable place of supply → zero tax + `PENDING_REVIEW` + exception row, never a
  guess.
- `gst_movement_ledger` with supersede-never-delete; credit/debit notes;
  period-lock trigger.

GST liability follows **supply**, not collection. Therefore payment, in any method,
has **no GST effect**. This is why no GST change is proposed below.

---

## 7. Inventory architecture proposal

### 7.1 The minimum correct model

The order grain is `(product, size)` — `customer_order_item.size` is populated and
`inventory_product_size_stock` is keyed that way. `inventory_product_variants` is dead
(0 rows) and colour is not ordered against. **The per-size row is the stock grain.**

Proposed, on `inventory_product_size_stock`:

| Column | Required? | Justification |
|---|---|---|
| `available_quantity` | **Yes, now** | Sellable units. Replaces the sell-side meaning of `initial_stock` |
| `damaged_quantity` | **Yes, now** | `return_condition` already yields `DAMAGED`; stock must go somewhere other than "vanished" |
| `reserved_quantity` | **Not yet — see 7.2** | |

`inventory_product.initial_stock` stays as the denormalised roll-up it already is.

### 7.2 Why `reserved_quantity` is deliberately deferred

Reservation protects a **window between committing to sell and confirming payment**.
That window does not exist today: `placeOrder` commits synchronously and there is no
gateway. Adding a reserved column now would create a state nothing transitions out of.

`reserved_quantity` becomes **mandatory the moment a real prepaid gateway lands**, so
it is designed here and implemented *with* the payment work, not before. See §9.

### 7.3 State transition table

Only states this application actually needs:

| Event | Available | Reserved | Damaged | Movement type | Exists today? |
|---|---|---|---|---|---|
| Restock (`addStock`) | + | | | `RESTOCK` | endpoint exists, unsafe |
| Purchase received | + | | | `PURCHASE` | `purchase_invoice` exists, no stock effect |
| Order placed — **COD / no gateway** | − | | | `SALE` | **missing** |
| Order placed — **prepaid, gateway** | − | + | | `RESERVATION` | future (7.2) |
| Payment captured | | − | | `SALE` | future |
| Payment failed / timeout | + | − | | `RELEASE` | future |
| Order cancelled before dispatch | + | | | `CANCELLATION` | partial (`SalesOrderServiceImpl`) |
| Return inspected `PRODUCT_OK` | + | | | `RETURN` | condition captured, no stock effect |
| Return inspected `DAMAGED` | | | + | `DAMAGE` | condition captured, no stock effect |
| Return inspected `LOST` | | | | *(none — write-off)* | condition captured |
| Manual adjustment | ± | | ± | `ADJUSTMENT` | **missing** |
| Replacement dispatched | − | | | `REPLACEMENT` | `order_exchange_*` exists, no stock effect |

No `TRANSFER` — there is one location and no warehouse model. Do not build it.

---

## 8. Inventory concurrency strategy

### Evaluation against *this* schema

| | **A — atomic conditional UPDATE** | **B — `SELECT … FOR UPDATE`** | **C — optimistic `version`** |
|---|---|---|---|
| Correctness | Correct. The predicate and the write are one statement | Correct | Correct |
| Concurrency | Row lock held from UPDATE to COMMIT | Lock held from SELECT to COMMIT — **strictly longer** | No lock |
| Deadlock | Possible on multi-item orders **unless updates are ordered** | Same, plus a longer window | None, but livelock-ish retry storms |
| Throughput on a hot SKU | Best of the three | Worse — extra round trip, longer hold | **Worst** — every loser retries the whole order |
| Complexity | One statement; check `affectedRows` | Two statements | Version column + app retry loop + retry cap |
| Multi-product orders | Fine if sorted | Fine if sorted | Retry amplifies with item count |
| Retry behaviour | None needed — 0 rows means fail fast | None needed | Mandatory |
| Failure handling | 0 rows → throw → whole transaction rolls back | Same | Conflict → retry or fail |

### Recommendation — **Approach A**

```sql
UPDATE inventory_product_size_stock
   SET available_quantity = available_quantity - :qty
 WHERE product_id = :productId
   AND size       = :size
   AND available_quantity >= :qty;
```

`affectedRows = 0` → throw → the existing `@Transactional` on `placeOrder` rolls back
the order, its items, the GST rows and the movement rows together.

**Deadlock prevention (§49 of the audit, unaddressed today):** a multi-item order must
issue these UPDATEs in a **deterministic order — ascending `(product_id, size)`** —
so two concurrent orders containing the same two SKUs take row locks in the same
sequence. This must be written down and tested, not left to list ordering.

**Second line of defence** (constraint, not trigger):

```sql
CHECK (available_quantity >= 0 AND reserved_quantity >= 0 AND damaged_quantity >= 0)
```

Approach C is explicitly rejected: a popular SKU is precisely where optimistic locking
degrades worst, and a retry loop around a transaction that also writes GST and
movement rows is a large correctness surface for no gain.

---

## 9. Order transaction boundary

### Today

```
BEGIN … order, items, sales_order, gst_output_tax, gst_movement_ledger … COMMIT
```
No inventory. No payment. No ledger.

### Proposed — COD / "pay later" (implementable now, no external system)

```
BEGIN
  validate session, user, address, place of supply
  resolve products
  compute GST                            (unchanged)
  decrement inventory, ordered by (product_id, size)     ← new, atomic UPDATE
  insert customer_order / items / sales_order            (unchanged)
  insert inventory_movement rows                         ← new
  insert gst_output_tax / gst_movement_ledger            (unchanged)
  insert payment_transaction  (direction=IN, status=AWAITING_COLLECTION)  ← new
  insert outbox row (ORDER_PLACED)                       ← new, optional
COMMIT
```

Everything here is local to PostgreSQL, so one boundary is correct.

### Proposed — prepaid, once a gateway exists

**Must not** be one transaction. An HTTP call to a provider inside a DB transaction
holds row locks on the hot inventory row for the provider's latency.

```
TX-1  BEGIN
        reserve inventory (available−, reserved+)
        insert order (PENDING_PAYMENT)
        insert payment_transaction (INITIATED, idempotency_key)
      COMMIT
          ↓
      call provider  ← outside any transaction
          ↓
TX-2  (webhook, de-duplicated on provider_reference)
      BEGIN
        append payment_transaction (CAPTURED)
        reserved− , movement SALE
        order → CONFIRMED
        post ledger: Dr Clearing, Cr Receivable
      COMMIT
```

Plus a **sweeper** for reservations whose payment never resolves — release after a
timeout, movement `RELEASE`. Without it, an abandoned checkout holds stock forever.

### Operations that must never be inside the DB transaction

Payment provider calls · email/SMS · shipping API · e-invoice/e-way bill submission ·
S3 uploads · webhook fan-out.

---

## 10. Payment architecture proposal

### Entities — with justification for each, and what is rejected

| Entity | Verdict | Why |
|---|---|---|
| **`payment_transaction`** | **Required** | Immutable, append-only record of every money movement with a direction. Partial payment means several receipts against one order, so this must be many-per-order from the start |
| **`refund`** (separate table) | **Rejected** | A refund is a `payment_transaction` with `direction = OUT`, `type = REFUND`, `related_transaction_id` pointing at the original. A second table duplicates the state machine and creates two places for the truth to diverge |
| **`payment_attempt`** | **Deferred** | Only meaningful with a gateway that can fail mid-flight. Until then an attempt *is* a transaction row in `FAILED` |
| **`settlement` / `payout`** | **Not required** | There are **no sellers** and no marketplace commission in this application. Do not build it (§16: do not fabricate requirements) |
| **`payment_provider_reference`** | **Rejected as a table** | A nullable unique column on `payment_transaction` |

### Shape

```
payment_transaction
  id, uuid
  order_code            → customer_order.order_code   (FK)
  direction             IN | OUT
  type                  PAYMENT_RECEIVED | PAYMENT_REFUND
  method                CARD | UPI | COD | PARTIAL
  amount_paise          bigint, > 0         (CHECK)
  status                see §11
  idempotency_key       UNIQUE NOT NULL     ← client-supplied, per checkout attempt
  provider_reference    UNIQUE NULL         ← gateway id; de-duplicates webhooks
  related_transaction_id → self (refund → original)
  created_at, created_by
```

Append-only: a status change appends a row, it never updates one (§22 of the audit).
Current status = latest row per `order_code` — or a small `current_status` column
maintained in the same transaction if reads prove expensive. Measure before adding it.

---

## 11. Payment state machine

Because **no gateway exists**, the honest model has two tracks.

### Track 1 — COD (and today's CARD/UPI, which behave as "pay later")

```
AWAITING_COLLECTION ──▶ COLLECTED ──▶ SETTLED
        │
        └──▶ CANCELLED
```

### Track 2 — prepaid, when a gateway is integrated

```
INITIATED ──▶ PENDING ──▶ AUTHORIZED ──▶ CAPTURED ──▶ SETTLED
    │            │            │              │
    │            ▼            ▼              ▼
    └──────▶ FAILED        VOIDED      REFUND_PENDING ──▶ REFUNDED
                                                    └──▶ REFUND_FAILED
```

### Partial payment

Not a status — **two rows**: one prepaid `CAPTURED` for the deposit, one
`AWAITING_COLLECTION` for the balance. Outstanding = order total − Σ settled IN.
This is why `payment_transaction` is many-per-order.

### Invalid transitions to reject in the service

`REFUNDED → CAPTURED` · `FAILED → SETTLED` · `CANCELLED → COLLECTED` ·
`SETTLED → PENDING` · any transition out of a terminal state
(`SETTLED`, `REFUNDED`, `FAILED`, `VOIDED`, `CANCELLED`) · refund amount exceeding
Σ captured − Σ already refunded.

Enforced in the **application service**, not a trigger: the legal set depends on
method, actor and refund arithmetic across rows (§12).

---

## 12. Refund state machine

```
REFUND_PENDING ──▶ REFUND_PROCESSING ──▶ REFUNDED
                            └──────────▶ REFUND_FAILED ──▶ (retry, same idempotency key)
```

Rules: refundable only from `CAPTURED`/`COLLECTED`/`SETTLED`; cumulative refunds may
never exceed the captured total (service check **and** a DB constraint is not possible
across rows — so this is a service invariant backed by a reconciliation report);
a refund never mutates the original row.

---

## 13. Idempotency strategy

| Operation | Key | DB protection | Retry behaviour |
|---|---|---|---|
| Checkout | client-generated `idempotency_key` sent with the order | `UNIQUE` on `payment_transaction.idempotency_key` | Second request hits the unique violation → return the **existing** order, not an error |
| Gateway webhook | `provider_reference` | `UNIQUE NULL` | Duplicate callback is a no-op |
| Refund request | `refund_reference` (reuse `idempotency_key` column) | `UNIQUE` | Second request returns the first refund |
| Order code | `VO-yyyyMMdd-XXXXXXXX` | `customer_order_order_code_key` UNIQUE | **8 hex chars/day ≈ 1.2% collision/day at 100k orders/day** → widen or use a sequence |
| Journal posting | `(source_type, source_id)` | `ux_journal_source` partial unique | Already safe |
| Inventory movement | `(reference_type, reference_id, movement_type)` | proposed partial unique | Prevents double-decrement on replay |

The pattern `SELECT → if not exists → INSERT` must not be used anywhere here; the
unique index is the protection.

---

## 14. Inventory movement strategy

**Append-only audit/history — not the source of truth.**

Current stock stays on `inventory_product_size_stock` because stock is read on every
product card, bag render and checkout; deriving it by summing history would be
O(history) on the hottest read in the system. The movement table exists to explain
*how* the current number was reached, and to be reconcilable against it.

Fields, each justified:

| Field | Why |
|---|---|
| `product_id`, `size` | the stock grain |
| `movement_type` | RESTOCK/PURCHASE/SALE/RESERVATION/RELEASE/RETURN/DAMAGE/ADJUSTMENT/CANCELLATION/REPLACEMENT |
| `quantity` (signed) | the delta |
| `available_after`, `damaged_after` | makes the row self-verifying and lets reconciliation find the exact divergent movement without replaying all history |
| `reference_type`, `reference_id` | order code, return id, purchase invoice id |
| `reason` | required for `ADJUSTMENT` and `DAMAGE`; nullable otherwise |
| `created_by`, `created_at` | audit (§23) |

Omitted deliberately: `before_quantity` (derivable from `after − quantity`),
`warehouse_id` (no warehouses), `seller_id` (no sellers).

---

## 15. Damaged inventory strategy

The three conditions already exist in code and data (`PRODUCT_OK`, `DAMAGED`, `LOST`).

```
Customer return request  (order → READY_TO_PICKUP)
        ↓
Admin inspection — ReturnProcessingService.verify   ← authorization required
        ↓
   PRODUCT_OK          DAMAGED              LOST
        ↓                 ↓                   ↓
  available += q     damaged += q      no stock movement
  movement RETURN    movement DAMAGE   write-off
```

**Financial and GST impact is a separate decision from the inventory one** — the
existing Javadoc already says this, correctly. `gstAdjustmentRequired` governs whether
a credit note is raised; `return_condition` governs stock. A damaged return can still
warrant a credit note.

Damaged stock is **not** written off to expense automatically — whether it hits
`5900 Other Expense` or sits as an inventory reserve is an accounting policy decision
(§27).

---

## 16. Accounting treatment

The existing `Dr 1100 Receivable / Cr Sales / Cr Output GST` is **correct and must not
change**. All proposals are additive.

| Event | Posting | Status |
|---|---|---|
| Supply (invoice issued) | Dr 1100 AR · Cr 4010 Sales · Cr 2100/2110/2120 GST | **exists** |
| Prepaid captured | Dr *Payment Gateway Clearing* · Cr 1100 AR | **needs new account — confirm** |
| Gateway settles to bank | Dr 1020 Bank · Cr *Clearing* | **needs new account — confirm** |
| COD collected by courier | Dr *COD Receivable / Courier* · Cr 1100 AR | **confirm** |
| COD remitted | Dr 1020 Bank · Cr *COD Receivable* | **confirm** |
| Partial: deposit | Dr Clearing · Cr 1100 AR (part) — AR balance = outstanding | follows from above |
| Refund | Dr 4010 Sales (or Sales Returns) · Dr Output GST reversal · Cr Bank/Clearing — **appended, never a mutation of the original** | **confirm which account** |
| COD fee ₹50 | Dr AR · Cr ? | **confirm: revenue, or recovery of courier cost?** |

A clearing account is the standard way to represent "customer has paid, the money is
not in our bank yet". Introducing one changes the chart of accounts, so it is flagged
for confirmation rather than assumed.

---

## 17. GST interaction

**No change.** Explicitly:

- Payment, in any method, has **no GST effect**. Liability arises on supply.
- Cancellation before supply → no GST document; the `gst_output_tax` row must be
  superseded, not deleted (the existing pattern).
- Return with `gstAdjustmentRequired` → credit note through the existing
  `GstCreditNoteService`.
- Historical GST is **never** recalculated from current `gst_tax_rules`.
- **No GST trigger.** Rate selection depends on HSN, price band, buyer/seller state
  and transaction type — request context the database does not have.

---

## 18. Trigger decision matrix

| Proposed trigger | Classification | Correct mechanism |
|---|---|---|
| Inventory decrement | **NOT RECOMMENDED** | Atomic conditional UPDATE in the order transaction |
| Inventory movement insert | **NOT RECOMMENDED** | Same service, same transaction — a trigger would hide the audit trail from the code that caused it and cannot record `created_by` |
| Damaged stock transition | **NOT RECOMMENDED** | Application service — needs authorization and an inspection decision |
| GST calculation | **NOT RECOMMENDED** | Application service (§17) |
| Payment processing | **NOT RECOMMENDED** | Application service + external call outside the transaction |
| Refund processing | **NOT RECOMMENDED** | Application service |
| Accounting posting | **NOT RECOMMENDED** | `JournalService` — already idempotent and correct |
| Audit logging | **OPTIONAL, not now** | Service — a trigger cannot see the authenticated user or request id |
| Timestamp maintenance | **OPTIONAL** | Low value; JPA already sets them |
| Period locking | **REQUIRED — already exists** | `trg_journal_period_lock`, `trg_gst_movement_period_lock` |
| Negative stock prevention | **NOT RECOMMENDED as a trigger** | `CHECK (available_quantity >= 0)` |

**Both existing triggers are kept exactly as they are.** They enforce a cross-table
invariant that a CHECK cannot express, do one indexed lookup, write nothing, and call
nothing external.

**Net: zero new triggers.**

---

## 19. Failure / retry matrix

| Scenario | Expected DB state | User-visible | Retry | Idempotency | Recovery |
|---|---|---|---|---|---|
| Inventory update succeeds, order insert fails | Both rolled back — same transaction | "Could not place order" | Safe | not needed | automatic |
| Order insert succeeds, payment row fails | Both rolled back | error | Safe | not needed | automatic |
| Payment succeeds at provider, response lost | Order `PENDING_PAYMENT`, payment `PENDING` | "Payment processing" | Client retry hits the same `idempotency_key` | **required** | webhook reconciles; sweeper resolves |
| Webhook arrives twice | One `CAPTURED` row | unchanged | — | `provider_reference` UNIQUE | second is a no-op |
| Payment succeeds, reservation fails | Payment `CAPTURED`, order not confirmable | "Refund initiated" | — | refund key | **auto-refund** — never keep money for stock we cannot ship |
| Reservation succeeds, payment fails | `reserved` released, movement `RELEASE` | "Payment failed" | Safe | — | sweeper backstop |
| User retries checkout | One order | same confirmation | Safe | `idempotency_key` | return existing order |
| Refund submitted twice | One refund row | one refund | Safe | `refund_reference` UNIQUE | second returns the first |
| App crash mid-checkout | Transaction rolled back by PostgreSQL | error/timeout | Safe | — | automatic |
| DB connection drops | Rolled back | error | Safe | — | automatic |
| Deadlock | One transaction aborted (40P01) | error | **Safe to retry once** | — | ordered updates make this rare |
| Provider timeout | Payment `PENDING` | "Processing" | Do **not** auto-retry the charge | — | reconcile by status query, then webhook |

Current reality for comparison: a failed checkout today creates a **second order row**
with `PAYMENT_FAILED`, and a retry creates a **third order**. There is no idempotency
anywhere on this path.

---

## 20. Security findings

Exact names, not fixed.

| # | Finding | Location |
|---|---|---|
| **S1** | **IDOR — any signed-in shopper can read any order by code.** No ownership check against the session | `ClientOrderController` → `ClientOrderServiceImpl.getOrderByCode` (`GET /client/order/{orderCode}`) |
| **S2** | **Stock mutation is unauthenticated.** No `@PreAuthorize`; the app-wide chain is `permitAll` | `InventoryProductController.addStock` (`PATCH /inventory-product/{uuid}/stock/add`) — 10 endpoints, **0** `@PreAuthorize` |
| **S3** | **Accounting is unauthenticated** — 16 endpoints, **0** `@PreAuthorize`, including `POST /accounting/backfill`, `POST /accounting/periods/{period}/close`, `/reopen`, `POST /accounting/journals/{id}/reverse`, `POST /accounting/opening-balances` | `AccountingController` |
| **S4** | Expense and payroll endpoints unauthenticated | `ExpenseController` (3, 0), `SalaryPaymentController` (4, 0) |
| **S5** | **Order status changed from a controller** — `order.setStatus("READY_TO_PICKUP")` with no transition validation | `ClientOrderController` return endpoint |
| **S6** | **Bean Validation inert** — `jakarta.validation-api` present, no implementation on the classpath, so `@Valid`, `@NotBlank`, `@NotEmpty`, `@Min` are all no-ops. Quantity 0/−1 and empty-item orders are accepted | whole application; `pom.xml` |
| **S7** | Damaged-stock marking will need authorization when built | `ReturnProcessingService.verify` — currently `@PreAuthorize` on `ReturnsController` (8/8) ✅ |
| **S8** | Refund authorization does not exist yet — must be admin-only and separated from the requester | future |
| **S9** | No customer/seller data isolation model beyond the session → email lookup | `ClientOrderServiceImpl` |

`ReturnsController` and `SalesInvoiceController` are correctly protected (8/8 each) —
the gap is not uniform, which suggests omission rather than policy.

---

## 21. Required schema changes

```
ALTER  inventory_product_size_stock
        + available_quantity bigint NOT NULL DEFAULT 0
        + damaged_quantity   bigint NOT NULL DEFAULT 0
        + CHECK (available_quantity >= 0 AND damaged_quantity >= 0)
        (backfill available_quantity := initial_stock)
        [+ reserved_quantity, with the gateway — not now]

NEW    inventory_movement            (append-only, §14)
         + index (product_id, size, created_at)
         + partial unique (reference_type, reference_id, movement_type)

NEW    payment_transaction           (§10)
         + UNIQUE idempotency_key
         + UNIQUE provider_reference (nullable)
         + FK order_code → customer_order.order_code
         + CHECK amount_paise > 0

FK+IX  customer_order_item.customer_order_id
       gst_output_tax.customer_order_id
       journal_entry_line.journal_entry_id
       sales_invoice_item.sales_invoice_id
       inventory_product_size_stock.product_id

CHART  payment clearing / COD receivable accounts  ← after §27 confirmation
```

Every migration is additive and nullable-or-defaulted, so it is safe to apply before
the code that uses it.

---

## 22. Required backend changes

1. `PlaceOrderRequest` — add `paymentMethod` and `idempotencyKey` (the UI already
   sends the first).
2. `InventoryService` — new: atomic decrement/increment, ordered by `(product_id,size)`.
3. `ClientOrderServiceImpl.placeOrder` — decrement inventory, write movements, write
   the payment transaction, inside the existing boundary.
4. `PaymentService` — new: state machine, idempotent creation, refund arithmetic.
5. `InventoryProductServiceImpl.addStock` — `@Transactional` + atomic UPDATE.
6. `ReturnProcessingService` — implement the documented inventory effect.
7. `AccountingPostingService` — add the collection leg; call `postSale` from the order
   flow or keep backfill, **decide explicitly** (§27).
8. `OrderStatusService` — one place that validates transitions; remove `setStatus`
   from the controller.
9. `pom.xml` — `spring-boot-starter-validation`.
10. `@PreAuthorize` on `AccountingController`, `InventoryProductController`,
    `ExpenseController`, `SalaryPaymentController`; ownership check in
    `getOrderByCode`.

## 23. Required frontend changes

1. Generate and send an `idempotencyKey` per checkout attempt; reuse it on retry.
2. Send the COD fee and partial split as **server-verified** values — the server must
   compute them, the client must not be trusted for money.
3. Show outstanding balance for partial orders.
4. Handle `409 / already placed` by showing the existing order, not an error.
5. Remove the client-side `+50` and `/2` once the server owns them.

---

## 24. Migration sequence

```
M1  add inventory columns + CHECK, backfill available := initial_stock   (no code depends on it yet)
M2  create inventory_movement
M3  create payment_transaction
M4  add FKs + supporting indexes                                          (verify no orphans first)
M5  chart of accounts additions                                           (after §27)
```

Each is independently deployable and backward compatible: old code ignores new
columns; new code is deployed after its migration.

## 25. Rollback strategy

- M1–M3: additive; roll back by ignoring. Dropping columns loses movement history —
  prefer forward fix.
- M4: **must** be preceded by an orphan check; a failing FK creation aborts cleanly.
  Rollback = drop the constraint, keep the index.
- Code: deploy behind a flag (`inventory.enforce=true`) so decrement can be disabled
  without a redeploy if it rejects legitimate orders on day one.
- Never roll back by deleting financial or movement rows — reverse them.

## 26. Test strategy

| Layer | What |
|---|---|
| Unit | State machine transitions (valid and every invalid one); refund arithmetic; movement quantity maths |
| Integration | Order transaction rolls back fully when the decrement fails; movement rows match the stock delta |
| **Concurrency** | **N threads buying the last unit → exactly one succeeds, `available = 0`, never negative.** Two concurrent `addStock` → both applied. Multi-item orders in opposite sequences → no deadlock |
| Idempotency | Same `idempotency_key` twice → one order; duplicate webhook → one capture; double refund → one refund |
| Reconciliation | Σ movements per SKU == `available + damaged`; Σ settled IN per order == AR cleared |
| Existing suites | The Cucumber/Playwright suite already covers the GST and order paths — run it before and after to prove business results are unchanged |

The automation suite's `@mutates` order scenarios become the regression net for this
work.

---

## 27. Open business decisions — these block implementation

1. **Do CARD and UPI actually process money?** There is no gateway. Today choosing
   CARD behaves exactly like "pay later". Either a provider is integrated, or the
   methods should be labelled honestly. **This decides whether `reserved_quantity` and
   the whole prepaid track are built now or later.**
2. **COD ₹50 fee** — revenue, or recovery of courier cost? Which account? Is it taxable?
3. **Partial payment** — is 50% policy, or configurable? When is the balance due? What
   happens if it is never paid?
4. **Payment clearing account** — introduce `Payment Gateway Clearing` and
   `COD Receivable` to the chart of accounts, or post straight to Bank?
5. **Refund account** — reduce `4010 Sales`, or a separate `Sales Returns` account?
6. **Damaged stock write-off** — expense immediately, or hold as an inventory reserve?
7. **Should checkout post to the ledger automatically**, or stay a controlled backfill?
   Automatic is more correct; it also means a ledger failure can fail a customer order.
   My recommendation: post via the **outbox**, so the order commits and the journal
   follows reliably without coupling.
8. **Reservation timeout** — how long may an unpaid order hold stock?
9. **Oversell policy** — hard reject, or allow backorder for selected SKUs?
10. **Are historical orders with no stock effect to be backfilled** into inventory, or
    is the new model effective from a cut-off date?

---

## 28. P0 implementation plan

### A. Implementable immediately — no business input, no external system

1. `spring-boot-starter-validation` (S6). Smallest change, largest correctness gain —
   it is what currently lets quantity-0 orders through.
2. Ownership check in `getOrderByCode` (S1).
3. `@PreAuthorize` on `AccountingController`, `InventoryProductController`,
   `ExpenseController`, `SalaryPaymentController` (S2–S4).
4. `addStock` → `@Transactional` + atomic UPDATE (C2).
5. FKs + supporting indexes (M4), after the orphan check.

### B. Requires business/accounting confirmation

Payment methods and whether a gateway exists (#1) · COD fee (#2) · partial policy (#3)
· clearing accounts (#4) · refund account (#5) · damage write-off (#6) · automatic vs
backfill posting (#7) · reservation timeout (#8) · oversell policy (#9) · historical
backfill (#10).

### C. Requires schema migration

M1 inventory columns · M2 `inventory_movement` · M3 `payment_transaction` ·
M4 FKs/indexes · M5 chart of accounts.

### D. Requires application changes

All ten items in §22, plus the five frontend items in §23.

### E. Must remain unchanged

- Both existing triggers.
- GST calculation, snapshotting and the credit/debit note flow.
- `JournalService` idempotency, reversal-not-deletion, period locking.
- `Dr Receivable / Cr Sales / Cr Output GST` on supply.
- Integer-paise money representation and basis-point rates.
- Atomic invoice numbering.
- The 20 existing CHECK constraints.

### F. Recommended implementation order

```
1. A1  validation                    ← unblocks every quantity/emptiness invariant
2. A2–A3 authorization + IDOR        ← smallest, highest-severity security fixes
3. A4  addStock safety
4. M1 + M2  inventory schema + movement ledger
5. Inventory decrement in placeOrder, ordered updates, CHECK, movement rows
       → concurrency test: N buyers, 1 unit, exactly one wins
6. A5 / M4  FKs + indexes
7. ── decision gate: §27 items 1–7 ──
8. M3 + PaymentService: COD/pay-later track only, with idempotency_key
9. Collection leg in the ledger (Dr Cash/Clearing, Cr AR)
10. Return → inventory effect (PRODUCT_OK / DAMAGED / LOST)
11. Prepaid track, reserved_quantity, webhooks, sweeper — only if #1 says a gateway exists
```

Steps 1–6 are independent of every open question and can start now. Step 7 is a hard
gate: building the payment domain before the answers to §27 means guessing at
accounting treatment, which §16 forbids.

---

**Nothing in this document has been implemented.** Confirm the §27 decisions — or tell
me to start at step 1 of §28.F, which needs none of them.
