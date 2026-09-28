# P0-13 — COD GST implementation report

## A. Root cause

P0-14 left COD functionally complete but **refusing every order**, because the three
things needed to tax a service were all absent:

```
gst_tax_rules      8 rules, 0 with a service code
COD_FEE_SAC        (unset)   — pending tax approval
COD_FEE_TAX_BASIS  (unset)   — pending tax approval
```

The refusal was correct: charging a customer for a taxable service while declaring no
tax on it is a misdeclaration. What was missing was the tax determination, not code.

This phase supplies it — **as configuration**, so the classification stays
changeable by whoever validates it.

### A second root cause, found during implementation

The invoice total added the charge **and** its tax:

```java
productPaise + gstPaise + transportPaise + otherChargesPaise
    + codFeePaise + codFeeTaxPaise
```

Correct under EXCLUSIVE, wrong under INCLUSIVE — which is exactly the basis this
phase turns on. It would have quoted the customer ₹50 at checkout and billed
₹57.63. The checkout quote used the right figure, so the two disagreed.

Fixed by the one expression that is correct under both bases:

```
gross = taxable + tax
```

Exclusive: 5000 + 900 = 5900. Inclusive: 4237 + 763 = 5000. A historical order whose
charge was never taxed has taxable equal to the fee and no tax, so it still grosses to
the fee — unchanged for every order already placed.

## B. GST research basis

See **`docs/p0-13-cod-gst-tax-treatment.md`** for the full treatment. In short: the
charge is a supply of a service, not of goods, so it needs a SAC. It is not
transport, not a financial service and not a commission; what remains is a support
service incidental to the sale, and 998599 is the residual heading for support
services not classified elsewhere.

> **SAC 998599 is the configured application classification and must be validated by
> the business's GST adviser/CA before actual GST filing if the COD charge is
> intended to be treated as a separate service supply.**

The software applies a configured classification consistently and freezes it onto
each order. It does not determine legal treatment, and this report does not claim
that the application's design is evidence of one.

## C. SAC configuration

**§9's preference was followed: the schema was improved, safely and minimally.**
`gst_tax_rules` was already generic — its code column has no foreign key to the goods
HSN master — but it could not say *which kind* of code a rule classified. One additive
column now does:

```
gst_tax_rules.tax_code_type   VARCHAR(8) NOT NULL DEFAULT 'HSN'
                              HSN — goods · SAC — services
```

Every existing rule defaults to `HSN`, so nothing changes meaning. The code column
is **not** renamed: a dozen queries read it, and the rename would buy nothing the
type column does not give.

**This is more than a label.** Rule resolution now filters on the type, so:

- a goods rule cannot price a service — even if someone configured `COD_FEE_SAC` to
  `6211`, the lookup would find nothing and COD would be refused;
- a SAC rule cannot price goods.

```
findMatchingRulesOfType(code, type, price, date)
findMatchingRules(code, price, date)   → delegates with type = HSN
```

One engine, one master, one precedence rule — the existing `ORDER BY priority DESC`
with active-only and both date bounds. No second engine, and no second precedence
algorithm.

## D. GST rate

```
SAC          998599      tax_code_type = SAC      match type = EXACT
CGST          9%   (900 bp)
SGST          9%   (900 bp)
IGST         18%  (1800 bp)
Effective from  2017-07-01
Effective to    (open)
Active          true
Priority        300
```

A row in `gst_tax_rules`. **No rate appears in Java or TypeScript** — a test reads
the source of the fee path and fails on a percentage literal.

`2017-07-01` because every existing rule in this master carries it, being when GST
came into force. A later date would leave a gap: an order dated before it would find
no rule, and COD would be refused for that order.

`EXACT` because a SAC must match exactly — a `PREFIX` rule on `998599` would also
catch `9985991` and anything else beginning with it.

A future rate change is a new row with a later `effective_from` and an
`effective_to` closing this one. Historical orders keep the rate they were charged
at, because it is frozen onto each order.

