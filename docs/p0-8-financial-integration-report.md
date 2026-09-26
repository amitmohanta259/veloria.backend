# P0-8 — Financial integration, transportation and sandbox validation

**Outcome: three of the four workstreams are BLOCKED on decisions that do not exist.** No accounting
entry was posted, no transportation rule was invented, and no sandbox payment was executed. What was
done instead: P0-7's behaviour was verified rather than assumed, and the two highest-risk paths it
could not cover — the callback/webhook race and verification's refusals — are now proven by test.

| Workstream | Outcome |
|---|---|
| **A. Accounting / D35** | **BLOCKED** — recognising SALE at payment capture is contradicted by the existing architecture, and posting it inside a capture would risk losing a payment |
| **B. Transportation** | **BLOCKED** — no authoritative pricing rule exists anywhere in the repository |
| **C. Payment → accounting** | **Partially satisfied already.** The snapshot and allocation requirements were met by P0-7; the posting half is blocked by A |
| **D. Sandbox validation** | **BLOCKED** — no Razorpay credentials are configured |

---

## 1. Accounting findings

### How SALE works today — verified, not assumed

| Question | Answer, from the code |
|---|---|
| Which accounts? | `Dr 1100 AR` / `Cr 4010 Sales` / `Cr 2100·2110·2120 Output GST` |
| How is AR created? | Only by `postSale`. `credit(RECEIVABLE, …)` appears nowhere — AR has never been cleared by a collection |
| Order ↔ journal link | `source_type='SALE'`, `source_id = customer_order.id` |
| **What date does the sale carry?** | **`dateOf(order.getOrderPlacedAt())`** — the sale is dated at **order placement**, not at payment |
| **Is eligibility payment-dependent?** | **No.** `isSaleEligible()` excludes only `CANCELLED` and `PAYMENT_FAILED`, and P0-5B states in the code that this is *"deliberately not a payment test"* |
| Who triggers it? | `backfill()` only |
| Duplicate prevention | `ux_journal_source` (partial, `status='POSTED'`) plus an any-status existence check (P0-5B) |
| Reversals | Non-destructive mirror, dated today, original marked `REVERSED` |
| Closed periods | `journal.post` → `assertPeriodOpen` — **every** posting path goes through it |

### D35 — remains **BLOCKED**

The task asks whether SALE should be recognised at payment capture. The existing architecture says
no, for three independent reasons:

1. **The sale is dated at order placement.** Recognising at capture would either post a journal
   dated at placement but triggered by capture (a trigger change dressed as a policy), or re-date it
   to capture time — which changes what the books say about when the supply happened.
2. **COD has no capture event.** Under capture-based recognition a COD order would never produce a
   sale, contradicting a model in which every non-cancelled order is sale-eligible.
3. **It could lose a payment.** `postSale` enforces the period lock. Posting a sale inside the
   payment-capture transaction means a closed period throws, the transaction rolls back, and a
   payment Razorpay has already taken is not recorded. That is strictly worse than no accounting.

**Stop conditions 1 and 8 apply.** Nothing was implemented.

### Payment collection accounting — **BLOCKED**

`Dr 1020 Bank / Cr 1100 AR` needs AR to exist. AR is created by `postSale`, which runs only at
backfill, so a payment collected today would credit a receivable that was never debited and drive AR
negative. Deferring the posting to a batch does not escape it either: the batch still needs a
recognition *date*, which is P0-6 **D19** (`BLOCKED` on D11).

### COD accounting — **BLOCKED**

The approved operational event is "customer receives order → cash collected". No event in the system
means that: `DELIVERED` is settable by any `ADMIN_GST` holder and records no money, and there is no
remittance or bank-credit concept. The timing question — recognise at creation, fulfilment, delivery
or bank credit — is **stop condition 2**. Not invented.

### Payment failure accounting — **already correct**

`PAYMENT_FAILED ≠ SALE` is enforced by `isSaleEligible()` (P0-5B) and re-proven here: after twelve
concurrent failure arrivals, `SELECT count(*) … source_type='SALE'` is **0** and
`capturedTotalPaise` is **0**. Failure telemetry — code, description, source, timestamp, gateway
payment id — is recorded on the attempt.

### Partial payment accounting — representable, not posted

The allocation is **stored per attempt** in four columns and never recomputed. That satisfies the
auditability requirement. What cannot be posted is the AR half, for the reason above: the first 50%
must settle only what it collected, and there is no AR to settle against.

### Refund accounting — **BLOCKED**

The reversal architecture could represent it (non-destructive, audited, idempotent). What is missing
is the trigger and the entries, both of which depend on collection accounting existing first.

### Gateway fee — representable, no account

`gateway_fee_paise` is a separate column, `NULL` until settlement data supplies it. **No rate is
assumed.** It is never combined with transportation. The ledger has no suitable account:

> **`NEW ACCOUNT — REQUIRES ACCOUNTING APPROVAL`** — gateway charges (expense), gateway clearing
> (asset), COD cash-in-transit (asset), refunds contra-revenue.

