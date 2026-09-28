# P0-14 — COD service tax: analysis and implementation

## 1. Full application analysis

The analysis found that **almost everything this feature needs already existed**,
and the single most important finding was architectural: `gst_tax_rules` is not an
HSN table. It is a generic tax-rule master.

```
hsn_code VARCHAR(20) NOT NULL      no foreign key to gst_hsn_master
hsn_match_type  EXACT | PREFIX
cgst/sgst/igst/cess_rate_bp        basis points
min/max_price_paise                optional slabs
priority                           precedence — ORDER BY priority DESC
effective_from / effective_to      effective dating
active                             activation
```

`findMatchingRules` already filters on `active`, applies both date bounds and
orders by priority. **A SAC rule is representable today with no schema change, and
no second GST engine was created.** `gst_hsn_master` is goods-only, but nothing
joins to it, so it does not constrain the use of a service code.

Equally decisive: `gst_configuration` + `GstConfigurationService` is a generic,
effective-dated, org-aware key/value store whose stated purpose is "anything that
is a business or statutory decision rather than arithmetic" — with
`SHIPPING_TAX_TREATMENT` as the precedent. That is where the COD decisions belong,
and no new configuration table was created.

Third: the Admin GST tab **already has full tax-rule CRUD** — `GET/POST
/api/master/gst/rules`, `PATCH /rules/{uuid}/toggle`, and a working creation form in
`GstManagement.tsx` with code, match type, priority, slabs, rates as percentages and
effective dates. An administrator can create the COD SAC rule today.

| Area | What already existed |
|---|---|
| GST engine | `GstCalculationService`, returning `RULE_APPLIED` / `NO_HSN` / `NO_RULE` — it already distinguishes an unconfigured rate from a genuine 0% |
| Rounding | `GstRoundingService`, BigDecimal HALF_UP to the paise; no floating point in any financial path |
| Place of supply | `GstIdentityService.isInterState` |
| COD snapshot | Nine `customer_order.cod_fee_*` columns, `cod_fee_tax_rate_bp` nullable so unknown ≠ 0% |
| Invoice | `sales_invoice_item` is a generic tax line — `order_item_id`, `product_uuid`, `product_name`, `quantity`, `unit` all nullable |
| Accounting | `4090 Other Income`, `COD_FEE` source type, `postCodFee` |
| Refund | Five-state `RefundStatus`, frozen per-line GST in `order_return_item`, `GstCreditNoteService` for reversal |
| Transportation | `shipping_value` snapshot, default 0, no pricing logic anywhere |

**What did not exist:** any store-credit or wallet mechanism. Searched code, schema
and docs for store credit, wallet, voucher, gift card, credit balance and loyalty:
the only hits are accounting vouchers and Razorpay's `"wallet"` payment-method
string. `gst_credit_note` is a statutory GST document, not customer credit.

## 2. Reused existing components

`gst_tax_rules` and `findMatchingRules` (the SAC rule, its precedence, its dates,
its activation) · `GstCalculationService` · `GstRoundingService` ·
`GstIdentityService` · `gst_configuration` and `GstConfigurationService` ·
`GstController` /rules and `GstManagement.tsx` · the nine `cod_fee_*` snapshot
columns · `sales_invoice_item` · `AccountingPostingService.postCodFee` ·
`RefundStatus` · `OrderInvoice` · `CartGstPreviewResponse.codCharge` ·
`AccountingResidue`.

No duplicate GST engine, accounting engine, invoice engine or configuration store
was created.

## 3. Files changed

**Backend**

| File | Change |
|---|---|
| `GstConfigurationService` | Five COD keys and typed accessors |
| `CodFeeTaxResolver` | Rewritten: SAC and tax basis from configuration, inclusive/exclusive support, refuses incomplete configuration |
| `PaymentService` | The ₹50 constant removed; charge read from configuration; COD refused when unconfigured |
| `ClientBagServiceImpl` | Quote reports availability instead of quoting an unpriceable charge |
| `CartGstPreviewResponse` | `CodCharge` carries `available`, the service identity, the SAC, the per-head amounts and the basis |
| `SalesInvoiceService` | COD as its own service line, from the frozen snapshot |
| `AccountingPostingService` | Refund reverses the COD heads out of 4090 and its own output-tax heads |
| `RefundService` | Configurable refundability; the store-credit gap named precisely |
| `PaymentRefundEntity` | Two COD refund heads |
| `GstController` + `GstTaxRuleRepository` | Toggle defect fixed; `includeInactive` listing |

