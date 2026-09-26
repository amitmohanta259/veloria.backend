# P0-11 — Financial implementation report

## 1. COD fee accounting

The ₹50 handling charge is now a real accounting event of its own.

```
Dr 1100 Accounts Receivable      charge + its tax
   Cr 4090 Other Income                       the charge
   Cr 2100 / 2110 / 2120 Output GST            as the snapshot states
```

Source type `COD_FEE`, keyed on the order, so `ux_journal_source` makes it
idempotent for free. **Account 4090 Other Income already existed** — no new code
was created and none was invented.

**This is what closes the gap P0-10 reported.** The charge is added when the
customer chooses COD, which is after the sale has been recognised, so the customer
was being asked for ₹50 that no receivable stood behind — and the collection had to
be capped to stop the receivable going negative. Raising the receivable here means
a COD collection settles the invoice exactly:

| | P0-10 | P0-11 |
|---|---|---|
| Receivable raised | sale only | sale **+ charge + its tax** |
| COD collection | capped; excess unaccounted | settles in full |
| The cap | the mechanism | a guard that no longer fires |

The cap is deliberately kept. Crediting more than was ever debited would drive the
receivable negative, and if it ever does fire the excess is logged rather than
absorbed — a silent plug is how books stop meaning anything.

A cancelled order earns no handling income: `postCodFee` takes the same row lock
and applies the same `isSaleEligible` test as a sale.

## 2. COD fee GST

The charge is approved as taxable. The rate is **not** decided here and **not**
hardcoded: `CodFeeTaxResolver` asks the same `gst_tax_rules` master that prices
every product, by service code and by the order's own date, so a historical order
reproduces the rate that applied when it was placed. There is no second GST engine.

Frozen on the order, and never recomputed (§6):

| Column | |
|---|---|
| `cod_fee_paise` | the charge |
| `cod_fee_taxable_paise` | what the tax was charged on |
| `cod_fee_sac_code` | the code it was priced under — never a rate |
| `cod_fee_tax_rate_bp` | basis points, **null when unresolved** |
| `cod_fee_cgst/sgst/igst_paise` | by head, as the engine decided |
| `cod_fee_tax_resolution` | `RULE_APPLIED` / `NO_RULE` / `NO_HSN` / `NOT_CONFIGURED` |

Deliberately separate from the product's `taxableValue` / `cgstAmount` / … : the
product GST snapshot is a statutory per-line figure and must not absorb a charge
that is not a product. **The existing product GST calculation is untouched.**

**`cod_fee_tax_rate_bp` is nullable on purpose. Null is not 0%** — it is an
unanswered tax question, and the accounting refuses to recognise the charge until
it is answered.

### BLOCKED — the rate itself

```
BLOCKER            COD fee GST rate
WHY                gst_tax_rules holds only textile and leather goods chapters
                   (4202, 4203, 61, 62, 63). There is no service code for a
                   handling charge, so the master returns NO_RULE — which it
                   deliberately distinguishes from 0%
CURRENT CODE       CodFeeTaxResolver resolves through the existing engine and
                   records NOT_CONFIGURED; postCodFee posts nothing
DECISION REQUIRED  (a) which SAC, and what rate
                   (b) is ₹50 the charge the customer pays, or the taxable value
                       with tax added on top? The two give a different bill
MINIMUM CHANGE     one row in gst_tax_rules, and set
                   veloria.payment.cod-fee.sac-code
```

Until then the charge still stands at the approved ₹50, the reason its tax is
unknown is recorded on the order, and nothing is posted. Setting that property is a
tax decision, not a configuration convenience — which is why it is unset.

## 3. Gateway fee split

`GatewayFeeSplit` is the one place the 50/50 rule lives:

```
Gateway fee ₹100  →  business expense ₹50  +  customer charge ₹50
```

Taken from the fee **the gateway actually charged**, never from a rate. An odd fee
gives the extra paisa to the business, so the customer is never charged more than
half — the same direction of rounding the partial-payment split already uses.

Persisted on the payment (§29): gross paid, fee charged, business half, customer
half, net settlement. All three derived values are **null while the fee is null**,
because half of an unknown number is not zero.

A test reads the source of the fee path and fails on `0.02`, `2.36`, `0.18`,
`/ 100` or `* 236` appearing on any line that mentions a fee. A hardcoded 2% is
indistinguishable from a correct split until the day the gateway charges something
else, so this is checked in the source rather than in the behaviour.