Verified: `chart_of_accounts` contains no account whose name matches *gateway* or *clearing*.

---

## 2. Transportation findings — **BLOCKED**

| | |
|---|---|
| Current amount | **₹0 on every order** — `SELECT count(*) … shipping_value > 0` returns **0** |
| Existing pricing rule | **None** |
| Source of a rule | **None exists** |
| Outbound / return / customer charge | Not distinguished — one column, `shipping_value` |
| Refund treatment | Excluded by policy; structurally enforced (no refund column for it) |

Searched and found nothing authoritative:

- `shippingRate`, `shippingFee`, `deliveryCharge`, `weightBased`, `shipping_config`,
  `delivery_pricing` — **0 files each**
- `courier` — 3 matches, all prose in comments
- `pincode` — address fields only, used for GST place-of-supply, never pricing
- No shipping/delivery/courier/freight table in the database
- `SalesInvoiceService` **copies** `order.getShippingValue()`; it does not compute it
- Nothing anywhere writes `shipping_value` at checkout

> ### BLOCKED — TRANSPORTATION PRICING DECISION REQUIRED
>
> A rule is needed before any amount can be charged: what determines it (flat, weight, distance,
> pincode zone, order value, free above a threshold), whether outbound and return are priced
> separately, and its GST treatment. **Stop condition 3.**

**Nothing was implemented, and no column was added.** §13 requires outbound, return and customer
charge to be distinguishable — but adding three columns before knowing what fills them would be
speculative schema built on an unmade decision. The allocation waterfall already handles
transportation correctly at any value including zero, so nothing is blocked by waiting.

---

## 3. Code changes

**One new test file. No production code was changed.**

| File | Change |
|---|---|
| `src/test/java/.../payment/PaymentConvergencePostgresTest.java` | **New** — 7 tests: the callback/webhook race, and verification's refusals |

No entity, service, controller, repository, migration, configuration or frontend file was modified.

## 4. Database changes

**None.** No table, column, index, constraint or migration was added or altered. Verified after the
full run: 7 orders · 54 journals · 187 lines · 0 payment rows · 0 automation users.

## 5. Payment → accounting

**Implemented:** everything up to the ledger.

```text
Order → Razorpay order → Payment → CAPTURED
   │                                   │
   │                                   ├─ allocation frozen on the attempt
   │                                   ├─ order eligible for fulfilment
   │                                   └─ ✗ SALE / collection posting — BLOCKED (§1)
   │
   └─ FAILED → order CANCELLED (SYSTEM) → inventory released once → no SALE, no collection ✓
```

The authoritative snapshot requirement (§15) is met: `OrderInvoice` reads the order's stored
`taxable_value`, `total_tax_amount`, `shipping_value` and `cod_fee_paise`. Nothing recomputes from
current prices, current rates or frontend values. Integer paise throughout; no floating-point
arithmetic exists in any financial path.

## 6. Partial payment

Unchanged from P0-7, re-verified:

| Head | Allocated | Remaining |
|---|---:|---:|
| GST | ₹1,800 (100%) | ₹4,450 |
| Transportation | ₹500 (100%) | ₹3,950 |
| Other charges | ₹200 (100%) | ₹3,750 |
| **Product** | **₹3,750** | ₹0 |

₹12,500 invoice → ₹6,250 first payment → outstanding product **₹6,250**.

**The allocation is stored, not recomputed** — four columns on `payment_attempt`, written once at
capture. `callbackAndWebhookConverge` asserts the stored allocation still adds up to the amount
after a 16-thread race, and that a late arrival does not allocate again.

## 7. Sandbox — **NOT EXECUTED**

```
RAZORPAY_KEY_ID      = NOT SET
RAZORPAY_KEY_SECRET  = NOT SET
RAZORPAY_WEBHOOK_SECRET = NOT SET
no .env file present
```

**Stop condition 13.** Every item below is therefore **NOT EXECUTED**, and none is claimed:

| Test | Status |
|---|---|
| CARD · UPI · real payment failure · live webhook · duplicate live webhook · invalid live signature · live signature verification | **NOT EXECUTED — no credentials** |

What *was* proven without credentials, because it tests this application rather than Razorpay:

| Behaviour | Test | Result |
|---|---|---|
| Callback + webhook simultaneously (16 threads) | `callbackAndWebhookConverge` | One capture · allocation intact · money counted once |
| Failure via both paths (12 threads) | `concurrentFailureConverges` | One cancellation · **stock released once** · no SALE · nothing collected |
| Wrong amount | `wrongAmountIsRefused` | Refused, payment stays `PENDING` |
| Wrong gateway order | `wrongRazorpayOrderIsRefused` | Refused |
| Wrong currency | `wrongCurrencyIsRefused` | Refused |
| Unsigned callback | `unsignedCallbackIsRefused` | Refused before any lookup |
| Late duplicate arrival | `lateArrivalIsIgnored` | Ignored; no second allocation |
| Unauthorised customer | `cannotPayForSomeoneElsesOrder` (P0-7) | Refused, indistinguishable from "not found" |

