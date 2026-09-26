# P0-7 — Razorpay payment integration: implementation report

**Status: SANDBOX-READY, NOT PRODUCTION-READY.** No live key can even be loaded — the application
refuses to start with one. See §Q.

---

## A. Architecture

Payment is a domain of its own, deliberately not an extension of the order.

```text
Customer ── checkout ──► POST /order/place        (P0-3 idempotent, unchanged)
                            │
                            ▼  orderCode
                         POST /payment/initiate    server computes the amount
                            │
              ┌─────────────┴─────────────┐
              ▼                           ▼
        gateway = COD               gateway = RAZORPAY
        no Razorpay call            Razorpay Orders API
        amount due at the door      → razorpay_order_id
                                          │
                                          ▼
                                 Razorpay Standard Checkout
                                 (card/UPI entered in Razorpay's window)
                                          │
                        ┌─────────────────┴─────────────────┐
                        ▼                                   ▼
              POST /payment/verify                 POST /payment/webhook
              signature + re-read from             signature + event-id dedupe
              Razorpay                                      │
                        └─────────────► applyGatewayTruth ◄─┘
                                        row lock, one outcome
                                              │
                        ┌─────────────────────┴───────────────────┐
                        ▼                                         ▼
                   CAPTURED                                    FAILED
              order eligible for fulfilment            order CANCELLED (SYSTEM)
                                                       inventory released once
                                                       no SALE, no collection
```

Three properties carry the design:

1. **The server owns the amount.** `OrderInvoice` derives it from the order's stored figures.
   Sending `amount` in the request body is refused outright, not ignored.
2. **A signature is evidence, not proof of payment.** It proves the message came from Razorpay.
   What the payment *is* — status, amount, currency, which order — is re-read from Razorpay before
   anything is marked paid. Both the callback and the webhook go through the same
   `applyGatewayTruth`, so they cannot reach different answers.
3. **Order status and payment status never mix.** `Order = PACKED, Payment = CAPTURED` is ordinary;
   neither field is derived from the other.

## B. Files changed

**New — backend (12)**

| File | Purpose |
|---|---|
| `core/payment/PaymentStatus.java` | Payment state machine, enforced |
| `core/payment/PaymentAllocation.java` | The 50% waterfall, integer paise only |
| `core/payment/OrderInvoice.java` | The only place a payable amount is derived |
| `core/payment/PaymentMode.java` | ONLINE_FULL / ONLINE_PARTIAL / COD |
| `core/payment/CancellationActor.java` | The approved per-actor cancellation matrix |
| `core/payment/CancellationReason.java` | Controlled reason list |
| `core/payment/RazorpayProperties.java` | Credentials from env; refuses live keys |
| `core/entity/PaymentAttemptEntity.java` · `PaymentRefundEntity.java` · `PaymentWebhookEventEntity.java` | Persistence |
| `service/payment/RazorpayGateway.java` | The only place that talks to Razorpay |
| `service/payment/PaymentService.java` | Initiation, verification, allocation, failure |
| `service/payment/PaymentWebhookService.java` | Webhook verification + idempotency |
| `service/payment/PaymentAttemptWriter.java` | Insert in its own transaction (race recovery) |
| `controller/client/ClientPaymentController.java` | The five endpoints |
| `repository/payment/…` (3) | Repositories |
| `resources/db/changesets/PaymentDomain.yaml` | The migration |

**Modified — backend (5)**

`core/entity/CustomerOrderEntity.java` (4 additive fields) · `core/order/OrderStatus.java`
(post-dispatch cancellation permitted) · `service/admin/impl/SalesOrderServiceImpl.java`
(`cancelAs`, actor recorded) · `resources/db/master.yaml` · `pom.xml` (razorpay-java 1.4.8)

**New / modified — frontend (3)**

`src/services/razorpay.ts` *(new)* · `src/views/Payment.tsx` *(card/CVV/VPA fields removed,
Checkout wired)* · `.env.example` *(template, empty values)*

**Tests (5)**

`payment/PaymentAllocationTest.java` *(new, 12)* · `payment/PaymentPostgresTest.java` *(new, 14)* ·
`order/OrderStatusTest.java` + `order/OrderLifecyclePostgresTest.java` *(policy change)* ·
`features/order/order_lifecycle.feature` *(policy change)* ·
`playwright/fixtures/db.js` *(cleans up payment rows)*

## C. Database

Three tables and four additive columns. **No migration is destructive**; every new column on
`customer_order` is nullable or defaulted, so the seven historical orders remain valid unchanged.