## E. Tax basis

```
COD_FEE_PAISE        5000
COD_FEE_TAX_BASIS    INCLUSIVE
COD_FEE_SERVICE_NAME Cash on Delivery Handling Fee
COD_FEE_SAC          998599
COD_FEE_REFUNDABLE   false
```

INCLUSIVE: the customer-facing charge is ₹50 and the GST is inside it. They see ₹50
and pay ₹50.

Still configuration, not code — a later tax opinion changes a row. And still no
default: an unrecognised basis resolves to `NO_BASIS_CONFIGURED` and refuses, because
the two bases give the customer a different bill.

## F. The inclusive calculation

```
gross   = 5000 paise
rateBp  = 1800

taxable = round_half_up(5000 × 10000 / 11800) = round_half_up(4237.288…) = 4237
tax     = gross − taxable                      = 5000 − 4237            =  763
```

```
₹42.37  taxable value
₹ 7.63  GST
──────
₹50.00  charged
```

**The tax is the remainder, not the rate re-applied.** 18% of 4237 is 762, and
4237 + 762 is 4999 — a paisa short of what the customer pays. Taking the remainder
makes

```
taxablePaise + taxPaise == grossPaise
```

true by construction. Integer paise throughout; HALF_UP via the existing
`GstRoundingService`; no floating point.

Swept over five rates (0%, 5%, 12%, 18%, 28%) × seven amounts × both supply types —
70 combinations, all reconciling exactly.

### CGST / SGST split

```
intra-state   CGST = totalTax / 2          → 763 / 2 = 381
              SGST = totalTax − CGST       → 763 − 381 = 382
              IGST = 0

inter-state   IGST = totalTax = 763
              CGST = SGST = 0
```

**The odd paisa goes to SGST, always.** Deterministic and fixed — rounding each head
independently loses or creates a paisa, and a varying rule would make the same charge
reconcile differently on different days. `CGST + SGST == totalTax` is asserted at
every rate and amount in the sweep.

## G. Place of supply

Taken from the **order's own snapshot** — `order.placeOfSupply`, falling back to
`order.buyerStateCode`. Never from the product, the HSN, the inventory or the
catalogue. For an unregistered customer that is the recipient's address on record.

A missing place of supply is a **validation error, never a 500**:

```
taxResolution = NO_PLACE_OF_SUPPLY      HTTP 400
"The order has no place of supply, so it cannot be determined whether the
 charge attracts CGST and SGST or IGST."
```

The validation was **not** relaxed. Both protections stand: a real order carries a
place of supply, and an order without one is refused cleanly. The regression test
`missingPlaceOfSupplyDoesNotReturn500` pins the whole outcome — a 400, the message,
and no order mutation, no journal, no payment attempt.

An unrecognised state code that differs from the seller's is treated as inter-state.
The engine compares codes rather than validating them against the state master, and
inter-state is the safer direction: IGST on an unknown destination is correctable,
whereas a wrong CGST/SGST split has been apportioned to the wrong state.

## H. Accounting

Verified live against the running application:

```
Dr 1100  Accounts Receivable      5000
   Cr 4090  Other Income                  4237
   Cr 2120  Output IGST                    763      (inter-state order)
```

Intra-state credits 2100 and 2110 with 381 and 382 instead. Every account already
existed; none was created.

**₹50 is not credited to Other Income with tax added on top.** Only the taxable value
is income; the rest is an output-tax liability.

### AR and collection

```
invoice total                 1,400,520   product + GST + transport + COD gross
AR before delivery            1,400,520   equal, verified live
COD collection                1,400,520   Dr 1010 Cash / Cr 1100 AR
AR after delivery                     0
```

No capping was needed, because the receivable is now correct. The cap remains only as
a floor against a negative receivable.

## I. Invoice

Its own line, its own service code, from the frozen snapshot:

```
  Description                        SAC      Taxable    IGST    Total
  ────────────────────────────────────────────────────────────────────
  Midnight Silk Maxi                 6211    12,460.00     …        …
  Cash on Delivery Handling Fee    998599        42.37    7.63    50.00
```

Not merged into a product line and not apportioned across them the way shipping is —
shipping is incidental to a composite supply of goods, whereas handling is a separate
service with its own classification.

## J. Frontend

No COD literal remains in the React source. The screen renders
`codCharge.feePaise`, `codCharge.taxPaise` and `codCharge.totalPaise` from the
server, labels the tax line IGST or GST as the supply requires, and shows the SAC.
When the charge cannot be priced it says COD is unavailable and holds Place Order.

The quote now also carries `placeOfSupply`, so a client can show which jurisdiction
the charge was taxed in without inferring it.

Three Playwright tests hold it: the displayed figures equal the server's, the page
follows a changed server figure (₹77.77), and an unavailable charge is refused
on-screen. **COD-001's arithmetic assertion was itself wrong and was fixed** — it
asserted `total = grandTotal + fee + tax`, the exclusive formula, which under
INCLUSIVE demanded the ₹57.63 bill the backend fix had just removed. It now asserts
`taxable + tax`, correct under both bases, plus the basis-specific relationship.

## K. Tests

| Suite | Before | After |
|---|---|---|
| Backend | 533 | **540 run, 0 failures, 0 errors** |
| Cucumber | 87 run, 6 known | **87 run, 0 skipped, the same 6** |
| Playwright client | 15 + 1 skipped | **15 passed, 1 skipped** |
| Playwright admin | 15 | **15 passed** |

The six Cucumber failures are unchanged in count, identity and message. They were
compared line by line against the P0-14 baseline, not just counted.

New or rewritten:

| Test | Proves |
|---|---|
| `theApprovedConfiguration` | ₹50 → ₹42.37 + ₹7.63 against the **shipped** rule, both supply types, 381/382 split |
| `inclusiveCodChargeBillsAndClearsExactly` | The production basis end to end: snapshot, AR = invoice, ₹42.37 income vs ₹7.63 tax, collection, AR → 0 |
| `missingPlaceOfSupplyDoesNotReturn500` | 400 not 500; no order mutation, no journal, no payment attempt |
| `missingPlaceOfSupplyIsAValidationError` | null, empty and blank place of supply |
| `missingSellerStateIsAValidationError` | the same for the seller side |
| `unknownStateCodeIsTaxedByComparisonNotByLookup` | an unrecognised code is inter-state, not an error |
| `shippedConfigurationCarriesTheApprovedValues` | the migration seeds exactly the approved values |
| `shippedCodRuleIsTheApprovedOne` | one SAC rule, 9/9/18, EXACT, active, open-ended |
| `codeTypesAreNotMixedUp` | no 99xx rule typed HSN, and no non-99xx typed SAC |
| `inclusiveBasisReconcilesAtEveryRate` | extended to 0/5/12/18/28% — 70 combinations |

Also fixed, both test-side defects surfaced by running the suites rather than
reasoning about them:

- the `invoiceTotal` test helper in two classes encoded the pre-fix `fee + tax`
  formula, which would have overstated an inclusive invoice. It now uses
  `taxable + tax`;
- `PaymentPage.deliveryAddressHeading` matched `Delivery Address` as a substring,
  so it also matched the placeholder `Select a delivery address` whenever no
  address had loaded yet — two matches, and Playwright's strict mode then reported
  an ambiguous locator instead of the address being absent. Now `exact: true`.
  It appeared as an intermittent ORD-011 failure and was misleading in exactly the
  place a COD test depends on the same page object.

## L. Historical checksum

| | Baseline | After the complete suite |
|---|---|---|
| Checksum | `eea1e82cfa93ce16f7963524e3eaf56f` | **`eea1e82cfa93ce16f7963524e3eaf56f`** |
| Orders | 7 | **7** |
| SALE · REVERSAL | 28 · 21 | **28 · 21** |
| Journals · lines | 54 · 187 | **54 · 187** |
| AR | 1,979,633 paise (₹19,796.33) | **1,979,633** |
| Accounts | 26 | **26** |
| Tax rules | 8 | **9** — the approved SAC rule |
| Orphans · leftover payments · closed periods | 0 | **0 · 0 · 0** |

