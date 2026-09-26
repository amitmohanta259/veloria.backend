# P0-4 — Payment Business Decision Register

**Status:** decision gate. **No application code, schema, migration, test or configuration was changed by this task.**

Every statement below labelled *Existing* was verified against source, the live schema, or a runtime
probe. Nothing is inferred from naming. Labels used throughout:

| Label | Meaning |
|---|---|
| **Existing** | Verified present in the repository or the live database |
| **Proposed** | A recommendation for review — not implemented, not decided |
| **Business decision** | Requires product/business confirmation before anything is built |
| **Vendor decision** | Requires an external/commercial decision (gateway, acquirer, terms) |

A companion document, `docs/payment-business-decision-and-architecture.md`, already covers candidate
*architecture* in depth. This register deliberately does not repeat it: its job is to enumerate the
**decisions**, their current behaviour, and their blast radius.

---

## 1. Executive summary

**Veloria collects no money.** There is no payment domain in the backend at all — not a table, not a
column, not a field, not an enum. An order reaches `ORDER_PLACED`, consumes stock, and books revenue
against Accounts Receivable, with nothing ever collected and no record that collection is owed to a
particular method.

Three findings shape everything downstream:

1. **Payment is absent, not partial.** A word-boundary search of the backend for `paymentMethod`,
   `paymentStatus`, `transactionId`, `gateway`, `webhook`, `UPI`, `COD`, `PARTIAL`, `Razorpay`,
   `Stripe`, `PayU`, `Cashfree`, `PhonePe` and `Paytm` returns **zero files**. There is no partial
   implementation to finish and no provider remnant to remove.

2. **The checkout UI is a facade.** The payment screen offers Card, UPI, COD and Partial; collects
   card number, name, expiry, **CVV** and UPI VPA; computes a 50% partial amount and a ₹50 COD fee —
   and sends **none of it**. The card and VPA fields are never transmitted anywhere. `paymentMethod`
   *is* sent and is silently discarded by the backend, which has no such field.

3. **Stock is consumed by an order that was never paid for.** `ORDER_PLACED` is in the
   stock-consuming set, so placing an order deducts inventory immediately. With real payment
   introduced, a failed or abandoned payment would hold real stock unless a decision is made first.

Verified by runtime probe (order created, then deleted; database returned to its prior 7 rows):

```
POST /client/order/place
  { "paymentMethod":"CARD", "razorpayPaymentId":"pay_FAKE123", "amountPaid":99999, … }
→ 200 OK   order VO-20260925-5CF45FEA
→ stored:  total_value 1395520 paise, status ORDER_PLACED
→ paymentMethod, razorpayPaymentId and amountPaid: accepted and discarded
```

The order was created, priced by the server, and marked placed. The claimed payment identifier and
amount were neither validated nor stored nor rejected.

**The central business question is therefore not "which gateway".** It is: *does Veloria intend to
collect money online at all, and if so, at what point in the order lifecycle does an order become
payable, paid, and shippable?* Until that is answered, no payment table can be designed correctly.

---

## 2. Current payment architecture

### 2.1 Backend — verified absent

Word-boundary, case-sensitive search across `src/main/java` (701 classes):

| Term | Files |
|---|---|
| `paymentMethod`, `paymentTransaction`, `paymentStatus`, `transactionId` | **0** |
| `gateway`, `webhook` | **0** |
| `Razorpay`, `Stripe`, `PayU`, `Cashfree`, `PhonePe`, `Paytm` | **0** |
| `UPI`, `COD`, `PARTIAL` | **0** |

A case-insensitive search for "payment" matches 30 files. **None is customer payment.** Every one
belongs to a different domain:

| Domain | Files | What it is |
|---|---|---|
| Payroll | `SalaryPayment*`, `PayrollService` | Staff salary disbursement |
| GST remittance | `GstPaymentEntity`, `postGstPayment` | Tax paid to the authority |
| Procurement | `PurchaseOrderRequest`, `SupplierEntity` | Vendor payment *terms* (text) |
| Exchanges | `OrderExchangeRequestEntity` | A computed settlement *label* (§2.4) |
| Orders | `ClientOrderServiceImpl:468` | The string literal `"PAYMENT_FAILED"` |

### 2.2 Database — verified absent

Tables matching `%payment%`, `%transaction%`, `%refund%`, `%settle%`, `%gateway%`:

```
gst_itc_transaction     GST input-credit ledger
gst_payment             GST remitted to the authority
salary_payment          payroll
salary_payment_detail   payroll
```

None relates to customer payment. `customer_order` has **41 columns and not one payment column** —
no `payment_method`, `payment_status`, `amount_paid`, `amount_due`, `transaction_id` or
`paid_at`. `shipping_value`, `discount_value`, `coupon_code` and `round_off` exist but are `0` on
every one of the 7 live orders and are never written at checkout.

### 2.3 Frontend — a complete UI over nothing

`veloria.frontend/frontend-client/src/views/Payment.tsx`:

| Element | Existing behaviour |
|---|---|
| Method selector | `"card" \| "cod" \| "partial" \| "upi"`, defaults to `card` |
| Card fields | `cardNumber`, `cardName`, `cardExpiry`, `cardCvv` — **collected, never transmitted** |
| UPI fields | `upiId`, `partialUpiId` — **collected, never transmitted** |
| Partial amount | `partialAmount = grandTotalRupees / 2` — display only |
| COD fee | `dueNow = grandTotalRupees + 50` — display only |
| Sent to backend | `deliveryLocation`, `currency`, `paymentMethod`, `items`, `clientOrderReference` |
| Received by backend | `deliveryLocation`, `currency`, `items`, `clientOrderReference` |

`paymentMethod` is the only payment datum that crosses the wire, and Jackson drops it because
`PlaceOrderRequest` has no matching field (Spring Boot's default is to ignore unknown properties;
no `FAIL_ON_UNKNOWN_PROPERTIES` override exists).

The admin portal contains no payment functionality. Its single `gateway` match is an unrelated
"Global VPN Gateway" label in a staff-dossier component.

### 2.4 The one place a refund already has a name

`OrderExchangeService` computes an exchange price difference and stores a direction:

```java
String settlement = paymentDiff > 0 ? COLLECT_FROM_CUSTOMER
                  : paymentDiff < 0 ? REFUND_TO_CUSTOMER
                  : NO_SETTLEMENT;
```

`REFUND_TO_CUSTOMER` is written to `order_exchange_request.settlement_direction` and **nothing reads
it**. It is a reporting label describing money that ought to move, with no mechanism to move it.
This is an existing, unfunded obligation the payment design must eventually absorb (**D33**).

---

## 3. Current checkout / payment flow

```text
Customer
  ↓
Cart                          bag rows, server-priced
  ↓
Checkout                      address selected; GST previewed
  ↓
Payment selection             ◄── EXISTS ONLY IN THE BROWSER
  │                               card/CVV/VPA collected and discarded
  │                               50% and ₹50 computed for display
  ↓
POST /order/place             paymentMethod sent → silently dropped
  ↓
Order creation                status = ORDER_PLACED
  │                           total = server-computed; no fee, no discount
  ↓
Inventory deduction           ◄── STOCK CONSUMED HERE, UNPAID
  │                           reserveInventory() → FOR UPDATE → committed
  ↓
GST output tax + movements    written in real time
  ↓
[ NO PAYMENT EVENT ]          ◄── NOTHING COLLECTS, AUTHORIZES OR RECORDS MONEY
  ↓
Accounting                    Dr AR / Cr Sales / Cr Output GST
                              …but only when backfill() is run (§6.2)
```

**Where payment is absent:** everywhere after "Payment selection". There is no authorization step,
no capture, no callback, no settlement, no ledger entry for cash, and no field recording how the
customer intended to pay.

---

## 4. Existing payment-related code inventory

| Artefact | Location | What it actually does | Label |
|---|---|---|---|
| `PAYMENT_FAILED` status | `ClientOrderServiceImpl:468` | Written by `recordFailedOrder`; creates a customer_order row that consumes no stock | Existing |
| `POST /order/record-failed` | `ClientOrderController` | Called by the browser when `/order/place` errors; records an attempt | Existing |
| `paymentMethod` in payload | `Payment.tsx:239` | Sent, discarded server-side | Existing |
| Card / CVV / VPA inputs | `Payment.tsx:114–127` | Held in React state, never transmitted | Existing |
| 50% partial calculation | `Payment.tsx:227` | Float rupee arithmetic, display only | Existing |
| ₹50 COD fee | `Payment.tsx:229` | Float rupee arithmetic, display only, never reaches the order | Existing |
| `REFUND_TO_CUSTOMER` | `OrderExchangeService:224` | A stored label; no execution path | Existing |
| `postGstPayment` | `AccountingPostingService:234` | `Dr GST Payable / Cr Bank` — the **only** cash-movement posting in the system | Existing |
| `CASH` account `1010` | `AccountingPostingService:41` | Defined; **used by no posting**; zero ledger rows | Existing |
| "Retry payment" link | `OrderHistory.tsx:521` | Links a `PAYMENT_FAILED` order to `/payment` | Existing |
| Gateway / webhook / provider code | — | **Does not exist** | Existing (absent) |

