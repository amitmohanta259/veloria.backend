# Payment — Business Decision & Architecture Gate

**Design only. No code, schema, migration, trigger or test was changed.**

Everything in sections 1–9 is **repository evidence**. Everything from section 10 on is
**recommendation**, and is labelled as such. Where the repository cannot answer a
question, it says so rather than filling the gap.

---

## 1. Executive summary

Three findings decide the shape of this phase.

1. **No payment gateway exists anywhere in the repository.** Searched for Razorpay,
   Stripe, PayU, Cashfree, PhonePe, Paytm, Braintree, Adyen, webhooks, signature
   verification, payment intents and provider references. The only hits are a dead
   whitelisted path and decorative brand names (§5, §6).
2. **The payment method the shopper chooses has no backend effect whatsoever.** The UI
   sends `paymentMethod`; `PlaceOrderRequest` has no such field; Jackson drops it.
   CARD, UPI, COD and PARTIAL are, today, four labels that produce one identical order.
3. **No money is ever recorded as collected.** Every sale debits Accounts Receivable.
   The only credits to AR in the entire ledger come from *reversal* journals — not one
   collection event exists. AR stands at **₹19,796.33 outstanding** and can only grow.

A fourth finding constrains COD specifically: **no code path advances an order beyond
`ORDER_PLACED`**. `DELIVERED` and `OUT_FOR_DELIVERY` exist in the data and in read
queries, but nothing in the application writes them. COD collection depends on
delivery, so COD cannot be modelled end-to-end until fulfilment status is.

The gate cannot be cleared by engineering alone. Twenty business decisions are
registered in §25; the first — *do CARD and UPI move real money?* — determines
whether this is a two-week phase or a two-month one.

---

## 2. Current payment architecture

There is none. Stated precisely, as evidence:

| Component | Exists? |
|---|---|
| Payment gateway integration | **No** |
| Payment / payment_transaction / refund table | **No** (only `gst_payment` = GST remittance to government, `salary_payment` = payroll) |
| Payment column on `customer_order` | **No** — searched for `%pay%`, `%paid%`, `%settle%`, `%outstand%`: none |
| `paymentMethod` in any backend Java file | **No** — zero occurrences |
| Webhook endpoint | **No** |
| Provider reference / transaction id | **No** |
| Signature verification | **No** |
| Idempotency key on the order API | **No** |
| Refund model | **No** |
| Settlement / payout model | **No** (and no sellers exist) |

### The two apparent hits, run down

- **Stripe** — `WebSecurityConfig` whitelists `/api/master/stripe/public-key` in its
  `ignoring()` list. There is **no controller, no service and no pom dependency**. It
  sits beside `/data-import/download/drug`, `/data-import/download/clinician`,
  `/patient/add-card` and `/auth/patient` — leftovers from a healthcare template.
  **Dead configuration, not an integration.**
- **PhonePe / Paytm / BHIM / GPay** — display text in `Payment.tsx`:
  `{["G Pay", "PhonePe", "Paytm", "BHIM"].map(app => …)}` renders brand chips, and a
  sentence reads "Pay instantly using any UPI app". **Decorative labels, no SDK.**

---

## 3. Current checkout payment behaviour

```
Payment.tsx
  selectedMethod: "card" | "upi" | "cod" | "partial"     (default "card")
  collects: card fields, upiId, partialMethod, partialUpiId
  computes: partialAmount = grandTotalRupees / 2
            dueNow       = partial ? partialAmount
                         : cod     ? grandTotalRupees + 50
                         : grandTotalRupees
        ↓  POST /client/order/place
  { deliveryLocation, currency, items[], paymentMethod: "CARD"|"UPI"|"COD"|"PARTIAL" }
        ↓
PlaceOrderRequest { deliveryLocation, currency, items[] }   ← no paymentMethod field
        ↓  Jackson drops the unknown property (Spring Boot default)
ClientOrderServiceImpl.placeOrder()
        ↓
customer_order.status = "ORDER_PLACED"    ← identical for all four methods
        ↓
sales_invoice   — created only by a manual admin action (POST /sales-invoices/{id}/issue)
        ↓
journal entries — created only by a manual admin action (POST /accounting/backfill)
```

### What each method actually does today

| Method | UI collects | Sent | Backend effect | Money moved |
|---|---|---|---|---|
| CARD | card number, expiry, CVV fields | method name only, then discarded | **none** | **none** |
| UPI | `upiId`, validated only as `includes("@")` | discarded | **none** | **none** |
| COD | nothing extra; adds ₹50 on screen | discarded | **none** | **none** |
| PARTIAL | sub-method + UPI id; shows 50% | discarded | **none** | **none** |

All four produce the same `ORDER_PLACED` order. The card fields are collected by the
browser and **never transmitted** — which is fortunate, because there is no compliant
place to put them (§20).

