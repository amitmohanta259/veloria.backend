# P0-14 — COD refund, addressless GST preview, transportation

Companion to `p0-14-decision-register.md`, which holds the statuses. This one records
what was changed, what was proved, and what was deliberately not done.

## 1. COD refund destination

`STATUS: BLOCKED` — see the register for the five options. Nothing was chosen.

The one change is a **correction, not an implementation**. The code claimed an
approval that does not exist:

```
before  "The approved refund destination is store credit, and this application has
         no store-credit facility to issue it to…"

after   "…no refund destination has been approved for cash payments, so the refund
         cannot be issued here. The amount owed stands; how it reaches the customer
         needs a business decision."
```

The register records **D-COD-REFUND: UNDECIDED** with store credit as one of four
options. The old sentence went to a customer as policy. The new one names the open
decision and picks nothing — and a test asserts that no destination may be presented
as approved, so the claim cannot come back.

No banking column was added. The supplier bank columns were not reused for customers.

## 2. COD refund status and safety

Nothing about the eligibility chain was changed; it was re-verified:

```
return created → goods received → warehouse verified → refund eligible → refund
```

No refund is issued before verification. The existing gates are: verified return
**and** goods received **and** not already refunded **and** something actually
collected, on an attempt that can be refunded.

### Amount safety

| Rule | Where it holds |
|---|---|
| refund ≤ amount collected | The amount comes from `PaymentAllocation.refundablePaise()` **recorded at capture**, less what was already refunded — never recomputed from current prices or rates |
| refund ≤ refundable amount | Transportation and the COD fee are absent from `refundablePaise()`; that absence *is* the policy |
| one refund per return | Partial unique index on the return request, plus `SELECT … FOR UPDATE` |
| no over-refund | A partially refunded attempt gives back only what remains, taken from the product first so tax already returned is not returned twice |

### Concurrency — 12 simultaneous COD refund attempts

New test `concurrentCodRefundAttemptsAllRefuse`. The online case was already covered
by `concurrentRefundsProduceOne` (exactly one winner); for COD the correct outcome is
that **nobody wins**, and what had to be proved is that a race cannot turn twelve
refusals into one accidental payout:

```
12 threads · all 12 refused · 0 refunds issued · 0 refund rows · 0 journals
receivable unchanged · every refusal names the open decision
```

### Accounting

Unchanged. `SALE`, `COD_FEE`, the COD collection and `REFUND` are all preserved. No
original accounting event was deleted and no historical journal was rewritten.
`COD_FEE_REFUNDABLE = false` is preserved, so the handling charge is not refunded.

## 3. Addressless product GST preview

### The defect

```
GET /client/bag/gst-preview   (shopper with no saved address)
  → ClientBagServiceImpl.getGstPreview
  → GstCalculationService.calculate(hsn, price, buyerStateCode = null, …)
  → GstIdentityService.isInterState(null, sellerState)
  → IllegalStateException("Place of supply is unknown — cannot determine CGST/SGST vs IGST")
  → HTTP 500
```

The engine is right to refuse: without a place of supply there is no answer to
"CGST and SGST, or IGST?". The defect was the **preview** passing that refusal to a
shopper as an internal error. Opening the bag before saving an address is the
ordinary first visit.

**This is not the COD path.** The COD charge already answered gracefully
(`NO_PLACE_OF_SUPPLY`, `available: false`) — P0-13 fixed that. This was the product
tax beside it.

### The fix, and why it invents nothing

Checkout **already had the guard**:

```java
// ClientOrderServiceImpl
boolean gstResolvable = pos.resolved() && sellerStateCode != null;
```

The preview was the one caller without it. So the fix is that same gate applied in the
preview — not a relaxation of the engine, which would have changed every caller
including order placement:

```java
// ClientBagServiceImpl
boolean gstResolved = !isBlank(buyerStateCode) && !isBlank(sellerStateCode);
```

And §9 option D applied for the behaviour: the frontend has shown
**"Add a delivery address to see GST"** in the bag since before this phase, reaching
it only because the request failed. The server now *says* what the UI already assumed.
No new business copy, no new rule.

### The response

```
HTTP 200                     — the existing contract; the neighbouring scenario
                               asserts 200 for a resolved preview, and the COD charge
                               already reports its own unavailability inline at 200
gstResolved      false
taxResolution    NO_PLACE_OF_SUPPLY      (the same vocabulary CodFeeTaxResolver uses)
supplyType       null
cgstAmount       null
sgstAmount       null
igstAmount       null
totalGst         null
grandTotal       null
subtotalPaise    <the real subtotal>     — price data, so always known
items[].taxableValuePaise  <real>        — and its rates and amounts null
```

**Null, not zero — this is the whole point.** "The tax is not determinable" and "this
supply is taxed at 0%" are different statements, and a client that cannot tell them
apart shows a tax-free total on a taxable order. Every tax field is nullable now, on
the record and in both TypeScript interfaces. Nothing falls back to 0%, the seller's
state, a default state or the product's state.