## 4. Gateway fee accounting — BLOCKED, nothing posted

The facts are recorded. Nothing reaches the ledger, for three separate reasons, any
one of which would be enough:

```
BLOCKER 1          No account for the customer-passed half
WHY                It is a recovery of a cost from the customer. Which account it
                   credits is an accounting decision; 4090 Other Income would be a
                   guess at the classification, not a reading of one
```

```
BLOCKER 2          The customer charge cannot be on the invoice they paid
WHY                Razorpay reports the fee ON the payment — after the customer has
                   already paid. Charging them half of a number that does not exist
                   yet requires either estimating it (a percentage, which §7 and §29
                   forbid) or billing it separately afterwards
DECISION REQUIRED  estimate at checkout and true up, bill on a later invoice, or
                   drop the pass-through and let the business bear the cost
```

```
BLOCKER 3          A capture is not a settlement
WHY                §28. There is no settlement ingestion — no API integration, no
                   credentials — so the event a fee journal should be dated by does
                   not exist in the application
```

And posting only the business half would misstate the bank: the gateway deducts the
whole fee from settlement, so half an entry leaves the bank overstated by the other
half. There is no correct partial posting available.

```
BLOCKER 4          Customer-charge GST treatment
STATUS             UNDECIDED. §10 — the application defines no rule, and none was
                   assumed. The tax master has no service code for it either
```

```
BLOCKER 5          Customer-charge refund treatment
WHY                The existing rule — "the gateway fee is not deducted from the
                   customer's refund" — is about the fee the BUSINESS bore. Whether
                   a charge the customer paid comes back is a different question and
                   the repository holds no answer
```

The existing rule is preserved: `payment_refund` has no gateway-fee column.

## 5. Closed-period accounting (CP-1)

Implemented in full, and the half P0-10 could not do — the deferral — now exists.

```
Order created
   ↓  its period OPEN?
   ├─ yes → post on the order's own date
   └─ no  → keep the order's date as the transaction date
            post on the first day of the next OPEN period
```

`PostingDateResolver` finds the next open period from the `accounting_period`
table, searching from the day after the closed period ends so a deferral can only
ever move **forward**. It never reopens, unlocks or writes to a period: the entry
moves, not the lock. The database trigger `acc_reject_closed_period` still fires on
the posting period, so nothing bypasses it.

**With no open period after a closed one, nothing is posted.** Refusing is the
honest answer — posting into the past would breach the lock, and posting into a
period nobody has set up creates an entry no report knows about. The message says
to open the next period.

Deferral is **opt-in per draft**, not automatic. It applies to the order lifecycle —
a customer's order must not be refused because the accountants closed a month. An
expense voucher or payroll run being backdated into a closed period is a different
situation: someone is backdating a document, and refusing it is still right. The
existing strict behaviour and its unit test are unchanged.

## 6 & 7. Original transaction date and posting date

One new column, and a change of meaning made explicit:

| | |
|---|---|
| `journal_entry.journal_date` | the **posting date** — whose period the entry lands in, and what the trigger checks |
| `journal_entry.transaction_date` | **new.** When the business event happened. Never overwritten |

Backfilled to `journal_date` for every existing entry, because for all 54 of them
the two genuinely were the same day. The historical journals keep every value they
had. `orderPlacedAt` is never touched.

Worked example, from the approved decision:

```
Transaction date   2026-09-28      (order placed; unchanged)
Period 2026-09     CLOSED
Posting date       2026-10-01      (first day of the next open period)
Journal carries    transaction_date = 2026-09-28
                   journal_date     = 2026-10-01
                   period           = 2026-10
```

## 8. Refund workflow

```
return requested → goods received → warehouse verified → refund confirmed → issued
```

Every arrow is a gate, not a stage name. **A return request alone earns no
refund.**

The existing return model is reused, not duplicated: `order_return_request.status`
REQUESTED → VERIFIED, plus the order reaching `RECEIVED` / `RETURNED` /
`PARTIALLY_RETURNED`. Eligibility requires all of: verified, goods back, not
already refunded, and something actually collected.

The final confirmation is an explicit act by an authorised person. Whether a return
is *accepted* — as opposed to received and inspected — is a commercial judgement
the application has no rule for, and a judgement with no rule belongs to a person,
not to a default. `ADMIN_GST` is required: inspecting goods (`CREATE_CREDIT_NOTE`)
is not the same permission as paying a customer back.