**Frontend** — `Payment.tsx`: honours `available`, renders the per-head tax label
and the SAC, disables Place Order when COD cannot be priced.

**Database** — `CodServiceTaxP014.yaml` (registered in `master.yaml`).

**Tests** — `CodServiceTaxPostgresTest` (new, 25), `CodTestConfig` (new),
`cod-charge.spec.js` (rewritten, 3), plus fixture updates in
`FinancialDecisionsPostgresTest`, `SaleAndCollectionPostgresTest`,
`PaymentPostgresTest`, `critical-flow.spec.js`, `idempotency.spec.js`,
`PaymentPage.js`, `db.js`, `global-setup.js`, `global-teardown.js`.

## 4. Database migrations

**`CodServiceTaxP014.yaml`** — two changesets, and deliberately no new table:

| Changeset | Purpose |
|---|---|
| `p014_cod_service_configuration` | Seeds five `gst_configuration` rows. `COD_ENABLED=true`, `COD_FEE_PAISE=5000`, `COD_FEE_REFUNDABLE=false`, and **`COD_FEE_SAC` and `COD_FEE_TAX_BASIS` seeded empty** |
| `p014_payment_refund_cod_heads` | `cod_fee_refunded_paise`, `cod_tax_refunded_paise`, both defaulting to 0 so every existing refund keeps exactly the meaning it had |

Every insert is `WHERE NOT EXISTS`, so re-running changes nothing. No table, index or
tax rule was created; the chart of accounts is still 26 accounts and the tax master
still 8 rules.

## 5. GST/SAC implementation

The charge is a supply of a **service** and is taxed as one. `CodFeeTaxResolver`
asks the existing engine for a rate by **service code and date**, and nothing in it
reads a product's HSN, a product's rate or a product's snapshot.

```
COD_FEE_SAC  ──→  gst_tax_rules (EXACT match)  ──→  GstCalculationService
                   active · effective dates · priority        ↓
                                                    CGST+SGST or IGST
```

Four states, and only the first is usable:

```
RULE_APPLIED        priced; the charge can be invoiced and recognised
NO_SAC_CONFIGURED   no service code — nothing to look a rate up by
NO_BASIS_CONFIGURED not decided whether ₹50 includes the tax
NO_RULE             a code is configured, but the master has no live rule for it
NO_PLACE_OF_SUPPLY  the order cannot be told to be intra- or inter-state
```

**No rate is hardcoded and 18% is assumed nowhere.** The 18% that appears in tests is
seeded by those tests against a service code that exists only for their duration.
A test asserts that no service-code rule and no SAC ship with the application.

## 6. COD configuration

`PaymentService.COD_FEE_PAISE = 5000L` is gone. There is no ₹50 literal left in the
backend or the frontend.

| Key | Shipped | Meaning |
|---|---|---|
| `COD_ENABLED` | `true` | whether COD is offered at all |
| `COD_FEE_PAISE` | `5000` | the approved ₹50 |
| `COD_FEE_SAC` | **unset** | pending tax approval |
| `COD_FEE_TAX_BASIS` | **unset** | pending tax approval |
| `COD_FEE_REFUNDABLE` | `false` | the standing treatment |

All effective-dated, activation-flagged and org-aware through the existing
`GstConfigurationService`, and all manageable without a code release.

## 7. Tax calculation

Intra-state and inter-state come from the existing engine's place-of-supply
decision, not from a second one:

```
intra-state   cod_cgst > 0, cod_sgst > 0, cod_igst = 0
inter-state   cod_cgst = 0, cod_sgst = 0, cod_igst > 0
```

**Tax-exclusive** — the rate is applied to the charge, exactly as to a product line.

**Tax-inclusive** — the taxable value is carved out and **the tax is the
remainder**:

```
taxable = fee × 10000 / (10000 + rateBp)      HALF_UP
tax     = fee − taxable
```

That second line is a defect I found and fixed while testing. Carving the base and
then re-applying the rate rounds twice: ₹50 at 18% carves to 4237, whose tax is 762,
and 4237 + 762 = **4999**. The customer pays ₹50, so the invoice would have declared
₹49.99 — a paisa short on a statutory document. Taking the tax as the remainder makes
base + tax equal the charge by construction. A test sweeps 6 rates × 7 amounts × both
supply types and asserts exact reconciliation on all 84.

Integer paise throughout; HALF_UP via the existing `GstRoundingService`; no
floating point.

## 8. Order snapshot

The nine existing columns were reused; **no column was added**, because the required
facts are already there:

| Required | Column |
|---|---|
| `cod_fee` | `cod_fee_paise` |
| `cod_service_code` / `cod_service_name` | constants — `COD_FEE` / `Cash on Delivery Fee`. Not stored per order: they never vary |
| `cod_sac` | `cod_fee_sac_code` |
| `cod_gst_rate` | `cod_fee_tax_rate_bp` — **nullable**, so unknown ≠ 0% |
| `cod_taxable_amount` | `cod_fee_taxable_paise` |
| `cod_cgst/sgst/igst_amount` | `cod_fee_cgst/sgst/igst_paise` |
| the tax basis used | **derivable**: `taxable == fee` is exclusive, `taxable < fee` inclusive |

Written once at the point COD is chosen and never recomputed, so a return prepared
next year reproduces what the customer was charged. A later configuration change
cannot reach a historical order.

## 9. Invoice

The charge appears as **its own line** on the existing `sales_invoice_item`, with
`hsn_code` holding the SAC, `product_name` "Cash on Delivery Fee", and its own
taxable value, rates and per-head amounts.

Its own line deliberately: shipping is *apportioned into* the product lines as part
of a composite supply, but handling is a separate supply of a service. Folding it
into a garment's line would declare it at the garment's rate and lose the
classification. Every figure is read from the frozen snapshot, and a charge whose tax
was never resolved produces no line, because there is nothing defensible to declare.

## 10. Accounting

Unchanged in structure. Recognition, already in place:

```
Dr 1100 AR                          charge + its tax
   Cr 4090 Other Income                          the charge
   Cr 2100/2110/2120 Output GST                  by head, from the snapshot
```

Refund, added this phase:

```
Dr 4090 Other Income                the charge returned
Dr 2100/2110/2120 Output GST        the tax on it, from the COD snapshot
   Cr 1020 Bank
```

Out of **Other Income**, not Sales, and using whichever heads the *charge* was taxed
under — read from the COD snapshot, never the product's. The refund refuses to post
unless product + GST + COD charge + COD tax equals the money that moved.

## 11. Refund

Refundability is configuration. Off by default, which is the treatment the schema has
expressed since `payment_refund` was created by having no column for the charge. When
on, the charge **and its output tax** both come back — returning the charge while
keeping the tax would leave an output-tax liability with no supply behind it. The
charge belongs to the order, so it is returned once however many payments the order
had.

### COD refund destination — BLOCKED, and not invented

The approved destination is **store credit**, and this application has no
store-credit facility. Building one would mean inventing a wallet — balances, expiry,
transferability, its own accounting — none of which is approved.

So a COD-paid order is refused at eligibility, with a reason that names the **missing
capability** rather than a missing decision. A mixed online/COD order still refunds
against its online instrument. `RefundStatus.APPROVED` is where such a refund will
rest once a destination exists; what the system will not do is send cash somewhere
nobody chose.

## 12. API changes

`GET /api/master/client/bag/gst-preview` — `codCharge` now carries `available`,
`serviceCode`, `serviceName`, `sac`, `feePaise`, `taxablePaise`, `cgstPaise`,
`sgstPaise`, `igstPaise`, `taxPaise`, `taxRateBp`, `taxInclusive`, `taxResolution`,
`totalPaise`. `available` is the field a client must key off.

`POST /api/master/client/payment/initiate` with `COD` — now returns the standard
`BAD_REQUEST` validation error when the configuration is incomplete, naming the
missing piece.

`GET /api/master/gst/rules?includeInactive=true` — new optional parameter; the
default is unchanged.

## 13. Frontend changes

The checkout screen renders the server's figures and nothing of its own. When the
charge cannot be priced it says COD is unavailable and holds the Place Order button,
rather than letting the customer commit to an order the server will refuse. The tax
line appears only when the rate was actually resolved, labelled IGST or GST as the
supply requires, with the SAC shown.

Verified in the real UI at both states:

```
configured    COD Fee ₹50 · COD Fee GST ₹9 · Amount due ₹14,014.20 · Due Now ₹14,014.20
unconfigured  "Cash on delivery is currently unavailable" · no charge shown ·
              Place Order disabled
```

## 14 & 15. Tests and results

| Suite | Before | After |
|---|---|---|
| Backend | 506 | **533**, 0 failures, 0 errors, 0 skipped |
| Cucumber | 87 run, 6 known | **87 run, the same 6** |
| Playwright client | 14 + 1 skipped | **15 passed, 1 skipped** |
| Playwright admin | 15 | **15 passed** |