### Checkout was not weakened

The preview's tolerance stops at the preview. `checkoutStillRequiresAPlaceOfSupply`
asserts from the other side: an order whose place of supply is nulled is refused with
a 400 when COD is initiated, and nothing is posted. A future change that relaxes the
order path to simplify a preview fails that test.

```
Preview         address may be missing · tax may be unresolved · HTTP 200
Order placement place of supply required · tax must resolve · HTTP 400 otherwise
```

### Frontend

`ShoppingBag.tsx` and `Payment.tsx` now key off `gstResolved` rather than merely
"a preview arrived", because an unresolved preview *does* arrive:

- the GST rows render only when resolved; otherwise the existing address prompt (bag)
  or a dash (payment) shows;
- the "Total GST" line is not rendered at all when there is no determined tax — an
  unconditional `totalGst / 100` would have printed ₹0;
- the order total falls back to the subtotal instead of `null / 100`.

No arithmetic was added. Every figure still comes from the server.

## 4. Transportation

`STATUS: UNDECIDED`, and nothing was implemented. The register lists the four fields
and nine possible methods; the search that confirms no pricing logic exists is
recorded there.

The model already satisfies §16 with no change: `shipping_value` is a snapshot,
defaulting to 0, read and never derived. The approved waterfall still holds:

```
Product + Product GST + Transportation + Other charges + COD fee + COD fee GST
  = Final invoice
```

With transportation 0 today, and a stored authoritative amount when one is approved.
Historical transportation is never recalculated from future rules.

## 5. Frontend consistency

Searched both frontends for business arithmetic:

```
+ 50 · + 9 · 0.18 · * 18 · 18/100 · shipping = <n> · transportation = <n>
₹50 · 50.00 · 7.63 · 42.37
```

**One hit, and it is a comment** recording the bug that was removed:

```
Payment.tsx:270   // This used to be `grandTotalRupees + 50`. …
```

"Complimentary" appears as the shipping label in three places, which is a label on a
₹0 amount, not a calculation. No rate, fee or tax is computed in the browser.

## 6. COD GST regression

Re-verified live, not copied from P0-13:

```
SAC      998599      tax_code_type = SAC, EXACT, active, from 2017-07-01, open
Rate     CGST 9% · SGST 9% · IGST 18%
Basis    INCLUSIVE
Gross    ₹50.00      = ₹42.37 taxable + ₹7.63 GST
Intra    CGST 381 + SGST 382 = 763      (odd paisa to SGST)
Inter    IGST 763
Missing place of supply → HTTP 400 / NO_PLACE_OF_SUPPLY, never 500
```

`concurrentQuotesAgree` adds twelve simultaneous cart quotes and asserts every one is
byte-identical **and** that the agreed figures are the approved 4237 / 763 / 1800 —
so the test fails if twelve readers agree on the wrong thing.

## 7. Tests

| Suite | Result |
|---|---|
| Backend | **544 run, 0 failures, 0 errors** (was 540) |
| Cucumber (unfiltered, `-Dcucumber.filter.tags=`) | **87 run, 0 skipped, 5 failures** (was 6) |
| Playwright client | **18 tests: 17 passed, 1 skipped** — twice consecutively |
| Playwright admin | **15 passed** |
| `FinancialDecisionsPostgresTest` | 39 (was 35) |
| `CodServiceTaxPostgresTest` | 30 |
| GST tests | `GstCalculationService` 9 · `GstRounding` 9 · `PlaceOfSupplyResolver` 7 · `GstIdentityService` 7 · `SalesInvoiceService` 26 |
| Accounting tests | `AccountingPostingService` 9 · `PostingDateResolver` 8 · `SaleAndCollection` 11 · `JournalService` 15 · `AccountingBackfill` 16 · `OpeningBalance` 9 |
| Refund tests | `RefundStatusTest` 8, plus the refund cases inside `FinancialDecisionsPostgresTest` |
| Concurrency | COD refund ×12 · cart quote ×12 · online refund ×12 · payment initiation · failure cancellation ×12 · add-stock ×3 ×2 |

### New

| Test | Proves |
|---|---|
| `addresslessProductGstPreviewDoesNot500` | 200 not 500; `gstResolved` false; `NO_PLACE_OF_SUPPLY`; every tax field null; subtotal and line values still real; no order, no payment attempt, no journal |
| `checkoutStillRequiresAPlaceOfSupply` | The preview's tolerance did not leak into placing an order — 400, nothing posted |
| `concurrentCodRefundAttemptsAllRefuse` | 12 threads, 12 refusals, 0 refunds, 0 journals, receivable unchanged |
| `concurrentQuotesAgree` | 12 simultaneous quotes identical, and equal to the approved figures |
| `GST-020` (Playwright) | The addressless preview over HTTP: 200, unresolved, nulls not zeros |
| `GST-021` (Playwright) | The bag asks for an address, renders no "Total GST" line, and totals to the subtotal |