---

## 4. Payment gaps

1. No record of *how* a customer paid, or *whether* they did.
2. No payment state, so no notion of pending / captured / failed.
3. No idempotency — a retried checkout creates a second order **and now consumes
   stock a second time** (inventory deduction landed in P0-2).
4. No refund model, so a return cannot return money.
5. No collection event, so AR never clears (§18).
6. Amounts shown to the customer (COD fee, partial split) exist **only in the
   browser**, in floating-point rupees, and are never sent, stored or invoiced.
7. No fulfilment status transitions, so "delivered" — the trigger for COD collection —
   is not an event the system can observe.

---

## 5. CARD analysis

**Evidence.** A card form exists in `Payment.tsx`. No card data is sent. No gateway,
no tokenisation, no PCI-scoped handling anywhere in the repository.

**Therefore:** CARD is currently **Option A — a payment-method label only.** Choosing
it is indistinguishable from choosing COD.

**Cannot be answered from the repository:** whether it is *intended* to process real
money, and through which provider. Decisions D1 and D3.

---

## 6. UPI analysis

**Evidence.** `upiId` is captured and validated with `upiId.includes("@")` — a
substring check, not a VPA format check. Not transmitted. No collect-request, no
intent URL, no deep link, no provider.

**Therefore:** UPI is also **Option A — a label only.** The brand chips imply an
integration that does not exist.

**Risk worth naming:** the screen currently tells a customer "Pay instantly using any
UPI app", takes their VPA, and places an order having moved no money. That is a
customer-trust and potentially a consumer-protection problem, independent of the
engineering.

---

## 7. COD analysis

**Evidence.** COD sets a label, adds ₹50 on screen, and places an identical order.
There is no COD flag, no courier model, no remittance model, no delivery event.

**The blocking dependency:** the code writes only `ORDER_PLACED`, `PAYMENT_FAILED`,
`IN_PROGRESS` (sales_order) and `READY_TO_PICKUP` (return requested). The fulfilment
vocabulary the client app renders — `PACKED`, `IN_TRANSIT`, `OUT_FOR_DELIVERY`,
`DELIVERED` — appears in read queries and in existing data, but **no application code
path sets it**. Those rows were set outside the application.

COD collection happens *at delivery*. Until delivery is a state the application
records, COD cannot be modelled beyond "order exists". This is decision D4 plus a
prerequisite (fulfilment status) that belongs to a different phase.

---

## 8. Partial-payment analysis

**Evidence, exactly:**

```js
const partialAmount = grandTotalRupees / 2;                    // Payment.tsx:226
const dueNow = selectedMethod === "partial" ? partialAmount : …
```

- The 50 % is a **hard-coded literal division in the browser**. Not configurable, not
  fetched, not sent.
- The remaining half is represented **nowhere** — not as AR, not as COD, not at all.
- There is no partial-payment state, no due date, no collection path.

**Financially authoritative: NO.**

Nothing in the repository establishes that 50 % is the business rule; it establishes
only that the browser currently draws it. Decisions D7, D8, D9.

---

## 9. COD fee analysis

**Evidence, exactly:**

```js
: selectedMethod === "cod" ? grandTotalRupees + 50              // Payment.tsx:228
```

- ₹50 is a **hard-coded literal added in the browser**.
- It is **not** sent to the server, **not** stored, **not** on the invoice, **not** in
  the GST computation, **not** in any account.
- It is computed in **floating-point rupees**, against a backend that stores all 188
  monetary columns as integer paise (§21).

**Financially authoritative: NO.**

The customer is shown a total the business has no record of. Whether ₹50 is a real
charge, whether it is taxable, and whether it is revenue or a courier-cost recovery
are decisions D5 and D6 — and they have a GST consequence, because a taxable fee
changes the invoice.

---

## 10. Order state machine *(recommendation)*

Order status and payment status must be separate. Today they are conflated —
`PAYMENT_FAILED` is an *order* status, which is why a failed checkout leaves an order
row behind.

```
                 ┌──────────────┐
                 │ ORDER_PLACED │ ◄── exists today
                 └──────┬───────┘
          cancel        │        fulfilment
      ┌─────────────────┼─────────────────┐
      ▼                 ▼                 │
 ┌───────────┐    ┌──────────┐            │
 │ CANCELLED │    │  PACKED  │            │   ← none of these are written
 └───────────┘    └────┬─────┘            │      by code today
                       ▼                  │
                 ┌────────────┐           │
                 │ IN_TRANSIT │           │
                 └────┬───────┘           │
                      ▼                   │
            ┌──────────────────┐          │
            │ OUT_FOR_DELIVERY │          │
            └────┬─────────────┘          │
                 ▼                        ▼
           ┌───────────┐          ┌──────────────┐
           │ DELIVERED │ ───────► │ RETURN flow  │ (READY_TO_PICKUP … RETURNED)
           └───────────┘          └──────────────┘
```