**The amount** is the refundable part of the allocation **stored on the payment at
capture** — product plus its GST. Not recomputed from current prices, not taken
from the request, never more than was collected. A price change or a tax-rule change
afterwards cannot alter what is given back.

| Head | Refunded? | Where that policy lives |
|---|---|---|
| Product | ✅ | `PaymentAllocation.refundablePaise()` |
| Its GST | ✅ | the same |
| Transportation | ❌ | absent from `refundablePaise()`; ₹0 today in any case |
| COD fee | ❌ | **non-refundable**, the existing approved rule, preserved |
| Gateway fee | ❌ | no column on `payment_refund` |

## 9. Refund accounting

```
Dr 4010 Sales                    the product value refunded
Dr 2100 / 2110 / 2120 Output GST  the tax refunded
   Cr 1020 Bank                                what actually left
```

An adjustment, **not a deletion**. The sale and the collection both happened and
both stay exactly as posted. The receivable is untouched: the collection already
cleared it, and a refund does not re-create it.

Which tax heads come back is not decided here — it is whichever heads the original
sale credited, read from the order's own snapshot, split in the same proportion with
the last head absorbing the odd paisa. A refund of tax against an order with no tax
recorded is **refused**, not credited to a head that was never charged.

Reconciliation holds: `SALE + COD_FEE − COLLECTION − REVERSAL` is the receivable,
and the refund moves revenue, tax and cash without touching it.

## 10. Refund gateway integration

The P0-7 architecture is reused unchanged. The ordering is the whole design:

1. Eligibility is decided from the database.
2. The refund row is written and **committed as `REQUESTED` before the gateway is
   called** — via `PaymentRefundWriter`, a separate bean so `REQUIRES_NEW` actually
   applies. A refund that succeeds at Razorpay but is lost on the way back is the
   one failure there is no recovering from.
3. Razorpay is called. The backend owns the amount; nothing comes from a request
   body but the idempotency key.
4. Only the gateway's confirmation moves it to `COMPLETED`.
5. Only then is anything posted, and the posting is idempotent on the refund.

Not transactional around the gateway call: an HTTP call inside a database
transaction holds a connection for as long as the gateway takes, and a rollback
cannot un-send money.

**Gateway failure** (§27): status `FAILED`, no gateway refund id, **nothing
posted** — the books say no money moved, because none did. A later attempt may
succeed, and only then is it accounted for.

**Idempotency**, three layers deep: the request key, the partial unique index
`ux_payment_refund_per_return` (one refund per return, failures excepted), and
Razorpay's own `receipt` deduplication. Twelve concurrent requests produce exactly
one refund and exactly one accounting effect; the losers converge on the winner
rather than erroring, because a caller asking about a refund already on its way
should be told about that refund.

**Destination**: `RAZORPAY_SOURCE` — back to whatever instrument paid, which is the
gateway's behaviour and not a routing decision made here. Recorded on the refund.

### BLOCKED — COD refund destination

```
BLOCKER            Where a cash-on-delivery refund goes
WHY                Cash has no electronic destination. The only bank-account
                   columns in the entire schema belong to `supplier` — there is no
                   customer bank account, IFSC, or UPI id anywhere
CURRENT CODE       eligibilityFor refuses, and says it is an open decision
DECISION REQUIRED  the route — bank transfer against collected details, store
                   credit, or a cash refund at a counter
MINIMUM CHANGE     the decision, then somewhere to hold the destination and a
                   payout path for it
```

## 11. GST

Unchanged. No second engine, no rate computed anywhere outside the tax master, no
calculation from frontend data, no recalculation of a historical rate. The COD
charge routes through the **existing** `GstCalculationService` by service code; the
refund reads the **existing** snapshot. The product GST path was not touched.

## 12. Transportation

**Still UNDECIDED. Nothing was invented** — no formula, rate table, distance,
weight, pincode or courier logic. `shipping_value` is 0 on every order.

The model is not hardcoded to zero: `OrderInvoice` reads the column, the waterfall
settles transportation in full before the product, and a refund would read the
stored snapshot. A non-zero value flows through without further change. Refund of
transportation is ₹0 because the stored value is ₹0 — not because of a rule.

## 13. Historical integrity