| Table | Key columns | Indexes |
|---|---|---|
| `payment_attempt` | order, customer, `idempotency_key`, `sequence_no`, gateway, status, `amount_paise`, four allocation columns, razorpay ids, `gateway_fee_paise`, failure fields | `ux_payment_attempt_idempotency` · `ux_payment_attempt_rzp_order` *(partial)* · `ux_payment_attempt_rzp_payment` *(partial)* · **`ux_payment_attempt_open_per_order`** *(partial: `status IN (INITIATED,PENDING,AUTHORIZED)`)* |
| `payment_refund` | attempt, order, `idempotency_key`, `razorpay_refund_id`, amount, product/GST split, status | `ux_payment_refund_idempotency` · `ux_payment_refund_rzp` *(partial)* |
| `payment_webhook_event` | `event_id`, type, razorpay ids, status | **`ux_payment_webhook_event_id`** |

`customer_order` gains `payment_mode`, `cod_fee_paise` (default 0), `cancelled_by`, `cancelled_at`.

**Derived, not stored:** amount paid, amount outstanding, final invoice total — computed from
captured attempts, so no field can drift from the payments that actually happened.

## D. Razorpay

| Endpoint | Auth | Behaviour |
|---|---|---|
| `POST /client/payment/initiate` | Session + ownership | Creates the attempt and the Razorpay order; returns key **id**, order id, amount |
| `POST /client/payment/verify` | Session + ownership | Signature → re-read → transition |
| `GET /client/payment/order/{code}` | Session + ownership | Paid / outstanding / per-attempt allocation |
| `POST /client/order/{code}/cancel` | Session + ownership | Customer cancellation |
| `POST /client/payment/webhook` | **Signature only** | Razorpay server-to-server |

- **Checkout:** `https://checkout.razorpay.com/v1/checkout.js`, opened with the publishable key,
  the backend's `order_id` and the backend's amount.
- **Callback verification:** `HMAC-SHA256(order_id|payment_id)` via the SDK's `Utils`, then
  **eight further checks** — attempt exists, customer owns it, payment belongs to that gateway
  order, currency is INR, amount matches exactly, not already processed, transition is legal, all
  inside one transaction under a row lock.
- **Webhook:** signature verified against `RAZORPAY_WEBHOOK_SECRET` **before the body is parsed**;
  body taken as a raw string so the bytes the signature covers are unmodified. Handles
  `payment.captured`, `payment.authorized`, `payment.failed`; records `refund.processed` /
  `refund.failed` without acting. Unknown events are recorded and ignored. Returns 2xx once
  verified, so Razorpay does not redeliver an event that was understood.
- **Idempotency:** three unique indexes plus a row lock. A redelivered webhook loses the insert on
  `ux_payment_webhook_event_id` and stops.

## E. Payment lifecycle

```text
INITIATED ──► PENDING ──► AUTHORIZED ──► CAPTURED ──► REFUNDED
     │           │             │             └─────► PARTIALLY_REFUNDED ──► REFUNDED
     │           │             │
     └──► FAILED / CANCELLED ◄─┘          (terminal for that attempt)
```

`PENDING → CAPTURED` is permitted directly because some methods capture without a separate
authorization. `CAPTURED` can never regress to any unpaid state — asserted in tests.

## F. Order integration

`CAPTURED` makes the order **eligible for fulfilment**; it does not advance it. The order stays
`ORDER_PLACED` until an operator packs it, which is what "eligible for fulfilment" means. Delivery
remains a later, separate event.

Business stage names map onto the statuses that already exist, rather than renaming a working
lifecycle: *processing* → `ORDER_PLACED`/`PACKED`, *shipped* → `IN_TRANSIT`.

## G. Inventory

Payment failure → `cancelAs(SYSTEM, PAYMENT_FAILED)` → order `CANCELLED` → stock released by the
existing derived model, because `CANCELLED` is non-consuming. **No reservation semantics were
introduced and P0-2 was not redesigned.**

Double release is prevented at three levels: cancellation is a no-op when the order is already
`CANCELLED`; the order row is locked before its status is read; and stock is *derived* from status,
so releasing is not an operation that can run twice — the status is either `CANCELLED` or it is not.

Proven by `paymentFailureReleasesStockOnce` (5 repeats) and
`concurrentFailureCancellationsReleaseOnce` (12 concurrent threads): stock returns to 5, once.

## H. Partial payment — worked example

Product ₹10,000 · GST ₹1,800 · Transport ₹500 · Other ₹200 → invoice **₹12,500**, first payment
**₹6,250**:

| Head | Allocated | Remaining |
|---|---:|---:|
| GST | ₹1,800 (100%) | ₹4,450 |
| Transportation | ₹500 (100%) | ₹3,950 |
| Other charges | ₹200 (100%) | ₹3,750 |
| **Product** | **₹3,750** | ₹0 |