`PAYMENT_FAILED` should **not** be an order status. A checkout whose payment fails
should leave either no order, or an order in a payment-pending state — decided by §17.

| State | Meaning | Actor | DB effect | Accounting | Inventory |
|---|---|---|---|---|---|
| `ORDER_PLACED` | accepted, stock claimed | customer | order + items | none today | consumed |
| `PACKED` → `DELIVERED` | fulfilment | ops | status only | none | none |
| `CANCELLED` | withdrawn before dispatch | customer/ops | status | reversal if posted | **must restore** — see §17 |
| return states | existing flow | customer/ops | return tables | credit note if raised | restores on `PRODUCT_OK` |

---

## 11. Payment state machine *(recommendation)*

Two tracks, because the repository supports two realities.

**Track 1 — no gateway (today's truth, and COD forever):**

```
AWAITING_COLLECTION ──► COLLECTED ──► SETTLED
         └──► CANCELLED
```

**Track 2 — prepaid, only if D1/D2 say CARD/UPI are real:**

```
INITIATED ─► PENDING ─► AUTHORIZED ─► CAPTURED ─► SETTLED
    │           │            │            │
    ▼           ▼            ▼            ▼
  FAILED      FAILED      VOIDED    REFUND_PENDING ─► REFUNDED
                                                  └─► REFUND_FAILED
```

States I recommend **omitting** unless a provider requires them: `PARTIALLY_REFUNDED`
(derivable as Σ refunds < Σ captured — a computed fact, not a state),
`AUTHORIZED`/`VOIDED` (only if the chosen provider separates auth from capture; many
Indian UPI flows do not).

| State | Meaning | Allowed → | Forbidden | DB | Accounting | Inventory |
|---|---|---|---|---|---|---|
| `INITIATED` | intent created | PENDING, FAILED | anything else | insert txn | none | already claimed |
| `PENDING` | at provider, outcome unknown | AUTHORIZED, CAPTURED, FAILED | SETTLED | append | none | held |
| `CAPTURED` | money taken | SETTLED, REFUND_PENDING | INITIATED, PENDING | append | **Dr clearing, Cr AR** | committed |
| `SETTLED` | funds in bank | REFUND_PENDING | any backwards | append | Dr bank, Cr clearing | — |
| `FAILED` | terminal failure | *(none)* | SETTLED, CAPTURED | append | none | **release** |
| `AWAITING_COLLECTION` | COD, not yet collected | COLLECTED, CANCELLED | SETTLED | insert | none | committed |
| `COLLECTED` | courier holds cash | SETTLED | CANCELLED | append | Dr COD receivable, Cr AR | — |
| `REFUNDED` | money returned | *(none)* | CAPTURED | append | see §19 | see §17 |

**Invalid transitions to reject explicitly:** `REFUNDED → CAPTURED`,
`FAILED → SETTLED`, `CANCELLED → COLLECTED`, any exit from a terminal state, and any
refund whose cumulative amount exceeds Σ captured.

Enforced in the **application service** — the legal set depends on method, actor and
arithmetic across rows, which a CHECK constraint cannot express and a trigger should
not (consistent with the P0 trigger decisions).

---

## 12. Refund state machine *(recommendation)*

```
REFUND_PENDING ─► REFUND_PROCESSING ─► REFUNDED
                          └──────────► REFUND_FAILED ─► (retry, same key)
```

**Model: Option A — a transaction against the original payment**, i.e. a row in
`payment_transaction` with `direction = OUT`, `type = REFUND`, and
`related_transaction_id` pointing at the capture.

Re-evaluated against the repository, as §9 of the brief requires, and the evidence
*supports* the earlier lean rather than contradicting it:

- The codebase already models corrections as **append-not-mutate** — `JournalService`
  reverses rather than deletes; `gst_movement_ledger` supersedes rather than updates.
  A refund-as-transaction matches the house style.
- A separate `refund` table would duplicate the state machine and give "how much has
  been refunded" two sources that can disagree.

Partial refunds fall out naturally: several OUT rows against one capture, with the
service enforcing Σ OUT ≤ Σ IN.

---

## 13. Idempotency architecture *(recommendation)*

**Order idempotency and payment idempotency are different problems**, and the
repository shows why: inventory is consumed by order creation, so a duplicate *order*
is now an inventory bug, whereas a duplicate *payment* is a money bug.

| | Order | Payment |
|---|---|---|
| Protects against | double order, **double stock consumption** | double charge |
| Key | `client_order_reference` generated by the browser per checkout attempt | provider's `provider_reference`, plus our own key on the intent |
| Generated by | client, once per checkout screen; **reused on retry** | client for the intent; provider for the callback |
| Stored on | `customer_order` | `payment_transaction` |
| Uniqueness scope | per customer | global |
| Validity | the checkout attempt; a new cart/screen means a new key | life of the transaction |
| Retry with same key | return the **existing order**, HTTP 200, no new stock consumed | return the existing transaction |
| Same key, different body | reject — `409`; never silently serve the first result for a different cart | reject |

One checkout → one order → one payment intent → **possibly many payment attempts**.
That is the distinction §11 of the brief asks about, and yes, this architecture needs
it: a customer whose card is declined should retry *the same order*, not create a new one.

Enforced by a **unique index**, not `SELECT`-then-`INSERT`, which two concurrent
requests would both pass.

---

## 14. Payment provider architecture *(recommendation, conditional)*

**Conditional on D1/D2/D3.** No provider is present, and I am not choosing one — that
is a commercial decision (fees, settlement cycle, UPI support, refund API, region).

Provider-agnostic shape:

```
PaymentService (ours)
   ├── PaymentProvider (interface)  ← createIntent, capture, refund, fetchStatus
   └── <Provider>Adapter            ← the only class that knows the vendor
```

Everything vendor-specific — signature algorithm, payload shape, status vocabulary —
lives behind that interface, so the domain, the state machine and the ledger never
mention the vendor. That matters because switching provider in Indian e-commerce is
common.

---

## 15. Webhook architecture *(recommendation, conditional)*

```
Provider ─► POST /api/master/client/payments/webhook
              1. verify signature       ← reject before any parsing
              2. de-duplicate on provider_reference (unique index)
              3. BEGIN
                   append payment_transaction
                   advance order state
                   post the collection journal
                 COMMIT
              4. 200 OK
```

**A duplicate webhook must be a no-op**, guaranteed by the unique index on
`provider_reference` — not by an existence check. Providers retry aggressively and
deliver out of order; assume both.

The endpoint must be **outside** the session-authenticated chain (the provider has no
session) and authenticated **solely by signature**. It must never trust amounts or
status from the payload without re-reading our own record.

---

## 16. Failure / retry architecture *(recommendation)*

The dangerous case is §13 of the brief: **provider captured, application timed out**.

```
TX-1  create order + intent, COMMIT          ← never hold a DB transaction across an HTTP call
        ↓
      call provider (outside any transaction)
        ↓ timeout — outcome genuinely unknown
      show "payment processing", NOT "failed"
        ↓
      webhook arrives → reconcile
      or a sweeper polls fetchStatus for PENDING intents older than N minutes
```

Three mechanisms, all required: **webhook** (fast path), **status inquiry sweeper**
(when the webhook never arrives), **daily reconciliation** against the provider's
settlement report (the backstop that catches what both missed). Never auto-retry the
*charge* on timeout — inquire first.

---

## 17. Inventory interaction *(recommendation — the P0-2 implementation is not changed)*

**Today, from the code:** stock is derived (`initial_stock − sold + returned`), the
order-item insert *is* the deduction, and `reserveInventory` locks the product row and
checks availability inside the existing order transaction. Concurrency-safe and
proven. **Not modified by this task.**

**When is inventory committed today?** At `COMMIT` of `placeOrder` — before any money
moves, because no money ever moves.

**What happens if payment does not complete?** Today the question cannot arise. Once a
gateway exists it becomes the central question, and the four models trade off:

| | A: deduct after payment | B: deduct now, restore on failure | C: reserve, commit on capture |
|---|---|---|---|
| Oversell risk | **High** — nothing holds stock during payment | None | None |
| Implementation | smallest | small — **reuses P0-2 as-is** | largest — needs `reserved_quantity` + expiry sweeper |
| Customer experience | pays, then told "sold out" — worst outcome | good | good |
| Scarcity | breaks under contention | fine | best |
| Cancellation | n/a | restore = cancel the order (derived stock returns automatically) | release |
| Fit with derived stock | poor | **natural** — cancelling the order un-consumes the stock by construction | needs a real counter, i.e. a model change |

**Recommendation: Model B now, Model C only if abandonment proves costly.**

Reasoning specific to this codebase: because stock is *derived from order rows*,
"restore inventory" is not a compensating write — it is a status change. An order moved
to `CANCELLED` (a status outside the sold set) releases its stock automatically, with
no second ledger to keep in step. Model C would require introducing
`reserved_quantity` and a real counter, which is exactly the speculative schema the P0
design deferred. Model B gets correctness for the price of a status transition.

The cost of Model B is honest: stock is held by unpaid orders until they are
cancelled, so an abandonment sweeper is required, and its timeout (D-new) is a business
decision.

---

## 18. Accounting interaction *(recommendation — existing posting unchanged)*

The existing sale entry is **correct and must not change**:

```
Dr 1100 Accounts Receivable
   Cr 4010 Sales
   Cr 2100/2110/2120 Output GST
```

What is missing is the *second* event. Evidence: AR has 49 lines, Dr ₹79,185.23 /
Cr ₹59,388.90, and **every credit comes from a REVERSAL journal** — not one collection.

Proposed additions, all **requiring accounting-owner confirmation**:

| Event | Entry | Status |
|---|---|---|
| Prepaid captured | Dr *Payment Gateway Clearing* · Cr 1100 AR | new account — **confirm** |
| Provider settles | Dr 1020 Bank · Cr *Clearing* | **confirm** |
| COD collected by courier | Dr *COD Receivable* · Cr 1100 AR | new account — **confirm** |
| COD remitted | Dr 1020 Bank · Cr *COD Receivable* | **confirm** |
| Partial payment | Dr clearing for the paid part; AR balance **is** the outstanding | follows |
| Refund | Dr Sales (or *Sales Returns*) · Dr Output GST reversal · Cr Bank/Clearing — **appended, never a mutation** | which account — **confirm** |
| COD fee ₹50 | Cr ? | revenue or cost recovery — **confirm (D6)** |

A clearing account is the standard representation of "customer has paid, money is not
in our bank yet". It changes the chart of accounts, so it is flagged, not assumed.

**Separate decision:** whether checkout should post to the ledger automatically at all.
It currently does not (manual backfill). Automatic is more correct but couples a
customer order to a ledger failure. **Recommendation: post via a transactional outbox**
— the order commits, the journal follows reliably, neither blocks the other.

---

## 19. GST interaction *(recommendation — GST is not changed)*

GST is the best-built part of this system and is **not redesigned**.

The governing rule: **GST liability arises on supply, not on collection.** Therefore:

| Event | GST effect |
|---|---|
| Sale / invoice | output tax recorded — **exists today, unchanged** |
| Payment (any method) | **none** |
| Payment failure | none |
| Cancellation **before** supply | no GST document; supersede the `gst_output_tax` row (existing pattern), never delete |
| Return with `gstAdjustmentRequired` | credit note via the existing `GstCreditNoteService` |
| Refund | **follows the credit note, not the payment.** A refund without a credit note is a cash movement with no tax consequence |

Historical GST is **never** recalculated from current `gst_tax_rules`; the snapshot on
`customer_order_item` and `sales_invoice_item` stays immutable. **No GST trigger.**

**Financial vs operational events** (§17 of the brief): *financial* = sale, payment
capture, settlement, refund, credit note. *Operational* = packed, in transit,
delivered, return picked up, inspection. Only financial events touch the ledger.

---

## 20. Security requirements *(recommendation)*

- **Never store** PAN, CVV, expiry, UPI PIN, or any raw instrument data. The current
  card form collects but does not transmit; if a gateway is added, the fields must be
  replaced by the provider's hosted/tokenised element so the data never reaches our
  servers. Storing them would pull the whole application into PCI-DSS scope.
- Store only **provider tokens and references**.
- Webhook: verify signature **before parsing**; constant-time comparison; reject
  unsigned requests; replay protection via timestamp window **and** the unique
  `provider_reference`.
- Provider secrets via environment variables only — consistent with the P0 credential
  remediation already applied to this repository.
- Refund initiation must be **admin-authorised and separated from the requester**; the
  current `ADMIN_GST` authority is a poor semantic fit and the module-authority gap
  noted in the P0-1 report should be resolved first.
- Payment endpoints must be owner-scoped, exactly as the order read now is.
- Never log tokens, signatures, or instrument data.

---

## 21. Money and rounding model *(preserve what exists)*

The repository is already consistent: **188 monetary columns, all `bigint` paise**,
rates in basis points, no float or numeric mixing. This is preserved unchanged.

Consequences for payment:

- All payment amounts in **integer paise**.
- The COD fee and partial split must move **server-side into paise**. Both are
  currently floating-point rupee arithmetic in the browser
  (`grandTotalRupees / 2`, `+ 50`), which is exactly what the rest of the system
  avoids.
- Partial split must round so the parts **sum exactly** to the total — define which
  part absorbs the remainder.
- Refund arithmetic in paise; Σ refunds ≤ Σ captured enforced in the service.
- Currency: `INR` only today (`customer_order.currency` defaults to it). Multi-currency
  is not in evidence and should not be designed for speculatively.

---

## 22. Database design *(design only — nothing created)*

### `payment_transaction` — required

| | |
|---|---|
| Purpose | Immutable record of every money movement, in either direction |
| PK | `id bigserial`; `uuid` for external reference |
| FK | `order_code` → `customer_order.order_code`; `related_transaction_id` → self (refund → capture) |
| Columns | `direction` (IN/OUT), `type` (PAYMENT_RECEIVED/REFUND), `method` (CARD/UPI/COD/PARTIAL), `amount_paise bigint`, `status`, `provider`, `provider_reference`, `failure_reason`, `created_at`, `created_by` |
| Unique | `idempotency_key` (NOT NULL); `provider_reference` (nullable, unique where present) |
| Indexes | `(order_code)`; `(status, created_at)` for the sweeper |
| State | `status` per §11 |
| Audit | append-only; a status change inserts a row, never updates one |
| Idempotency | `idempotency_key`, `provider_reference` |

### `customer_order` — one added column

`client_order_reference` (unique per customer) for order idempotency (§13). This is a
*column*, not a table, and is the smallest change that stops a retried checkout from
consuming stock twice.

### Deliberately **not** proposed

`refund` (§12 — it is a transaction), `payment_attempt` (an attempt *is* a transaction
row in `FAILED`), `settlement` / `payout` (**no sellers exist** — designing them would
be inventing requirements), `reserved_quantity` (§17 — only under Model C).

### Chart of accounts

`Payment Gateway Clearing`, `COD Receivable`, possibly `Sales Returns` — **pending
§18 confirmation**, added as seed rows, not schema.

---

## 23. Architecture alternatives

### Option 1 — "Pay later" only (formalise today's reality)

Keep every method as a *declared intention*; record it; collect offline; clear AR
manually.

- **Advantages:** smallest change; no PCI scope; no webhook, no timeout class of bug; unblocks the books immediately.
- **Disadvantages:** no online money; CARD/UPI remain misleading to customers.
- **Concurrency:** unchanged — P0-2 stands as-is.
- **Inventory:** committed at order, as today.
- **Accounting:** AR cleared by an operator-recorded collection.
- **GST:** unchanged.
- **Complexity:** low. **Recovery:** trivial — nothing asynchronous.

### Option 2 — Prepaid gateway, deduct-then-release (Model B)

Real CARD/UPI; order and stock committed at placement; failed/abandoned payment
cancels the order and releases stock.

- **Advantages:** no oversell; reuses P0-2 unchanged; simpler than reservation; matches the derived-stock model naturally.
- **Disadvantages:** unpaid orders hold stock until swept; needs webhook + sweeper + reconciliation.
- **Concurrency:** unchanged.
- **Inventory:** released by a status transition, not a compensating write.
- **Accounting:** clearing account required.
- **GST:** unchanged — but cancellation must supersede the output-tax row.
- **Complexity:** medium. **Recovery:** webhook → sweeper → daily reconciliation.

### Option 3 — Reservation with expiry (Model C)

Stock reserved at checkout, committed on capture, released on expiry.

- **Advantages:** best under scarcity; cleanest customer experience; the classic model.
- **Disadvantages:** **requires changing the stock model** — a real `reserved_quantity` counter, which the current derived model does not have; largest change; most new failure modes (orphan reservations, sweeper correctness).
- **Concurrency:** would replace the P0-2 approach.
- **Complexity:** high. **Recovery:** hardest.

---

## 24. Recommended target architecture

**If D1/D2 = "no real money for now" → Option 1.**
**If D1/D2 = "yes" → Option 2** (and revisit Option 3 only if abandonment measurably
costs sales).

```
Customer
   │
   ▼
Checkout ── client_order_reference (idempotency) ──────────┐
   │                                                       │
   ▼                                                       │
TX-1  BEGIN                                                │
        reserveInventory (P0-2, unchanged)                 │
        create order  ORDER_PLACED / PENDING_PAYMENT       │
        create payment_transaction  INITIATED              │
        outbox: ORDER_PLACED                               │
      COMMIT                                               │
   │                                                       │
   ├── COD ──► AWAITING_COLLECTION ──► (delivery) ──► COLLECTED ──► SETTLED
   │
   └── prepaid ──► Payment Provider (outside any transaction)
                        │
                        ▼
                   Webhook ── signature ── de-dupe on provider_reference
                        │
                   TX-2  BEGIN
                           append payment_transaction CAPTURED
                           order → CONFIRMED
                           journal: Dr Clearing, Cr AR      (via outbox)
                         COMMIT
                        │
              failure/timeout ──► sweeper: fetchStatus
                                   └─ still failed ──► order CANCELLED
                                                       stock released by status
```

Inventory sits **inside TX-1**, exactly where P0-2 put it. Nothing about the current
inventory implementation changes.

---

## 25. Business decision register

| ID | Decision | Current evidence | Options | Recommended direction | Owner |
|---|---|---|---|---|---|
| D1 | Are CARD payments real? | Form exists; nothing sent; no provider | label / real | **Decide first — everything depends on it** | Business |
| D2 | Are UPI payments real? | VPA captured, `includes("@")`, not sent | label / real | As D1 | Business |
| D3 | Which gateway? | none present | Razorpay / PayU / Cashfree / other | Not an engineering choice — fees, settlement, UPI, refunds | Business + Finance |
| D4 | Is COD supported? | label only; no delivery event | yes / no | Yes, but blocked on fulfilment status | Business + Ops |
| D5 | Is the ₹50 COD fee real? | browser-only float | real / display artefact | Must move server-side or be removed | Business |
| D6 | Is that fee taxable? | not in GST at all | taxable / exempt / not a fee | Changes the invoice — needs a tax view | Finance + Tax |
| D7 | Is partial payment supported? | browser-only 50 % | yes / no | Decide before modelling outstanding balances | Business |
| D8 | Always 50 %? | hard-coded `/ 2` | fixed / configurable / per-category | Not evidenced as a rule | Business |
| D9 | How is the outstanding collected? | no representation | COD on delivery / link / AR invoice | Required if D7 = yes | Business + Finance |
| D10 | When is inventory committed? | at order (P0-2) | at order / at capture / reservation | **Model B** (§17) | Product + Eng |
| D11 | After payment failure? | n/a | cancel / retain for retry / retry window | Cancel after a defined window | Product |
| D12 | After payment timeout? | n/a | inquire / assume failed | **Inquire, never assume** | Eng + Business |
| D13 | Gateway captured, app timed out? | n/a | webhook + sweeper + reconciliation | All three | Eng |
| D14 | Cancellation policy? | `CANCELLED` only on `sales_order` | until dispatch / until delivery | Until dispatch | Business |
| D15 | Refund policy? | none | full / partial / window | Needed before refunds | Business |
| D16 | Full vs partial refunds? | none | both / full only | Both; model supports it (§12) | Business |
| D17 | When does AR clear? | **never — only reversals credit it** | capture / settlement / delivery | At capture (prepaid), at remittance (COD) | Finance |
| D18 | How are COD settlements represented? | not represented | COD receivable / direct to bank | Clearing account | Finance |
| D19 | How are webhooks reconciled? | none | webhook only / + daily report | Both | Eng + Finance |
| D20 | Order idempotency policy? | **none — retry duplicates the order and the stock consumption** | client key / server token | `client_order_reference`, unique per customer | Product + Eng |
| D21 *(new)* | Abandoned-order timeout? | n/a | 15 min / 30 min / 24 h | Required by Model B | Business |
| D22 *(new)* | Should checkout post to the ledger automatically? | manual backfill only | auto / manual / outbox | **Outbox** | Finance + Eng |

---

## 26. Open questions

Beyond the register: does the business want a **payment-pending order state** visible
to customers? Who is accountable for the **daily reconciliation** report? Is there an
existing merchant account, or is onboarding part of this phase? Does the ₹50 fee apply
per order or per shipment? And — the one with a deadline attached — **what is the
remediation plan for orders already placed under CARD/UPI where no money was taken?**
₹19,796.33 of AR is outstanding today.

---

## 27. Implementation phases

| Phase | Content | Depends on |
|---|---|---|
| **0** | Resolve D1, D2, D5, D7 | — *(gate: nothing else may start)* |
| **1** | Order idempotency (`client_order_reference`) | none — **safe to do immediately**, and it closes the live duplicate-stock risk |
| **2** | Payment domain model + state machine, COD/"pay later" track only | D1, D4, D5, D7 |
| **3** | Server-side money: COD fee and partial split in paise, on the invoice | D5, D6, D8 |
| **4** | Accounting collection event + clearing accounts | D17, D18, D22, phase 2 |
| **5** | Provider integration (adapter + intent) | D3, phase 2 |
| **6** | Webhook, sweeper, reconciliation | phase 5, D12, D13, D19 |
| **7** | Refunds | phase 5, D15, D16 |
| **8** | Returns ↔ refunds ↔ credit notes | phase 7 |
| **9** | Fulfilment status transitions *(prerequisite for real COD)* | D4 |

Phase 1 is deliberately first: it needs **no business decision**, and it fixes a real
defect that P0-2 made worse (a retried checkout now consumes stock twice).

---

## 28. Test strategy *(design only)*

**Unit** — every legal and illegal state transition; refund arithmetic (Σ OUT ≤ Σ IN);
partial-split rounding summing exactly to the total; idempotency-key equality and
conflict.

**Integration (real PostgreSQL, per the established pattern)** — unique-constraint
behaviour under concurrent identical keys; webhook applied twice is a no-op; the
collection journal balances; rollback when any step fails; failure injection after
capture.

**Cucumber/JUnit (business flows)** — successful prepaid order; failed payment;
COD order; partial payment; duplicate checkout request; duplicate webhook; refund;
cancellation before and after payment.

**Playwright** — method selection; checkout; success, failure and pending results;
confirmation; retry after failure. Concurrency stays in the integration layer, per the
existing convention.

**Non-negotiable:** every concurrency test must be shown to fail against the broken
implementation before it is trusted — the practice already used for `addStock` and
checkout inventory.

---

## 29. Migration strategy *(design only)*

Additive and backward-compatible, in this order: `payment_transaction` (new table,
nothing reads it yet) → `customer_order.client_order_reference` (nullable, then unique
once backfilled) → chart-of-accounts seed rows. Each deployable before the code that
uses it. Historical orders get **no** synthetic payment rows — fabricating collections
that never happened would corrupt the books; they stay as outstanding AR until the
business decides their treatment (§26).

## 30. Rollback strategy *(design only)*

New tables roll back by being ignored; dropping them loses payment history, so prefer
forward fixes. The unique constraint on `client_order_reference` must be preceded by a
duplicate check. Gateway integration ships behind a flag so it can be disabled without
redeploy, falling back to "pay later". **Never** roll back by deleting payment or
journal rows — reverse them, consistent with the existing accounting rules.

---

# A. Confirmed facts

Demonstrated by the repository, not inferred:

1. **No payment gateway exists.** No provider SDK, dependency, adapter, webhook,
   signature verification, payment intent or provider reference.
2. The **Stripe** hit is a whitelisted path `/api/master/stripe/public-key` with **no
   controller, no service, no dependency** — dead template configuration.
3. **PhonePe / Paytm / BHIM / GPay** appear only as **display strings** in `Payment.tsx`.
4. The UI sends `paymentMethod`; `PlaceOrderRequest` has **no such field**; the value
   is silently dropped. **Zero occurrences of `paymentMethod` in backend Java.**
5. **CARD, UPI, COD and PARTIAL produce an identical `ORDER_PLACED` order.**
6. Card details are collected in the browser and **never transmitted**.
7. The **₹50 COD fee** is `grandTotalRupees + 50`, browser-only, floating-point,
   never sent or stored. **Financially authoritative: NO.**
8. The **50 % partial split** is `grandTotalRupees / 2`, browser-only, floating-point,
   never sent or stored. **Financially authoritative: NO.**
9. There is **no payment, payment_transaction, refund or settlement table**, and **no
   payment column** on `customer_order`.
10. Sales post **Dr AR / Cr Sales / Cr Output GST**, and **every AR credit in the
    ledger comes from a REVERSAL journal**. No collection event exists. Outstanding AR
    = **₹19,796.33**; Cash (1010) has **0** lines; Bank (1020) has **1** (the GST
    remittance).
11. Invoices and journals are produced **only by manual admin actions**, not by checkout.
12. **No code path advances an order past `ORDER_PLACED`.** The code writes only
    `ORDER_PLACED`, `PAYMENT_FAILED`, `IN_PROGRESS`, `READY_TO_PICKUP`. `DELIVERED` and
    `OUT_FOR_DELIVERY` exist in data and read queries but are never written by the app.
13. **No idempotency** on the order API; a retried checkout creates a second order and
    — since P0-2 — consumes stock a second time.
14. Money is **integer paise** across 188 columns; the browser's payment arithmetic is
    floating-point rupees, inconsistent with it.

# B. Recommended architecture

Not existing functionality — proposals:

- Separate **order**, **payment** and **refund** state machines (§10–§12).
- **`payment_transaction`** as the single append-only money-movement record;
  **refunds as OUT transactions**, not a separate table; **no settlement/payout tables**
  (no sellers exist).
- **Order idempotency via `client_order_reference`**, distinct from payment
  idempotency — and implementable **now**, without any business decision.
- **Model B** for inventory: keep P0-2 unchanged; release stock by cancelling the
  order, which works naturally because stock is derived from order rows.
- **Collection event** added to accounting; the existing sale entry untouched;
  clearing accounts pending confirmation; posting via **outbox**.
- **GST unchanged** — liability follows supply, not collection; refunds follow credit
  notes, not payments.
- Provider behind an **adapter interface**; webhook authenticated by signature and
  de-duplicated by a unique index; **webhook + sweeper + daily reconciliation**.
- **No card data stored, ever.**

# C. Business decisions required

Cannot be answered from the repository. Full register in §25; the blocking ones:

1. **D1/D2 — do CARD and UPI move real money?** Everything downstream depends on this.
2. **D3 — which provider?** Commercial, not engineering.
3. **D5/D6 — is the ₹50 COD fee real, and is it taxable?** It is currently fiction.
4. **D7/D8 — is partial payment real, and is 50 % the rule?** Also currently fiction.
5. **D17 — when does AR clear?** The books cannot be right until this is answered.
6. **D4 — is COD supported?** Blocked on fulfilment status existing at all.
7. **§26 — what happens to the ₹19,796.33 already outstanding** from orders placed
   under CARD/UPI where no money was taken?

**Nothing in this document has been implemented.**