`CodServiceTaxPostgresTest` — 25 tests: charge from configuration · zero charge ·
intra-state · inter-state · both totals agree · exclusive · inclusive · inclusive
reconciles at 84 rate/amount/supply combinations · the two bases bill differently ·
missing SAC refused · missing basis refused · missing rule refused and not
zero-rated · inactive rule rejected · future rule not used early · expired rule not
used after · overlapping rules use existing precedence · product HSN never used ·
COD rate independent of product rate · service identity · COD off by default ·
refundability off by default · unrecognised basis not accepted · no SAC shipped · no
service rule shipped · configuration effective dates honoured.

Also added: COD refunded when configured refundable (with the 4090 and output-tax
postings asserted) · refunded only once per order · transportation snapshot flows
through without pricing · three Playwright COD tests covering available,
server-driven and unavailable.

No vacuous assertions: every expected figure is computed in the test from the rate
the test configured, never read back from the application.

## 16. Historical integrity

| | Baseline | After the complete suite |
|---|---|---|
| Checksum | `eea1e82cfa93ce16f7963524e3eaf56f` | **`eea1e82cfa93ce16f7963524e3eaf56f`** |
| Orders | 7 | **7** |
| SALE · REVERSAL | 28 · 21 | **28 · 21** |
| Journals · lines | 54 · 187 | **54 · 187** |
| AR | 1,979,633 paise | **1,979,633** |
| Accounts · tax rules | 26 · 8 | **26 · 8** |
| Orphans · leftover payments · closed periods | 0 | **0 · 0 · 0** |
| All 13 account balances | — | **unchanged** |

The checksum moved twice during the phase and was restored both times. Both were
teardown residue from my own failing intermediate runs, and both had a root cause
that was then fixed rather than just cleaned:

1. `FinancialDecisionsPostgresTest` restored a resolver field before its row
   deletions; when that field stopped existing the teardown threw and left 20 orders
   behind. Replaced with configuration-based setup that cannot throw.
2. `PaymentPostgresTest` deleted only `SALE` journals and removed payments **before**
   the journals keyed on them — so once these tests configured COD, four `COD_FEE`
   journals were orphaned per run. Widened, reordered, and given the residue guard.

In both cases the historical 7 orders and their 28 SALE / 21 REVERSAL journals were
verified intact before anything was deleted, and only rows belonging to
`*@automation.veloria.test` orders were removed.

## 17. Known existing failures — not hidden

The six Cucumber failures are unchanged and were not rewritten:

1. `/bag` missing-header 400 vs 401
2. `/bag/add` missing-header 400 vs 401
3. `/favourites` missing-header 400 vs 401
4. place-of-supply 500 for a free-text address
5. the reconciliation check
6. `/products/new-in` empty because the seed data aged past 30 days

Note on (4): this phase made the COD path handle a missing place of supply cleanly —
it now reports `NO_PLACE_OF_SUPPLY` and returns a validation error instead of letting
an `IllegalStateException` become a 500. The *product* path that this scenario
exercises is untouched, so the failure remains as it was.

## 18. Business decisions still pending

```
COD SAC                     PENDING TAX APPROVAL
COD GST rate                PENDING TAX APPROVAL  (configured against the SAC)
₹50 taxable vs inclusive    PENDING TAX APPROVAL
COD refund destination      BLOCKED — store credit approved, no facility exists
Transportation pricing      UNDECIDED
Razorpay sandbox            BLOCKED — credentials not configured
```

Not pending, and not implemented, by decision: no customer-facing gateway charge, so
no gateway customer GST and no gateway customer refund.

## 19. Configuration status

The system can operate COD safely **as soon as two values are set**, with no code
release:

| | Status |
|---|---|
| Configured SAC | ⛔ pending — set `COD_FEE_SAC` |
| Configured GST rate | ⛔ pending — create the rule in Admin → GST against that SAC |
| Tax basis | ⛔ pending — set `COD_FEE_TAX_BASIS` to EXCLUSIVE or INCLUSIVE |
| Effective dates | ✅ honoured, on both the rule and the configuration |
| Active/inactive rules | ✅ honoured — and a withdrawn rule can now be reactivated |
| COD fee amount | ✅ ₹50, configured |
| Refundability | ✅ configurable, off |

**Until the SAC and the basis are set, COD is refused** — the customer is told it is
unavailable rather than charged a fee at a rate nobody approved. That is a deliberate
deployment gate, not a defect.

To enable it: create a rule in Admin → GST for the approved SAC (match type EXACT),
then set `COD_FEE_SAC` and `COD_FEE_TAX_BASIS` in `gst_configuration`.

## 20. Final status

```
IMPLEMENTED BUT BLOCKED BY PENDING BUSINESS/TAX CONFIGURATION
```