Product still outstanding: ₹10,000 − ₹3,750 = **₹6,250** — the second payment. Asserted exactly in
`approvedExample` and `approvedExampleBalance`.

The allocation is **stored on the attempt**, never recomputed, so a later price or tax change cannot
retrospectively alter what a customer was recorded as paying.

**Rounding:** an odd invoice cannot be halved exactly. The first payment is rounded **down** and the
balance carries the odd paise, so the two always sum to the invoice precisely and the customer is
never asked for more up front. Flagged in §P.

## I. COD

₹50 handling charge, recorded as `cod_fee_paise` and included in the payable. **No Razorpay
transaction is created** — `initiate` returns `cashOnDelivery: true` with no key and no order id,
and the frontend does not open Checkout. Verified: `codPayableIsServerComputed` asserts ₹12,350.00
(product + GST + transport + fee) with `razorpayOrderId == null`.

The COD fee is **non-refundable** — expressed structurally: `payment_refund` has no column for it,
and `PaymentAllocation.refundablePaise()` returns product + GST only.

**Cash collection is not implemented.** The event that means `CASH_COLLECTED` is recorded as a
blocker (§P) rather than being assumed to be `DELIVERED`.

## J. Cancellation authorization matrix

Enforced by `CancellationActor.mayCancelFrom`, tested for every cell in `cancellationMatrix`:

| | CUSTOMER | ADMIN | DELIVERY PARTNER | SYSTEM |
|---|---|---|---|---|
| `ORDER_PLACED` / `PACKED` *(processing)* | ✅ | ✅ | ❌ | ✅ |
| `IN_TRANSIT` *(shipped)* | ✅ | ✅ | ❌ | ❌ |
| `OUT_FOR_DELIVERY` | ❌ | ✅ | ✅ | ❌ |
| `DELIVERED` | ❌ | ❌ | ❌ | ❌ |

`DELIVERED` uses the existing return process. Every cancellation records `cancelled_by`,
`cancelled_at` and a reason; a reason is **mandatory** for ADMIN, DELIVERY_PARTNER and SYSTEM.
A customer cancelling someone else's order is refused indistinguishably from one that does not exist.

## K. Refund

**Domain and gateway call implemented; the refund workflow is not enabled.** What exists:
`payment_refund` with idempotency and partial-refund support; `RazorpayGateway.createRefund`;
refundable amount = product + GST, excluding transportation and the COD fee.

Not implemented, and blocked: the trigger (post-warehouse-verification), the accounting entries, and
gateway-fee exclusion — see §P.

## L. Accounting

**No accounting was changed. No collection entry is posted.** This is a deliberate stop, not an
omission.

P0-5B established that `postSale` is called **only** by `backfill()`, so a new order has no SALE
journal and therefore no receivable. Posting `Dr Bank / Cr AR` against it would credit a receivable
that was never debited and drive AR negative. Resolving that requires P0-6 **D35** (real-time sale
posting), which is `UNDECIDED`.

Mapping for when it is decided:

| Event | Posting | Accounts |
|---|---|---|
| COLLECTION | `Dr 1010/1020 / Cr 1100` | exist |
| GATEWAY FEE | `Dr <fee expense> / Cr 1020` | **NEW ACCOUNT — REQUIRES ACCOUNTING APPROVAL** |
| SETTLEMENT | `Dr 1020 / Cr <clearing>` | **NEW ACCOUNT — REQUIRES ACCOUNTING APPROVAL** |
| COD cash in transit | `Dr <cash with courier> / Cr 1100` | **NEW ACCOUNT — REQUIRES ACCOUNTING APPROVAL** |
| REFUND | `Dr <refunds contra or 4010> / Cr 1010/1020` | contra does not exist |

`gateway_fee_paise` is `NULL` until settlement data supplies it. **No rate is assumed.**
AR is **₹19,796.33, unchanged**. Closed-period protection is untouched.

## M. GST

**Unchanged.** No second calculation engine exists. The payment layer reads
`total_tax_amount` from the order's snapshot and allocates it; it never computes tax, applies a
rate, or knows what a rate is. The COD fee is treated as an "other charge" outside the GST base, per
the approved invoice structure — its tax treatment (D27) remains open (§P).

## N. Security