No historical journal, order or invoice was modified. No closed period was reopened.
No account was created. The only intended change to reference data is the one new
tax rule and the configuration values.

**Historical orders are never recalculated.** The rate, taxable value and amounts are
frozen on each order; a later rate change or a reclassification after CA review
cannot alter an order already placed.

## M. Remaining tax/compliance caveats

```
CAVEAT    SAC 998599 requires GST adviser / CA validation before filing
          Configured, not determined. The alternatives and what each would
          change are tabulated in p0-13-cod-gst-tax-treatment.md §10.

CAVEAT    Composite-supply question
          If the adviser concludes the charge is part of a composite supply
          with the goods, that is NOT a configuration change — the charge
          would cease to be a separate service line and the invoice and
          accounting presentation would need re-approval. Flagged as a stop
          condition rather than accommodated speculatively.

OPEN      COD refund destination — store credit approved, no facility exists
UNDECIDED Transportation pricing
BLOCKED   Razorpay sandbox — credentials not configured
```

> **Corrected in P0-14.** "store credit approved" above is wrong. The authoritative
> register records D-COD-REFUND as **UNDECIDED**, with store credit as one of four
> options; no approval exists. The claim was repeated from an earlier report and from
> a code comment, and the code's customer-facing refusal message has been corrected.
> See `p0-14-decision-register.md`.

None of these blocks COD from operating. The first is the one that matters before
filing.

### Two things found while testing that this phase deliberately did not change

**1. The product GST preview still returns 500 when the shopper has no address.**
One of the six preserved Cucumber failures — *"A new shopper with no address yet
gets a handled answer, not a server error"* — is a 500 carrying the very message
this phase was told never to allow:

```
GstIdentityService.isInterState  →  IllegalStateException
"Place of supply is unknown — cannot determine CGST/SGST vs IGST"
```

It is reached through the **product** GST path on `/client/bag/gst-preview`, not the
COD fee path. The COD fee path was fixed: it catches this and answers
`NO_PLACE_OF_SUPPLY` with a 400, proven by `missingPlaceOfSupplyDoesNotReturn500`.
The product path was left alone for two reasons — it is one of the six failures this
phase was instructed not to modify, and fixing it means deciding what a preview
should show a shopper who has no address yet (an untaxed subtotal? a refusal? a
default state?), which is a business rule and not mine to invent.

**Flagged rather than fixed, and it is a real defect**: it predates this phase
(present in the P0-14 baseline and every run since, verified by comparing the
failure messages, not the counts), and the same family of bug in the COD path was
this phase's own subject.

**2. One unexplained, non-reproducing client-suite failure.** On the first full
Playwright run of this phase's final regression, COD-001 and COD-003 both stopped at
`/bag`: the bag rendered its item and totals, Proceed to Checkout was present and
enabled and received the click, and the SPA did not navigate for the full 10-second
window. It did not recur in four subsequent runs (the spec serially, then three full
suites). Ruled out by inspection: stock on the fixture product (100 units on the
catalogue products, 10 on the size in the bag), the seeded delivery address (present,
and the product GST rendered from it), and the COD configuration (`global-setup`
verified it in that same run). No cause was established, so none is asserted here.
It is recorded because the run happened, not because it is understood.

> **Explained and fixed in P0-14.** `ProductPage.goto()` returned as soon as Buy Now
> was painted, but the button reads state filled in by a second request
> (`/products/{id}/sizes`, which auto-selects a size), and the view refuses to add
> anything while a product has sizes and none is chosen. A click landing in that
> window does nothing — which is both symptoms. `goto()` now waits for that response.
> See `p0-14-refund-and-gst-preview-report.md` §7.
