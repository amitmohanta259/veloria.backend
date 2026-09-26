# P0-13 — Final financial decision register

## Executive summary

This is a gate, not an implementation phase. Seven financial questions remain
open, and this phase turned none of them into code.

The system is technically ready for all seven. Each has its data model, its
snapshot, its idempotency and its refusal path already in place; what each is
missing is a business, tax or accounting answer that the repository does not
contain. Where a phase could have guessed — a GST rate, a refund destination, a
pass-through formula — it refuses instead, records why, and says what would unblock
it.

One thing was closed by inspection rather than by decision: **no account was
created, no column was added, no migration was written, and no GST rule was
seeded.** The chart of accounts still holds 26 accounts, the tax master still holds
8 rules, and the historical journal checksum is unchanged.

**One discrepancy to resolve**, raised rather than silently absorbed: the P0-13
brief names the refund's terminal success state `COMPLETED`; the code names it
`REFUNDED`, because that is the name the P0-11/P0-12 brief gave. Same state, same
semantics, different label — see *Decisions requiring technical approval*.

## Current approved decisions

These are settled, implemented and verified. Nothing here is awaiting anyone.

| Decision | Rule | Where it lives |
|---|---|---|
| **COD fee amount** | ₹50 | `PaymentService.COD_FEE_PAISE` |
| **COD fee classification** | Other Income | account **4090**, which already existed |
| **COD fee is taxable** | yes — *rate undecided* | `CodFeeTaxResolver`, gated on resolution |
| **COD fee refundability** | non-refundable | absence of a column on `payment_refund` |
| **SALE timing** | at order creation (D35) | `ClientOrderServiceImpl`, after commit |
| **Closed period (CP-1)** | order stands; sale posts in the next open period | `PostingDateResolver` |
| **Transaction vs posting date** | both retained, separately | `journal_entry.transaction_date` / `journal_date` |
| **Payment waterfall** | GST → transport → other → product | `PaymentAllocation` |
| **Partial payment** | 50% first, balance second | `OrderInvoice` |
| **Refund eligibility** | verified return + goods received + not already refunded + something collected | `RefundService.eligibilityFor` |
| **Refund amount** | product + its GST, from the allocation stored at capture | `PaymentAllocation.refundablePaise()` |
| **Refund destination (online)** | back to the paying instrument | `RAZORPAY_SOURCE` |
| **Gateway fee split** | 50 / 50 of the actual fee, odd paisa to the business | `GatewayFeeSplit` |
| **Cancellation matrix** | per-actor, per-status | `CancellationActor` |
| **Transportation** | ₹0 until approved | `shipping_value`, default 0 |

## Remaining decisions

### D-COD-GST — COD fee GST

```
COD GST SAC:      UNDECIDED
COD GST rate:     UNDECIDED
₹50 treatment:    UNDECIDED  (A. taxable value + GST on top / B. tax-inclusive)
```

**No rate is implemented, and 18% is not assumed anywhere.** The figure that
appears in one test is seeded by that test against a service code that exists only
for its duration, and removed afterwards.

### D-COD-REFUND — COD refund destination

```
STATUS: UNDECIDED
Options: A. secure bank/UPI collection during the refund process
         B. manual admin refund
         C. store credit
         D. other
```

Not implemented. No customer banking column was added, and the supplier bank
columns were **not** reused for customers.

### D-GATEWAY-CUSTOMER-CHARGE — how the customer's half is known before payment

```
STATUS: UNDECIDED
Options: A. fixed customer processing fee, defined before checkout
         B. estimated fee shown before checkout + an approved reconciliation rule
         C. business bears the whole gateway fee
         D. other
```

The rejected model is explicit: charging the customer 50% of a fee discovered
*after* payment is **not implemented**, because it would alter an invoice the
customer has already paid.

### D-GATEWAY-GST — GST on the customer-facing gateway charge

```
Gateway customer charge GST: UNDECIDED
Required: taxable / non-taxable / other
          SAC if taxable · rate if taxable · inclusive vs exclusive
```

Not inferred from product GST, not inherited from COD GST, not assumed to be 18%.

### D-GATEWAY-REFUND — refundability of the customer-borne half

```
STATUS: UNDECIDED
Options: A. refundable   B. non-refundable   C. other
```

Cannot be settled before D-GATEWAY-CUSTOMER-CHARGE: there is no customer charge yet
to refund.

### D-TRANSPORTATION — pricing

```
STATUS: UNDECIDED
```

No weight, pincode, distance, courier, slab or seller pricing was implemented. No
shipping table exists.

## Evidence from the repository

Everything below was read from the live system during this phase, not carried over
from a report.

### The tax master holds no service code at all

```
gst_tax_rules    8 rows:  4202, 4203, 61, 62, 63     (textiles and leather)
gst_hsn_master  19 rows:  chapters 42, 61, 62, 63     (goods only)

rows matching ^99 or service / COD / fee / charge / handling:   0
```