Measured after the **complete** suite — 494 backend, 87 Cucumber, 27 Playwright —
not merely after implementation:

| | Baseline (P0-10) | After the full P0-11 suite |
|---|---|---|
| Journal checksum | `eea1e82cfa93ce16f7963524e3eaf56f` | **`eea1e82cfa93ce16f7963524e3eaf56f`** |
| Orders | 7 | **7** |
| SALE | 28 (7 posted, 21 reversed) | **28** |
| REVERSAL | 21 | **21** |
| `COD_FEE` · `PAYMENT_COLLECTION` · `REFUND` | 0 · 0 · 0 | **0 · 0 · 0** |
| Journals · lines | 54 · 187 | **54 · 187** |
| AR (1100) | 1,979,633 paise | **1,979,633** |
| All 13 account balances | — | **unchanged** |
| Orphaned journals · leftover payments · periods left closed | — | **0 · 0 · 0** |

Reproducible, not a claim:

```sql
SELECT md5(string_agg(id||':'||journal_number||':'||source_type||':'
                      ||COALESCE(source_id::text,'-')||':'||status||':'
                      ||total_debit_paise, '|' ORDER BY id))
FROM journal_entry;
-- eea1e82cfa93ce16f7963524e3eaf56f
```

No historical journal was modified, deleted or rewritten. No closed period was
reopened. No `orderPlacedAt` was changed. The one schema change to `journal_entry`
is additive, and `transaction_date` was backfilled from `journal_date`, which for
every existing entry was the same day.

### The checksum did move during this phase, and was restored

After the first full regression it read `6ffa0d0d…` with 93 journals and 36 orders.
Every extra row was **residue from my own test's earlier failing runs**: the
teardown hit one bad DELETE — a mistyped credit-note column — and aborted, leaving
29 orders and 39 journals behind. Two things followed:

1. The residue was identified positively before anything was deleted. The seven
   historical orders were confirmed to still hold exactly 28 SALE and 21 REVERSAL
   journals, and the arithmetic was made to close first — 93 − 39 = 54 — so that
   only rows belonging to `*@automation.veloria.test` orders were removed. The
   checksum came back to `eea1e82c…` exactly.
2. **The teardown was fixed, not just re-run.** Every statement is now attempted
   independently and failures are collected and asserted at the end. Fail-fast is
   wrong for a teardown: a cleanup that gives up half way is worse than one that
   reports what it could not do. The period restore also moved to the front and is
   unconditional — a period left closed silently deferred four later tests'
   postings, which is how one bad DELETE produced four unrelated failures.

## 14. Test fixtures (§32)

Every fixture that creates an order now removes its sale, its COD fee, its
collections, its refunds, its returns and its credit notes — journals **before** the
payment and refund rows they are keyed on.

| Fixture | Change |
|---|---|
| `TestData.removeOrderGraph` (Cucumber) | `SALE` → `SALE, COD_FEE`; refund journals added before the refund rows |
| `fixtures/db.js` (Playwright) | The same, **and a latent ordering bug fixed**: it deleted `payment_attempt` and `payment_refund` *before* the journals keyed on them, so the subqueries matched nothing and the journals would have been orphaned. It had never fired because Playwright captures no payments |
| `OrderIdempotencyPostgresTest` | `COD_FEE` and `REFUND` added |
| `CheckoutInventoryPostgresTest` | the same |
| `SaleAndCollectionPostgresTest` | the same; `invoiceTotal` now includes the COD fee tax |
| `FinancialDecisionsPostgresTest` | new; failure-tolerant teardown as above |

## 15. Connection pool (§33)

The P0-10 fix is preserved and extended. All **12** integration tests carry a
byte-identical property set, so Spring caches **2** contexts rather than one per
variation. The new test deliberately does **not** add a property for the COD service
code — it sets the field on the bean for the duration of a test instead, because a
new property would fork a third context and a third pool for no gain.

The pool was not increased. Budget, unchanged from P0-10:

```
instances × poolSize  must stay well below max_connections (100)
20 per instance → 5 instances reach 100 with no headroom
order creation opens a second transaction, so size for ~2× the
concurrent checkout rate
```

## 16. Test results

| Suite | Baseline (P0-10) | After P0-11 |
|---|---|---|
| Backend | 451 / 451 | **494 run, 0 failures, 0 errors, 0 skipped** |
| Cucumber | 87 run, 6 known failures | **87 run, the same 6** |
| Playwright client | 12 passed, 1 skipped | **12 passed, 1 skipped** |
| Playwright admin | 15 passed | **15 passed** |