The gateway is stubbed in the race tests. That is deliberate: what is under test is this
application's convergence, and pointing two threads at Razorpay's sandbox would test Razorpay.

## 8. Regression

| Suite | Baseline | After |
|---|---|---|
| Backend | 427 / 427 | **434 / 434**, 0 failures |
| Cucumber | 87 run, 81 pass, 6 known | **87 run, 81 pass, the same 6** |
| Playwright client | 12 passed, 1 conditional skip | **12 passed, 1 skip** |
| Playwright admin | 15 passed | **15 passed** |

**One failure occurred and is reported rather than hidden.** The first client run showed
`critical-flow` failing after 77ms with `Target page, context or browser has been closed` — a
browser crash, not an assertion. It passed on re-run (7.5s) and the full suite passed cleanly
afterwards. Recorded as transient infrastructure; no test was altered.

The six known Cucumber failures are unchanged and unfixed: three missing-header 400-vs-401, a
place-of-supply 500, the reconciliation check, and `/products/new-in` returning `[]` because the seed
data has aged past its 30-day window.

All protections re-verified as part of the 434: P0-3 idempotency · P0-5A authorization, cancellation
and inventory · P0-5B accounting idempotency, cancellation reversal and closed-period protection ·
GST · AR.

## 9. AR

**Unchanged: 1,979,633 paise = ₹19,796.33.**

No accounting entry of any kind was created. Ledger source types after the full run: `SALE` × 28,
`REVERSAL` × 21, `PAYROLL` × 2, `EXPENSE` × 1, `PURCHASE` × 1, `GST_PAYMENT` × 1 — the last being
the pre-existing GST remittance, not a payment collection. **No `PAYMENT_COLLECTION` journal
exists.** No historical journal was modified.

## 10. Production readiness

| | |
|---|---|
| **IMPLEMENTED** | P0-7 unchanged: payment domain, state machine, idempotency, Razorpay Orders API, Standard Checkout, signature + trusted-status verification, webhook with dedupe, CAPTURED → fulfilment eligibility, FAILED → cancellation → single inventory release, 50% waterfall, COD, cancellation matrix. **Accounting integration is not implemented.** |
| **TESTED** | 434 backend · 21 PostgreSQL payment integration tests including 4 concurrency tests · 87 Cucumber · 27 Playwright |
| **SANDBOX-VALIDATED** | **No.** No credentials; no gateway call has ever been made |
| **PRODUCTION-READY** | **No.** Live keys are refused at startup. Accounting, transportation, refunds, reconciliation, settlement validation, operational monitoring and a runbook are all absent |

---

## Blockers

```text
BLOCKED  D35 — SALE recognition timing
  Reason              The sale is dated at order placement and eligibility is explicitly
                      payment-independent; capture-based recognition contradicts both and
                      breaks COD. Posting inside a capture risks a closed period rolling
                      back a payment already taken.
  Required decision   When is a sale recognised, and is posting real-time or batch?
  Affected component  AccountingPostingService, PaymentService
  Code changed        None

BLOCKED  Payment collection accounting
  Reason              Dr Bank / Cr AR needs AR, which only postSale creates. Collecting
                      before the sale is posted would drive AR negative.
  Required decision   D19 — when is AR cleared: capture, settlement, or collection?
  Affected component  PaymentService, AccountingPostingService
  Code changed        None

BLOCKED  COD sale/collection timing
  Reason              No event in the system means "cash collected". DELIVERED records
                      no money and is settable by any admin.
  Required decision   D17 — the event that means CASH_COLLECTED
  Affected component  PaymentService, order lifecycle
  Code changed        None

BLOCKED  Transportation pricing
  Reason              No rate table, courier integration, distance, weight or zone logic
                      exists. shipping_value is 0 on every order and never written.
  Required decision   How transportation is priced; outbound vs return; GST treatment
  Affected component  OrderInvoice, checkout, refunds
  Code changed        None

BLOCKED  Refund accounting
  Reason              Depends on collection accounting existing first.
  Required decision   D15 — refund trigger and postings
  Affected component  PaymentRefund, AccountingPostingService
  Code changed        None

BLOCKED  Gateway fee account
  Reason              No suitable account in the chart of accounts.
  Required decision   NEW ACCOUNT — REQUIRES ACCOUNTING APPROVAL (×4)
  Affected component  AccountingPostingService
  Code changed        None

BLOCKED  Razorpay sandbox validation
  Reason              RAZORPAY_KEY_ID / KEY_SECRET / WEBHOOK_SECRET are not set.
  Required decision   None — set the credentials locally
  Affected component  Parts D17–D22 of the implementation order
  Code changed        None
```

### The one decision that unblocks the most

**D35.** It gates collection accounting, which gates AR settlement, refund accounting and
reconciliation. Given the evidence above, the question to put to Finance is narrow:

> The sale is already dated at order placement and is already independent of payment. Should
> `postSale` run at checkout instead of at backfill — producing the identical journal, only sooner —
> and what happens when the order's period is already closed?

Answering that unblocks four of the seven items without changing a single accounting rule.