`GstCalculationService` therefore returns `NO_RULE`, which it deliberately
distinguishes from a genuine 0%. There is no authoritative rate to read for the COD
charge or for a gateway charge, and no rate may be inferred from goods rates — a
handling charge is a service, not a garment.

Searched for an explicit inclusive/exclusive rule for the ₹50: the only related
statement anywhere is `GstCalculationService`'s "tax-exclusive base", which
describes a product's unit price. It is not a rule about this charge.

### No customer refund destination exists

```
columns matching bank / ifsc / upi / vpa / account_number / beneficiary / payout:
  payment_refund.refund_destination    a label (RAZORPAY_SOURCE), not an account
  supplier.account_number              vendors
  supplier.bank_name                   vendors
```

### No pre-payment customer fee exists

Searched backend, both frontends and all documentation for *convenience fee,
platform fee, service fee, transaction fee, processing fee, handling fee, customer
fee*: **no pre-payment customer fee of any kind exists**. There is nothing
authoritative to reuse.

### The chart of accounts is unchanged

```
26 accounts.   Expense accounts: 5010 COGS, 5100 Marketing, 5200 Payroll,
                                 5300 Rent, 5400 Utilities, 5900 Other Operating
Every account code referenced by the posting service exists in the chart.
No account was created in this phase.
```

### Transportation carries no pricing

```
customer_order.shipping_value          bigint, default 0
customer_order.shipping_taxable_value  bigint, default 0
tables matching shipping / courier / freight / delivery_rate:   none
orders with a non-zero shipping_value:                          0
pricing logic (weight / pincode / distance / courier / slab):   none
```

### Money is integer paise throughout

```
double / float / BigDecimal in core/payment, service/payment,
AccountingPostingService, JournalService, PostingDateResolver:   none
```

The only textual match is the word "double" in a comment about a backfill.

## Verification of the architecture the decisions will land on

### §7 — the COD frontend is server-authoritative

```
hardcoded COD amount in the client React source:   none
```

The payment screen reads `codCharge.feePaise`, `codCharge.taxPaise` and
`codCharge.totalPaise` from `CartGstPreviewResponse`, priced by the same
`CodFeeTaxResolver` that prices the order. The COD fee GST line renders only when
`taxResolution == RULE_APPLIED`, so an unresolved rate is never displayed as ₹0.

`COD-001` and `COD-002` are retained and still prove server authority — COD-002 by
intercepting the response, changing the fee to ₹77.77 and requiring the page to
follow it.

### §8 — the COD GST snapshot can represent everything, with no rate hardcoded

| Required concept | Column |
|---|---|
| `codFeeAmount` | `cod_fee_paise` |
| `codFeeTaxableAmount` | `cod_fee_taxable_paise` |
| `codFeeTaxRate` | `cod_fee_tax_rate_bp` — **nullable**, so unknown ≠ 0% |
| `codFeeTaxAmount` | `cod_fee_tax_paise` |

Plus `cod_fee_cgst/sgst/igst_paise` (the head split a journal needs),
`cod_fee_sac_code` (what the rate was looked up by, never a rate) and
`cod_fee_tax_resolution`. **Existing fields were reused; nothing redundant was
added.**

### §9 — the refund state machine

```
REQUESTED  → APPROVED, PROCESSING, FAILED
APPROVED   → PROCESSING, FAILED
PROCESSING → REFUNDED, FAILED
REFUNDED   → (terminal)
FAILED     → (terminal)
```

Verified properties: a refund cannot skip the gateway (`REQUESTED`/`APPROVED` →
`REFUNDED` is refused), cannot go backwards, and a failure cannot become a success.
Only `REFUNDED` is accountable. `PROCESSING` and later count as money committed, so
a second request cannot send it twice.

**A COD refund cannot reach the gateway.** Eligibility refuses an order whose
captured payments are all COD, naming the open decision; and inside the
attempt loop COD attempts are skipped, so a mixed online/COD order refunds against
the online instrument only. No COD refund can be marked `REFUNDED` without a
destination, because none can be created at all.

`APPROVED` is the state a COD refund will rest in once a destination is approved —
the business has accepted the debt and nothing has moved.

### §10 — the gateway fee model is not collapsed

| Concept | Column | Type |
|---|---|---|
| gross paid | `amount_paise` | bigint, not null |
| `actualGatewayFee` | `gateway_fee_paise` | bigint, nullable |
| `businessGatewayFee` | `gateway_fee_business_paise` | bigint, nullable |
| `customerGatewayCharge` | `gateway_fee_customer_paise` | bigint, nullable |
| net settlement | `net_settlement_paise` | bigint, nullable |

Three separate fields, never collapsed — asserted against the database, and
asserted to be three genuinely different numbers so a change that merged them could
not pass. Nullable on purpose: half of an unknown fee is not zero.

**Odd-paise handling is deterministic and integer-only:**

```java
long customer = feePaise / 2;
long business = feePaise - customer;   // the business takes the odd paisa
```

Exhaustively checked over 5,000 consecutive fee values: the two halves always sum
to the fee exactly, and the business half is never the smaller one.

## Decisions requiring business approval