### One Cucumber failure genuinely disappeared

```
before  6 failures
after   5 failures
gone    "A new shopper with no address yet gets a handled answer, not a server error"
```

The other five are unchanged in count, identity and message, compared line by line
against the previous run — three `401 vs 400` header-validation cases, one empty
`new-in` collection, and one ledger-vs-orders reconciliation check. **None was
modified, hidden or rewritten**, and the fixed one was fixed in the behaviour, not in
the test: the scenario file is untouched.

### A test-fixture race, found and fixed

`ProductPage.goto()` returned as soon as Buy Now was painted, but the button reads
state filled in by a *second* request (`/products/{id}/sizes`, which auto-selects a
size). Clicking in between is a click that silently does nothing — the product view
refuses to add anything while a product has sizes and none is chosen — and the test
then failed ten seconds later waiting for a POST that was never going to be sent.

This is the cause of the intermittent COD-001/COD-003 failures recorded as
unexplained in `p0-13-cod-gst-implementation-report.md` §M. `goto()` now waits for the
sizes response — the app's own readiness signal, so a slow dev server waits longer
rather than flaking. Two consecutive full runs are green.

## 8. Accounting reconciliation

```
Invoice = SALE + approved charge lines
          product + product GST + transportation(0) + other + COD gross

AR      = Invoice − collections − approved refund/adjustment effects
```

Verified by the existing suite and unchanged by this phase. No arbitrary cap hides a
mismatch: the only remaining floor prevents a negative receivable, and the inclusive
COD lifecycle test still shows AR reaching exactly 0 after collection.

Twelve refused COD refunds moved no balance at all, which is the reconciliation
statement for this phase's one refund-related change.

## 9. Historical checksum

Captured before testing and compared after the complete regression — backend,
unfiltered Cucumber, both Playwright suites:

| | Before | After |
|---|---|---|
| Checksum | `eea1e82cfa93ce16f7963524e3eaf56f` | **`eea1e82cfa93ce16f7963524e3eaf56f`** |
| Journals · lines | 54 · 187 | **54 · 187** |
| SALE · REVERSAL | 28 · 21 | **28 · 21** |
| EXPENSE · GST_PAYMENT · PAYROLL · PURCHASE | 1 · 1 · 2 · 1 | **1 · 1 · 2 · 1** |
| Orders | 7 | **7** |
| AR (1100) | 1,979,633 (₹19,796.33) | **1,979,633** |
| Orphan lines | 0 | **0** |
| Test-owned orders · refunds · payment attempts | 0 | **0 · 0 · 0** |

All thirteen account balances identical:

```
1020 −100,500   1100 1,979,633   1200 5,000,000   1300 1,350,000
2010 −8,850,000 2100 −39,840     2110 −39,840     2120 −214,163
2200 100,500    2300 −42,360,000 4010 −1,685,790  5100 2,500,000
5200 42,360,000
```

Nothing was deleted, because nothing was left behind. No closed period was reopened.

## 10. Remaining blockers

```
UNDECIDED  COD refund destination — five options, none chosen
UNDECIDED  Transportation pricing — four fields, nine possible methods
UNDECIDED  Gateway customer charge — accounting and GST treatment
BLOCKED    Gateway customer charge — pre-payment determination, refund treatment
BLOCKED    Razorpay sandbox — credentials not configured
CAVEAT     SAC 998599 / 18% requires CA validation before filing
CAVEAT     If the CA finds a composite supply, that is a model change needing
           re-approval, not a configuration change
```

## 11. Sandbox status

```
BLOCKED — credentials not configured
```

**No Razorpay sandbox call was made in this phase and none is claimed.** What was
verified is that the three secrets can be supplied securely — environment variables
with empty defaults, a live-key refusal, a webhook refused when the secret is absent,
and `.gitignore` plus `NoCommittedSecretsTest` keeping them out of the repository.

### Security audit

Reported as presence/absence and location only; no value is reproduced here.

| Looked for | Found |
|---|---|
| Razorpay secret in source control | **Absent.** Env vars only; `application-local.yaml`, `*.env`, `.keycloak-secret` git-ignored and untracked |
| Card number, CVV, card PIN, UPI PIN | **Absent.** `PaymentAttemptEntity` documents the prohibition; no such column exists |
| OTP storage | In-memory `ConcurrentHashMap` only, never persisted |
| Bank credentials | Only `supplier.account_number` / `supplier.bank_name` — pre-existing **vendor** payout fields, not customers, and not reused |
| Hardcoded payment amount, GST or COD fee | **Absent** from backend and both frontends; one historical comment |

No remediation is required, and no new secret was needed for anything in this phase.