43 new backend tests:

| | |
|---|---|
| `FinancialDecisionsPostgresTest` | 28 — COD fee, gateway fee, closed period, refunds, end to end on real PostgreSQL |
| `PostingDateResolverTest` | 9 — open, closed, LOCKED, several closed in a row, unknown period, nowhere to go, never backwards |
| `GatewayFeeSplitTest` | 6 — the approved example, odd fees, exhaustiveness over 5,000 values, refusals, and the no-hardcoded-percentage source scan |

Coverage of the required list (§34) — COD order · ₹50 charge · Other Income ·
taxable · delivery collection · charge included · duplicate collection · gateway
fee · 50% business · 50% customer · no hardcoded percentage · open-period SALE ·
closed-period order · next-open-period posting · transaction date retained ·
posting date correct · payment still settles AR · COD still settles AR · return
created · warehouse receipt · warehouse verification · eligibility · calculation ·
partial-payment refund · full refund · original destination · duplicate refund ·
concurrent refund · gateway refund failure · successful gateway refund · refund
accounting — **all covered**. COD refund is covered as a **refusal**, which is the
approved-decision-shaped answer, not a skip.

### The six known Cucumber failures — unchanged, not hidden

Three missing-header 400-vs-401 (`/bag`, `/bag/add`, `/favourites`); a
place-of-supply 500 on a free-text address; the reconciliation check; and
`/products/new-in` returning `[]` because the seed data has aged past its 30-day
window. None fixed, none rewritten, none related to this work.

### One transient Playwright failure, reported not papered over

`ORD-011` (bag → payment navigation) timed out at 10s on the first client run,
immediately after the backend restarted, and passed in 9.3s on re-run and in the
full suite afterwards. First-request latency on a cold JVM, the same class of
flake seen in P0-8. No test was altered.

## 17. Security

Re-verified, unchanged from P0-7:

| | |
|---|---|
| Razorpay secret in source, frontend or git | **none** — the only `rzp_live_` occurrence is the code that rejects it |
| `KEY_SECRET` / `WEBHOOK_SECRET` in frontend | **none** |
| Card, CVV, VPA, PIN columns | **none** in the entire schema |
| Amount from the browser | never — `OrderInvoice` is the only source |
| Refund amount from the request | never — only the idempotency key comes from the caller |
| Refund authorisation | `ADMIN_GST`, enforced at the service boundary |
| Float or double in any financial path | **none** |

## 18. Sandbox

```
BLOCKED — CREDENTIALS NOT CONFIGURED
```

`RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` and `RAZORPAY_WEBHOOK_SECRET` are unset
and no `.env` exists. No CARD, UPI, live webhook, live signature, refund or
refund-failure test has been run against Razorpay, and none is claimed. The gateway
is stubbed where tests needed one. Live keys are still refused at startup.

## 19. Remaining blockers

```
BLOCKED   COD fee GST rate + inclusive/exclusive          §2
BLOCKED   Gateway customer charge: account, GST, timing, refund   §4
BLOCKED   COD refund destination                          §10
BLOCKED   Razorpay sandbox — credentials                  §18
UNDECIDED TR-1 / TR-2 transportation pricing              §12
```

One follow-up that is not a blocker but will become wrong the moment the COD tax
decision lands: the client's payment screen renders the COD line as a browser-side
literal `+ 50`. The amount the customer is actually charged is server-computed and
correct, so the two agree today only because the charge carries no tax. When it
does, the display will understate it, and that line should read the server's
figure.

## 20. Production readiness

| | |
|---|---|
| **CODE COMPLETE** | For what is approved: COD fee as Other Income with a frozen tax snapshot, closed-period deferral with transaction and posting dates, the refund workflow end to end for online payments, and the gateway fee split recorded. **Not** for gateway fee posting, the COD fee's tax, or COD refunds |
| **TEST COMPLETE** | 494 backend · 87 Cucumber · 27 Playwright, with concurrency and idempotency proven against real PostgreSQL |
| **SANDBOX VALIDATED** | **No.** No credentials; no Razorpay call has ever been made |
| **PRODUCTION READY** | **No.** Five blockers open, no settlement ingestion, no COD refund route, and the COD charge's tax unresolved |