| | Decision | Why it matters |
|---|---|---|
| 1 | **₹50: taxable value or tax-inclusive** | Changes what the customer is billed — ₹50 or ₹50 + tax |
| 2 | **COD refund destination** | A COD customer currently cannot be refunded at all |
| 3 | **Gateway customer charge model** | Determines whether a customer-facing charge exists, and how it is knowable before payment |
| 4 | **Gateway customer charge refundability** | Downstream of 3 |
| 5 | **Transportation pricing** | Nothing is charged for delivery today |

## Decisions requiring tax/accounting approval

| | Decision | Why it matters |
|---|---|---|
| 6 | **COD fee SAC and GST rate** | The charge is approved as taxable; without a rate the income cannot be recognised, so the receivable the customer pays against cannot be raised |
| 7 | **Gateway customer charge GST treatment** | Taxable or not, and at what rate |
| 8 | **Gateway fee expense account** | See below — requires accounting approval, not a code |

### New account requirement — documented, not created

```
Account name          Payment Gateway Charges
Purpose               The business-borne share of payment processing fees
Debit/Credit          DEBIT (a cost; increases with a debit)
Required type         EXPENSE, subgroup "Operating expenses"
Status                REQUIRES ACCOUNTING APPROVAL — no code assigned
```

The catch-all **5900 Other Operating Expenses** exists and is structurally correct,
but using it forfeits the ability to report payment-processing cost separately —
which matters for a business whose margin depends on it. Either answer is
acceptable; the choice is an accounting one. **No account code was invented.**

Note that approving the account alone does not unblock posting: the gateway fee also
awaits decisions 3 and 7, and a settlement event to date the entry by.

## Decisions requiring technical approval

| | Question | Recommendation |
|---|---|---|
| 9 | **Refund terminal state: `REFUNDED` or `COMPLETED`?** | Either is fine. `REFUNDED` is what P0-11/P0-12 specified and what is implemented; this brief says `COMPLETED`. The `payment_refund` table holds **0 rows**, so a rename needs no migration and no data fix — roughly six code sites and three tests. **Not renamed unilaterally**, because it changes a persisted contract on the strength of an ambiguity between two briefs |

## Historical baseline

Verified before any change, and again after:

| | Expected | Measured |
|---|---|---|
| Journal checksum | `eea1e82cfa93ce16f7963524e3eaf56f` | **match** |
| SALE | 28 | **28** |
| REVERSAL | 21 | **21** |
| AR | ₹19,796.33 (1,979,633 paise) | **match** |
| Journals | 54 | **54** |
| Journal lines | 187 | **187** |
| Orders | 7 | **7** |

No historical journal was modified, deleted or rewritten. No historical order was
touched. No closed period was reopened. **No schema change was made in this phase
at all.**

## Test results

| Suite | P0-12 | P0-13 |
|---|---|---|
| Backend | 505 | **506**, 0 failures |
| Cucumber | 87 run, 6 known failures | **87 run, the same 6** |
| Playwright client | 14 passed, 1 skipped | **14 passed, 1 skipped** |
| Playwright admin | 15 passed | **15 passed** |

One test added — the only code change in this phase:

**`transportationSnapshotFlowsThroughWithoutPricing`** — writes a ₹500
transportation amount directly onto an order's snapshot (no algorithm computes it,
because none exists) and proves the invoice grows by exactly that, the customer is
charged it, the approved waterfall settles it in full before the product, and the
refund excludes it. It validates that a future pricing rule need only populate the
column.

The six known Cucumber failures are unchanged and were not rewritten: three
missing-header 400-vs-401 (`/bag`, `/bag/add`, `/favourites`), a place-of-supply 500
on a free-text address, the reconciliation check, and `/products/new-in` returning
`[]` because the seed data has aged past its 30-day window.

## Remaining blockers

```
UNDECIDED  COD fee GST SAC and rate
UNDECIDED  ₹50: taxable value or tax-inclusive
UNDECIDED  COD refund destination
UNDECIDED  Gateway customer charge — pre-payment model
UNDECIDED  Gateway customer charge GST treatment
UNDECIDED  Gateway customer charge refund treatment
UNDECIDED  Transportation pricing
BLOCKED    Gateway fee expense account — requires accounting approval
BLOCKED    Razorpay sandbox — credentials not configured
```

## Sandbox

```
BLOCKED — sandbox credentials not configured
```

`RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` and `RAZORPAY_WEBHOOK_SECRET` are unset
and no `.env` exists. No Razorpay call has ever been made from this application. No
secret is committed; live keys are refused at startup.

## Next phase

Nothing further can be built on the financial side without answers. The next phase
should be whichever of these arrives first:

1. **Decision intake** — apply the approval form below. Each answer is a small,
   contained change against an architecture that is already in place and tested.
2. **Sandbox validation** — a separate phase once credentials exist, covering card
   and UPI success, failure, partial payment, refund, refund failure, webhook,
   duplicate webhook, signature verification, and the wrong-amount,
   wrong-currency and wrong-gateway-order refusals.

These are independent and can happen in either order.