| Control | Status |
|---|---|
| Secrets in source / frontend / git / logs / responses | **None.** Verified by scan; the only `rzp_live_` occurrence is the code that *rejects* it |
| Frontend exposure | Only `VITE_RAZORPAY_KEY_ID` (publishable). `grep` for `KEY_SECRET`/`WEBHOOK_SECRET` in frontend source: **no matches** |
| Live keys | **Refused at startup** — `rzp_live_` throws |
| Card / CVV / UPI PIN | Not collected, not transmitted, not stored. **Zero instrument columns** in the schema. The old fields that collected a CVV and discarded it are **removed** |
| Webhook | Signature verified before parsing; refused outright when the secret is absent |
| Authorization | Session + ownership on every customer endpoint; a refusal is indistinguishable from "not found" |
| Frontend-controlled amount | **Refused explicitly** — verified live: `"The payment amount is determined by the server and must not be supplied."` |

Live probes with no credentials configured:

```
POST /payment/initiate   anonymous → 400        POST /payment/webhook  unsigned → 400
POST /payment/verify     anonymous → 400        initiate with amount:1 → 400 (explicit refusal)
```

## O. Tests

| Suite | Before | After |
|---|---|---|
| Backend | 400 / 400 | **427 / 427**, 0 failures |
| Cucumber | 86 run, 80 pass, 6 known | **87 run, 81 pass, the same 6** |
| Playwright client | 12 passed, 1 conditional skip | **12 passed, 1 skip** |
| Playwright admin | 15 passed | **15 passed** |

New: `PaymentAllocationTest` (12) · `PaymentPostgresTest` (14) · 1 lifecycle test · 2 Cucumber
scenarios.

**Concurrency and idempotency, on real PostgreSQL:** 20 simultaneous initiations with one key →
exactly one attempt, no caller errors · repeated initiation (5×) → one attempt · a second live
payment per order → refused · 12 concurrent failure-cancellations → stock released once.

**A regression I introduced and fixed:** the first Cucumber run showed **7** failures. The extra one
was my own P0-5A scenario asserting that a shipped order cannot be cancelled — which the approved
P0-7 policy now permits. Updated to the new policy (plus a new scenario proving `DELIVERED` still
cannot be cancelled), returning to exactly the six known failures. Not hidden, not weakened.

**Three defects the tests caught during development:** a poisoned Hibernate session after a
unique-index race (fixed with a separate-bean `REQUIRES_NEW` insert, as P0-3 does); free-text
cancellation reasons being overwritten by enum names; and the Playwright fixture leaving orphaned
payment rows.

## P. Remaining blockers

| # | Blocked | Needs |
|---|---|---|
| 1 | **Collection accounting** | P0-6 **D35** — real-time sale posting. Posting a collection against an order with no SALE journal would drive AR negative |
| 2 | **COD cash collection event** | The event meaning `CASH_COLLECTED`. Not assumed to be `DELIVERED` |
| 3 | **Refund workflow** | Trigger, accounting entries, gateway-fee exclusion |
| 4 | **COD fee GST** | D27 — tax opinion. Treated as outside the GST base per the approved invoice structure |
| 5 | **Transportation amount** | `shipping_value` is **0 on every order** and never written at checkout. The allocation handles it correctly at 0; the rule for computing it does not exist |
| 6 | **Delivery-partner authentication** | No such principal exists. The matrix is enforced in the domain; no endpoint exposes it |
| 7 | **Partial rounding** | Round-down-first confirmed as the rule |
| 8 | **Failure-rate alert threshold** | Deliberately not invented; counts are queryable |
| 9 | **Gateway fee source** | Populated from settlement data only; never from a rate |
| 10 | **Post-dispatch cancellation releases stock for goods in transit** | Operational confirmation — the parcel is on a van when the units return to availability |

## Q. Production readiness

| | |
|---|---|
| **IMPLEMENTED** | Payment domain · state machine · idempotency · Razorpay Orders API · Standard Checkout · signature + trusted-status verification · webhook with signature and dedupe · CAPTURED → fulfilment eligibility · FAILED → cancellation → single inventory release · 50% waterfall · COD · cancellation matrix · telemetry |
| **TESTED** | 427 backend · 14 PostgreSQL integration incl. 2 concurrency · 87 Cucumber · 27 Playwright. **The Razorpay API paths themselves are untested against the sandbox** — no credentials were available |
| **SANDBOX-READY** | Yes, once `RAZORPAY_KEY_ID` / `KEY_SECRET` / `WEBHOOK_SECRET` are set |
| **PRODUCTION-READY** | **No.** Live keys are refused by design. Blockers 1–3 are unresolved, no end-to-end sandbox payment has been performed, no reconciliation exists, and refunds are not enabled |

---

### Credential note

**No Razorpay credential appeared anywhere in the instructions I received**, so none could have been
copied into the project — verified by scan. If a secret was exposed elsewhere, rotate it in the
Razorpay dashboard. Only `.env.example` templates with empty values were created; `.env` is
gitignored in both projects and no real `.env` exists.