---

## 5. Order lifecycle

### 5.1 Status inventory

The state machine lives **in the admin React component**, not the backend.
`SalesOrderServiceImpl.updateOrderStatus(orderCode, newStatus)` writes whatever string it is given —
no enum, no validation, no transition check.

| Status | Exists? | Can the application create it? | Where | Consumes stock? |
|---|---|---|---|---|
| `ORDER_PLACED` | Yes | Yes | `ClientOrderServiceImpl:186` (checkout) | **Yes** |
| `PAYMENT_FAILED` | Yes | Yes | `ClientOrderServiceImpl:468` (`recordFailedOrder`) | No |
| `PACKED` | Yes | Yes | Admin UI → `PATCH /sales-order/order/{code}/status` | **Yes** |
| `IN_TRANSIT` | Yes | Yes | Admin UI → same endpoint | **Yes** |
| `OUT_FOR_DELIVERY` | Yes | Yes | Admin UI → same endpoint | **No** ⚠ |
| `DELIVERED` | Yes | Yes | Admin UI → same endpoint; sets `delivered_at` | **Yes** |
| `CANCELLED` | Yes | Yes | `POST /sales-order/order/{code}/cancel` | No |
| `READY_TO_PICKUP` | Yes | Yes | `ClientOrderController:119` — set when a **return is requested** | No |
| `PICKED_UP` | Yes | Yes | Admin UI (return leg) | No |
| `IN_TRANSIT_TO_SELLER` | Yes | Yes | Admin UI (return leg) | No |
| `RECEIVED` | Yes | Yes | Admin UI (return leg) | No |
| `RETURNED` | Yes | Yes | Return processing | No (credited back) |
| `DISPATCHED` | **Name only** | **No** | Appears solely in the stock SQL's consuming list | Yes (unreachable) |
| `DONE` | **Name only** | **No** | Appears solely in the stock SQL's consuming list | Yes (unreachable) |
| `IN_PROGRESS` | Not for orders | n/a | Belongs to `purchase_order`, not `customer_order` | n/a |

Live data: `DELIVERED` 3, `RETURNED` 3, `OUT_FOR_DELIVERY` 1.

### 5.2 Two findings that block payment design

**⚠ `OUT_FOR_DELIVERY` releases stock.** The stock-consuming set in
`InventoryProductRepository` is `('ORDER_PLACED','PACKED','IN_TRANSIT','DISPATCHED','DONE','DELIVERED')`.
`OUT_FOR_DELIVERY` is absent. An order therefore *releases* its units when it goes out for delivery
and *re-consumes* them on `DELIVERED`. Live example: order `VO-20260824-D7ECB6D9` holds 1 unit that
is physically on a van and simultaneously counted as available for sale.

This matters for payment because **`DELIVERED` is the natural COD collection trigger** (**D16**), and
the path to it passes through a state that mis-states inventory. Two of the six consuming statuses
(`DISPATCHED`, `DONE`) are unreachable names, so the set has drifted from the lifecycle the UI drives.

**⚠ The status endpoints are unauthenticated.** Verified by probe against a nonexistent order code
(nothing modified):

```
PATCH /api/master/sales-order/order/VO-DOES-NOT-EXIST/status   no token → 400 "Order not found"
POST  /api/master/sales-order/order/VO-DOES-NOT-EXIST/cancel   no token → 400 "Order not found"
GET   /api/master/sales-order/stats                            no token → 200 + live revenue
```

The service was reached in every case, so no authentication filter guards
`/api/master/sales-order/**`. `SalesOrderController` carries no `@PreAuthorize`, and the path is in
neither `GST_PATHS` nor the P0-1 `ADMIN_TOKEN_PATHS`. Today that means anyone on the network can mark
an order `DELIVERED` or `CANCELLED`. If `DELIVERED` becomes the event that books COD cash, this
becomes a route to fabricating collections (**D26**).

*Documented as a finding, not fixed — this task changes no code. It is a P0-1-class gap and should be
closed before, or as part of, payment work.*

---

## 6. Accounting interaction

### 6.1 The four financial events, and which exist

| Event | Exists? | Posting | Trigger |
|---|---|---|---|
| **SALE** | Yes | `Dr AR 1100 / Cr Sales 4010 / Cr Output CGST+SGST+IGST` | `postSale` — backfill only (§6.2) |
| **PAYMENT COLLECTION** | **No** | Would be `Dr Cash/Bank / Cr AR` | — nothing produces it |
| **REFUND** | **No** | Would be `Dr Sales or Refunds / Cr Cash/Bank` | GST credit notes exist; no money moves |
| **GST REMITTANCE** | Yes | `Dr GST Payable 2200 / Cr Bank 1020` | `postGstPayment` |

`credit(RECEIVABLE, …)` appears **nowhere** in the codebase. AR is debited by sales and credited only
by manual journal reversals. Account `1010 CASH` is declared and has **zero ledger rows**.

### 6.2 Sales are not posted at checkout

`postSale` has exactly one caller: `AccountingPostingService.backfill()` — *"posts every existing
business record that has no journal entry yet"*. Checkout writes the order, its items,
`gst_output_tax` and `gst_movement_ledger` in real time, but **no journal entry**. The double-entry
books are assembled by a batch run.

Consequence for payment: if payment postings were made real-time while sales remain batch, the ledger
would show collections against receivables that do not yet exist (**D28**).

### 6.3 Current ledger position (live)

| Account | Balance | Note |
|---|---|---|
| `1100` Accounts Receivable | **₹19,796.33** | Sales booked, nothing ever collected |
| `1010` Cash | ₹0.00 | Never used |
| `1020` Bank | −₹1,005.00 | GST remittance only |
| `4010` Sales | −₹16,857.90 | |
| `2100/2110/2120` Output GST | −₹398.40 / −₹398.40 / −₹2,141.63 | |

The ₹19,796.33 confirms the figure cited in **D14**, computed here from `journal_entry_line`. It is
composed of ₹79,185.23 of debits less ₹59,388.90 of credits, and **every credit came from a
`REVERSAL` entry** — not one rupee from a collection.

`/sales-order/stats` reports `totalRevenue: 1979628` paise against the ledger's 1979633 — a 5-paise
divergence between order totals and posted revenue, worth resolving as part of reconciliation design
(**D19**).

---

## 7. Inventory / payment interaction

```text
ORDER CREATED  ──►  inventory consumed (committed, FOR UPDATE serialised)
                         ↓
                  payment may fail  ──►  stock is already gone
```

**Existing:** `reserveInventory` runs inside `placeOrder`'s transaction before anything is written,
takes `SELECT … FOR UPDATE` on each product row in ascending id order, and the order row itself is
the deduction (availability = `initial_stock − sold + returned`). `ORDER_PLACED` consumes stock the
instant the transaction commits.

There is no reservation concept and — per the P0-2 and P0-3 constraints — none is proposed here.

**The business consequence that must be decided (D15):** with real online payment, the order is
created and stock deducted *before* the customer's money is confirmed. Three shapes are possible, and
this is a business choice, not a technical one:

| Shape | Stock during payment | Failure behaviour | Cost |
|---|---|---|---|
| **A — order first** (today's shape) | Consumed at `ORDER_PLACED` | Abandoned payments hold stock until someone cancels | Oversold-looking catalogue, manual cleanup |
| **B — payment first** | Not consumed until payment confirmed | Two customers can pay for the same last unit | Refunds and apologies on the loser |
| **C — short-lived hold** | Consumed, released by timeout | Needs a timeout job and a defined window | New moving part; contradicts "no reservation" unless revisited |

`PAYMENT_FAILED` is not in the consuming set, so a `recordFailedOrder` row holds no stock — the
existing design already assumes shape A with manual cleanup.

---

## 8. Business decision register

Ordered as specified; **D26–D35 are additions discovered during this inspection.**
Every "Current behaviour" entry is verified.

| ID | Decision | Current behaviour (verified) | Decision required | Impact |
|---|---|---|---|---|
| **D1** | Online payment | No collection of any kind exists | Will CARD/UPI actually take money? | Payment architecture — gates D2, D17–D25 |
| **D2** | Gateway | None implemented; no provider remnants | Select a provider *(vendor decision)* | Integration, PCI scope, settlement timing |
| **D3** | COD | UI option only; never reaches backend | Is COD genuinely offered? | Order lifecycle, cash handling |
| **D4** | COD fee | UI adds ₹50 in float rupees; never stored | Is ₹50 real? If so: is it revenue, and is it taxable? | Pricing, accounting, **GST** |
| **D5** | Partial payment | UI shows 50%; never reaches backend | Is partial payment offered? | Payment + AR ageing |
| **D6** | Partial percentage | `grandTotalRupees / 2`, float | Confirm the rule and the **rounding direction in paise** | Payment, money model |
| **D7** | Payment failure | No real payment; `recordFailedOrder` writes a `PAYMENT_FAILED` row | Does the order persist, retry, or vanish? Does stock release? | Order + inventory |
| **D8** | Payment success | No real payment | What state does the order enter, and what posts to the ledger? | Order + accounting |
| **D9** | Payment pending | Not implemented | How long may an order stay pending? Is it shippable? | Order lifecycle |
| **D10** | Payment cancellation | Not implemented | Customer-initiated abandonment: what happens to order and stock? | Order lifecycle |
| **D11** | Refund | No refund path; GST credit notes exist | Full/partial? To original method? Within what window? | Payment, accounting, **GST** |
| **D12** | Order cancellation | `cancelOrder` sets `CANCELLED` + reason. **Releases stock** (status not in consuming set). **No ledger reversal.** Unauthenticated | From which states? Who may? What reverses? | Inventory, payment, accounting |
| **D13** | AR settlement | AR never credited except by manual reversal | When is AR cleared — on capture, on settlement, on delivery? | Accounting |
| **D14** | Existing AR | **₹19,796.33**, all from uncollected sales | Write off, treat as legacy, or reconcile against real collections? | Accounting, opening balances |
| **D15** | Inventory timing | Stock consumed at `ORDER_PLACED`, before any payment | Does online-payment failure release stock, and after how long? | Inventory ↔ payment (see §7) |
| **D16** | COD collection | Delivery lifecycle exists but records no money; `DELIVERED` is settable by anyone | What event books COD cash, and who may raise it? | COD, accounting, security |
| **D17** | Payment idempotency | Not implemented. Order idempotency (P0-3) is separate and must not be reused for it | Define the payment-request idempotency key | Payment |
| **D18** | Webhooks | None | Is the gateway webhook authoritative over the browser return? | Payment correctness |
| **D19** | Reconciliation | None. 5-paise divergence already observed between order totals and posted revenue | Define the settlement-file reconciliation process and owner | Accounting |
| **D20** | Gateway fees | Not implemented | Expense on capture, or netted at settlement? | Accounting |
| **D21** | Tax on gateway fees | Not implemented | Input GST treatment on gateway commission *(requires tax review)* | GST, accounting |
| **D22** | Customer-facing payment status | Not implemented; `OrderHistory` shows `PAYMENT_FAILED` and offers a "retry payment" link | Which states does the customer see, in what words? | Frontend + backend |
| **D23** | Duplicate callbacks | Not implemented | Define idempotent callback handling | Payment |
| **D24** | Partial + refund combinations | Not implemented | May a partially-paid order be refunded? Which leg first? | Payment, order |
| **D25** | Payment timeout | Not implemented | How long before an unpaid order expires, and what then? | Inventory, order |
| **D26** | Authorization over order status | `/api/master/sales-order/**` is **unauthenticated** — verified. Anyone can set `DELIVERED` or `CANCELLED` | Who may advance an order, especially to the COD-collection state? | Security, COD integrity |
| **D27** | Stock-consuming status set | `OUT_FOR_DELIVERY` releases stock; `DISPATCHED`/`DONE` are unreachable names in the set | Confirm which statuses represent committed stock | Inventory accuracy |
| **D28** | Posting timing | Sales post to the ledger only via `backfill()`, not at checkout | Must sale posting become real-time before payment postings exist? | Accounting integrity |
| **D29** | Card data in the browser | `cardNumber`, `cardCvv`, `upiId` collected and discarded | Remove these fields, or replace with a hosted/tokenised field? **Collecting a CVV that is thrown away should not survive this decision** | PCI scope, trust |
| **D30** | Request strictness | `paymentMethod`, `razorpayPaymentId`, `amountPaid` accepted and silently ignored | Reject unknown/payment-shaped fields rather than dropping them? | API safety |
| **D31** | Cancellation accounting | Cancellation releases stock but leaves AR debited | What ledger event does a cancellation raise? | Accounting |
| **D32** | Ownership of the state machine | Transitions are enforced only by which buttons the admin UI renders; backend accepts any string | Must the backend own the state machine before payment states depend on it? | Order integrity |
| **D33** | Existing refund obligations | `REFUND_TO_CUSTOMER` computed on exchanges; nothing acts on it | Must the payment build settle these, and what about historical ones? | Payment, accounting |
| **D34** | Failed-order retry contract | Client links `PAYMENT_FAILED` orders to `/payment`; admin UI offers that status no action at all | Can a failed order be paid later, or must the customer start over? | Order lifecycle, UX |
| **D35** | Currency | `currency` accepted from the client, unvalidated, defaulting to `"INR"` | Is INR the only currency? Should non-INR be rejected? | Money model, pricing |

---

## 9. Technical decision register

**These are deliberately unanswered.** Each depends on a business decision above and should be
settled only once that answer exists.

| ID | Technical decision | Blocked by | Notes |
|---|---|---|---|
| T1 | Payment table structure | D1, D3, D5, D11 | Must store integer paise; one row per attempt, not per order |
| T2 | Transaction state machine | D7–D10, D32 | See §10 for a proposal |
| T3 | Order ↔ payment cardinality | D5, D24 | Partial payment forces one-order-to-many-payments |
| T4 | Gateway integration shape | D1, D2 | Hosted page vs SDK vs server-to-server; determines PCI scope |
| T5 | Webhook verification | D18, D23 | Signature check, replay window, raw-body preservation |
| T6 | Payment idempotency key | D17 | **Separate from P0-3's `clientOrderReference`**, which must not be reused |
| T7 | Retry and backoff | D9, D25 | Gateway status polling vs waiting for callback |
| T8 | Reconciliation model | D19, D20 | Settlement file ingestion, matching, exception queue |
| T9 | Accounting postings | D8, D13, D20, D28 | Collection, fee, refund and write-off postings |
| T10 | Refund transaction model | D11, D24, D33 | Refund as its own record referencing the original capture |
| T11 | Audit trail | D22, D26 | Who saw what, who moved money, immutable |
| T12 | Authorization model for payment/status endpoints | D26 | Must close the `/sales-order/**` gap |
| T13 | Money and rounding utilities | D6, D35 | Paise-only; a single rounding rule; no float |
| T14 | Stock release mechanism | D15, D25, D27 | Only if the business chooses a release policy |

---

## 10. Proposed payment state machine — **PROPOSAL ONLY, NOT IMPLEMENTED**

Nothing below exists in the codebase. Offered for review.

| State | Meaning | Who causes it | Money moved? | Terminal? | Webhook can produce? |
|---|---|---|---|---|---|
| `INITIATED` | A payment attempt was created; customer not yet sent to the provider | Application | No | No | No |
| `PENDING` | Customer is with the provider; outcome unknown | Application / provider | No | No | Yes |
| `AUTHORIZED` | Funds confirmed and held, not yet taken | Provider | Held, not captured | No | Yes |
| `CAPTURED` | Money taken | Provider (or app requesting capture) | **Yes** | No — refunds may follow | Yes |
| `FAILED` | The attempt did not succeed | Provider | No | Yes *for this attempt*; a new attempt may follow | Yes |
| `CANCELLED` | Abandoned before completion | Customer or application | No | Yes for this attempt | Yes |
| `REFUNDED` | The full captured amount returned | Application → provider | **Yes, reversed** | Yes | Yes |
| `PARTIALLY_REFUNDED` | Part returned; a balance remains captured | Application → provider | **Yes, partial** | No — further refunds possible | Yes |

Additional states to consider, depending on decisions:

| State | Needed if | Why |
|---|---|---|
| `AWAITING_COD_COLLECTION` | D3 = yes | COD has an order with no payment attempt until delivery |
| `COLLECTED_COD` | D3 = yes | Cash received by the courier; distinct from a gateway capture |
| `PARTIALLY_CAPTURED` | D5 = yes | A deposit taken with a balance outstanding |
| `EXPIRED` | D25 = yes | A pending attempt that timed out |
| `DISPUTED` / `CHARGEBACK` | D2 answered | Providers surface these; they change AR and revenue |

Principles proposed for review:

1. **A payment attempt is immutable once terminal.** A retry creates a new attempt, never mutates one.
2. **The gateway is authoritative** (subject to D18). A webhook may move a record from `FAILED` or
   `PENDING` to `CAPTURED`; the application's own optimism may not.
3. **Only `CAPTURED`, `COLLECTED_COD`, `REFUNDED` and `PARTIALLY_REFUNDED` post to the ledger.**
   Everything else is operational state.
4. **No state may be produced by an unauthenticated caller** — which D26 must fix first.

---

## 11. Failure scenario matrix

| # | Scenario | Current behaviour (verified) | Missing capability | Business decision required | Downstream affected |
|---|---|---|---|---|---|
| **A** | Checkout → payment succeeds → order created | **Cannot occur.** No payment step; the order is created regardless and stock deducted | Authorization/capture before or alongside order creation | D1, D8, D15: is the order created before or after money is confirmed? | Order, inventory, AR |
| **B** | Payment fails | Not reachable from real payment. `recordFailedOrder` writes a `PAYMENT_FAILED` order (no stock held) if the browser calls it | Real failure signal | D7: does the order survive for retry? Does stock release? | Order, inventory |
| **C** | Payment succeeds, app crashes before responding | **Money would be taken with no order.** Nothing reconciles a gateway capture against a missing order | Capture-first record + reconciliation sweep | D18, D19: is the webhook authoritative enough to create the order alone? | Payment, order, accounting |
| **D** | Customer retries after payment succeeded | **Order** idempotency (P0-3) returns the original order. **Payment** idempotency does not exist — a second charge would not be prevented | Payment-request idempotency | D17: key definition, distinct from `clientOrderReference` | Payment |
| **E** | Gateway sends the same webhook twice | No webhook endpoint exists | Idempotent callback handling | D23: dedupe on provider event id | Payment, accounting |
| **F** | Gateway reports success after app recorded failure/pending | No mechanism; `PAYMENT_FAILED` is terminal in the admin UI (no action offered) | Late-success reconciliation | D18, D34: may a late success revive an order? Is stock still there? | Order, inventory, accounting |
| **G** | Customer cancels after successful payment | `cancelOrder` sets `CANCELLED`, **releases stock**, leaves AR debited, requires no authentication | Refund on cancel; ledger reversal; authorization | D10, D11, D12, D26, D31 | Payment, inventory, accounting, security |
| **H** | Order cancelled after partial payment | Partial payment does not exist | Partial refund of a deposit | D5, D24: is the deposit refundable or forfeit? | Payment, AR |
| **I** | Order returned after successful payment | Return flow exists: status → `READY_TO_PICKUP` (**releases stock at request time**) → GST credit note issued. **No refund, no AR credit** | Refund execution | D11, D33: refund trigger — on pickup, on receipt, or on inspection? | Payment, accounting, GST |
| **J** | COD order reaches delivery | `DELIVERED` settable by anyone; sets `delivered_at`; **records no money** | COD collection event | D3, D16, D26 | COD, accounting, security |
| **K** | COD customer refuses delivery | No status for refusal; the nearest is `CANCELLED` (releases stock, no ledger effect) | Refusal state; return-to-origin; cost attribution | D3, D12: who bears the failed-delivery cost? | Order, inventory, accounting |
| **L** | Partial-payment order reaches delivery with a balance | Partial payment does not exist; the courier has no balance to collect | Balance tracking + collection at delivery | D5, D6, D16 | Payment, COD, AR |

---

## 12. Open questions

**For product / business — blocking**

1. Is Veloria intending to take money online at all, or is this catalogue-and-contact for now? *(D1)*
2. Is COD a genuine offer? If yes, who collects the cash and how does it reach the bank? *(D3, D16)*
3. Is the ₹50 COD fee real? If it is charged, is it a service fee with its own GST treatment, or a
   discount-adjusted line on the order? **It is currently shown to customers and never charged.** *(D4)*
4. Is partial payment a real offer, or aspirational UI? If real: is 50% fixed, and is the balance due
   at delivery or on a schedule? *(D5, D6)*
5. At what moment is an order "paid" for the purpose of shipping it? *(D8)*
6. At what moment is an order "cancelled", and by whom? *(D10, D12)*
7. What is the refund policy — window, method, partial allowed? *(D11)*
8. What should happen to the existing **₹19,796.33** of receivables that were never collectable? *(D14)*
9. If a payment succeeds but the customer never sees confirmation, is the order valid? *(Scenario C)*
10. If a customer refuses a COD delivery, who bears the cost? *(Scenario K)*

**For business + tax review**

11. GST treatment of a COD handling fee. *(D4, D21)*
12. Input-GST creditability on gateway commission. *(D21)*
13. When a refund is issued, is the original credit note sufficient, or is a further adjustment
    required? *(D11)*

**Vendor decisions**

14. Which provider, on what commercial terms, with what settlement cycle? *(D2)*
15. Hosted checkout vs in-page SDK — this determines whether Veloria is ever in PCI scope. *(D29, T4)*

**Requiring a decision regardless of the payment answer** *(these are defects today)*

16. `/api/master/sales-order/**` is unauthenticated. *(D26)*
17. `OUT_FOR_DELIVERY` releases stock. *(D27)*
18. Cancellation releases stock but not the receivable. *(D31)*
19. Card numbers and CVVs are collected in the browser and discarded. *(D29)*

---

## 13. Recommended implementation sequence — **after decisions are approved**

Each phase is gated on the decisions in brackets. **Nothing below is started.**

| Phase | Contents | Gated on |
|---|---|---|
| **P0-5** *Close the gaps that exist regardless* | Authorize `/sales-order/**`; move the order state machine into the backend with an explicit transition table; correct the stock-consuming status set; decide the cancellation ledger event | D26, D27, D31, D32 — *these need no payment answer and should not wait for one* |
| **P0-6** *Money model* | Paise-only calculation for any fee, split or balance; a single rounding rule; remove float rupee arithmetic from checkout; validate `currency` | D4, D6, D35 |
| **P0-7** *Payment records, no provider* | Payment attempt table, state machine, audit trail, payment idempotency key distinct from `clientOrderReference`; **COD only if approved** — it needs no gateway and exercises the whole model | D1, D3, D5, D16, D17 |
| **P0-8** *Accounting for collection* | Collection, refund and fee postings; AR clearing; decide the treatment of the legacy ₹19,796.33; real-time vs batch sale posting | D8, D13, D14, D20, D28 |
| **P0-9** *Gateway integration* | Provider integration, hosted/tokenised card capture, webhook endpoint with signature verification and idempotent handling, inventory policy for payment failure and timeout | D1, D2, D15, D18, D23, D25, D29 |
| **P0-10** *Refunds and reconciliation* | Refund execution, partial refunds, settlement-file reconciliation and its exception queue, the outstanding `REFUND_TO_CUSTOMER` obligations | D11, D19, D24, D33 |

**Recommended sequencing note.** P0-5 is worth starting first whatever the payment answer is: an
unauthenticated endpoint that can mark orders delivered, and a status that silently returns stock to
the shelf, are defects today — and both become money-handling defects the moment payment exists.

---

## Appendix — verification method

| Claim | How verified |
|---|---|
| No payment code in backend | Word-boundary `grep` over `src/main/java`; all 30 "payment" matches read and classified |
| No payment tables/columns | `information_schema` query on the live database |
| `paymentMethod` discarded | Runtime probe: order placed with payment-shaped fields; stored row inspected; order then deleted |
| Card/CVV never transmitted | Every use of `cardNumber`/`cardCvv`/`upiId` in `Payment.tsx` traced; none reaches `fetch` |
| AR never credited by collection | `grep` for `credit(RECEIVABLE`; `journal_entry_line` grouped by `source_type` |
| AR = ₹19,796.33 | `SELECT sum(debit_paise) - sum(credit_paise) … WHERE account_code='1100'` |
| Sales post via backfill only | All `postSale(` call sites enumerated |
| Status writers | All `setStatus(`/`.status("` sites enumerated and attributed to entities |
| `OUT_FOR_DELIVERY` releases stock | Consuming list read from `InventoryProductRepository`; live order `VO-20260824-D7ECB6D9` confirmed |
| `/sales-order/**` unauthenticated | Probe with no token against a **nonexistent** order code — service reached, nothing modified |

One order was created and deleted during verification. The database was returned to its prior state:
**7 orders, 0 with a client order reference** — identical to before.
