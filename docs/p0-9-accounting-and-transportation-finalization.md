# P0-9 — Accounting and transportation finalization

## 1. Executive summary

**Nothing could be finalized, because nothing has been approved.**

P0-9 asks for decisions to be recorded and then implemented. The repository was searched for any
authoritative approval of the five open questions. There is none: a `grep` for `DECIDED` across
every document returns only the word *UNDECIDED* and my own earlier statement that no decision is
approved. There is no approval, no owner and no date anywhere.

| Decision | Status |
|---|---|
| D35 — SALE recognition timing | **UNDECIDED** |
| Closed-period order accounting | **UNDECIDED** |
| COD SALE timing | **UNDECIDED** |
| D36 — historical SALE/REVERSAL cleanup | **UNDECIDED** |
| Transportation pricing | **UNDECIDED** |
| Gateway fee ownership | **UNDECIDED** |
| Sandbox validation | **BLOCKED — credentials not configured** |

So no accounting entry was posted, no transportation rule was invented, no historical journal was
touched, and no speculative schema was created.

**What was done instead.** One genuine gap was found that needs no decision: the partial-payment
*balance* collection — the second half — had never been exercised end to end. Only the quoted
amount was covered. That is the collection that has to work out what is genuinely still owed, and
getting it wrong either short-changes the business or charges a customer twice for the same goods.
Six tests now prove the whole cycle, and they pass.

Historical data is provably untouched: the journal checksum taken before the work matches the one
taken after, exactly.

## 2. Decision register

| ID | Decision | Status | Owner | Blocks |
|---|---|---|---|---|
| **D35** | Is SALE recognised in real time or by batch? | `UNDECIDED` | Finance | Collection accounting, AR settlement, refunds, reconciliation |
| **CP-1** | What happens when an order is placed into a closed period? | `UNDECIDED` | Finance + Ops | Real-time SALE (a prerequisite of Option A) |
| **D17** | What event means COD cash was collected? | `UNDECIDED` | Finance + Ops | COD accounting |
| **COD-1** | When is SALE recognised for COD? | `UNDECIDED` | Finance | COD accounting |
| **D36** | Are the 28 historical SALE / 21 REVERSAL journals to be cleaned up? | `UNDECIDED` | Finance | Nothing technical; an audit question |
| **TR-1** | How is transportation priced? | `UNDECIDED` | Business | Customer charge, refunds, invoice total |
| **TR-2** | Are outbound and return priced separately? | `UNDECIDED` | Business | Transportation schema |
| **D24/GF-1** | Who bears the gateway fee, and how is it recorded? | `UNDECIDED` | Finance | Fee accounting, refund arithmetic |
| **D19** | When is AR cleared? | `BLOCKED` on D35 | Finance | Collection posting |
| **D15** | Refund trigger and postings | `BLOCKED` on D19 | Finance + Tax | Refund workflow |
| — | 50% waterfall and its allocation priority | **APPROVED, IMPLEMENTED** | — | — |
| — | Gateway fee excluded from customer refunds | **APPROVED, IMPLEMENTED** *(structurally)* | — | — |
| — | `PAYMENT_FAILED` ≠ SALE | **APPROVED, IMPLEMENTED** | — | — |
| — | Sandbox validation | `BLOCKED` — no credentials | — | All live gateway tests |

## 3. D35 — SALE recognition timing · `UNDECIDED`

### Verified current behaviour

| Question | Answer, read from the code |
|---|---|
| Accounts | `Dr 1100 AR` / `Cr 4010 Sales` / `Cr 2100·2110·2120 Output GST` |
| Trigger | `postSale` — **one caller**, `backfill()` |
| Date | `dateOf(order.getOrderPlacedAt())` — **order placement** |
| Eligibility | `isSaleEligible()` excludes only `CANCELLED` and `PAYMENT_FAILED`; the code states this is *"deliberately not a payment test"* |
| Duplicate protection | `ux_journal_source` (partial, `status='POSTED'`) + an any-status existence check |
| Period control | every posting path goes through `assertPeriodOpen` |

So today the system is **Option B**: the sale conceptually exists at order placement, and the
journal is written whenever backfill runs.

### The two options, with their real consequences

| | **Option A — real-time** | **Option B — batch (today)** |
|---|---|---|
| When the journal is written | At checkout | When backfill runs |
| Journal content | Identical — same accounts, same amounts, **same date** | Identical |
| Payment collection | Can settle AR, because AR exists | **Cannot** — no AR to settle |
| Cancelled order | Sale posted, then reversed → audit trail shows both | Never posted → nothing in the books |
| Closed period at placement | **Blocks the order, or needs CP-1** | Backfill simply skips it |

**The decisive detail:** the journal Option A writes is byte-identical to the one Option B writes —
same accounts, same amounts, and the same date, because the sale is dated at `orderPlacedAt` either
way. Option A is therefore a change of *trigger*, not of accounting policy. What it does change is
what the books show for an order that is later cancelled: sale-plus-reversal instead of silence.

That difference, and CP-1 below, are why this still needs Finance rather than an engineering call.

**Not implemented. Stop condition 1.**

## 4. Closed-period accounting · `UNDECIDED`

If Option A is chosen, an order placed while the accounting period is closed has to do something.
`assertPeriodOpen` would throw inside checkout.

| Option | Consequence |
|---|---|
| A — reject the order | Commerce stops whenever the books are closed. Almost certainly unacceptable |
| B — allow the order, defer the accounting | Needs a deferred-posting queue that does not exist |
| C — post into the next open period | Changes the sale's date, which contradicts `orderPlacedAt` dating |
| D — other | — |

**No option selected. Stop condition 2.** Existing period protection is untouched.

## 5. COD accounting · `UNDECIDED`

COD has no capture event. Two separate questions:

- **COD-1** — when is the SALE recognised: at order creation, at delivery, or elsewhere?
- **D17** — what event means cash was actually collected?

Neither can be answered from the code. `DELIVERED` records no money, is settable by any
`ADMIN_GST` holder, and there is no remittance or bank-credit concept. The P0-7 brief was explicit
that `DELIVERED` must not be assumed to equal `CASH_COLLECTED`.

**Not implemented. Stop condition 3.**

## 6. Historical SALE / REVERSAL · `UNDECIDED` → left untouched

Verified present and unchanged: **SALE × 28, REVERSAL × 21**, across 7 orders — four sale journals
per order, three reversed, one standing. The net position is correct; the duplicates are the
documented residue of a defect P0-5B has since fixed.

**No journal was modified, deleted or rewritten.** Proven by checksum (§18). D36 is an audit
question for Finance, not a code change.

## 7. Transportation pricing · `UNDECIDED`

Searched the repository again rather than trusting P0-8:

| Searched for | Found |
|---|---|
| `shippingRate`, `shippingFee`, `deliveryCharge`, `weightBased`, `shipping_config`, `delivery_pricing` | **0 files each** |
| `courier` | 3 matches — all prose in comments |
| `pincode` | address fields only; used for GST place-of-supply, never pricing |
| Shipping / rate / courier tables | **0** |
| Anything writing `shipping_value` at checkout | **nothing** |
| `SalesInvoiceService` | **copies** `order.getShippingValue()`; does not compute it |
| Orders with `shipping_value > 0` | **0** |

> ### BLOCKED — TRANSPORTATION PRICING DECISION REQUIRED
>
> ```text
> Outbound transportation fee:      ____________________
> Return transportation fee:        ____________________
> Customer transportation charge:   ____________________
> Calculation method:               ____________________
>   FIXED · PINCODE · DISTANCE · WEIGHT · COURIER · PRODUCT · SELLER · SLAB · OTHER
> ```

**Nothing was implemented and no schema was added.** No rate table, no distance, weight or pincode
algorithm, no courier pricing. The ₹0 behaviour is unchanged. **Stop condition 4.**

## 8. Transportation financial model — design only

When TR-1 and TR-2 are answered, three concepts must stay distinct, because collapsing them makes
refunds unanswerable:

```text
OUTBOUND_TRANSPORTATION_FEE     what it cost to send
RETURN_TRANSPORTATION_FEE       what it cost to bring back
CUSTOMER_TRANSPORTATION_CHARGE  what the customer was actually charged  ← the only one refunds see
```

Today there is one column, `shipping_value`, holding the customer charge. Adding the other two
before knowing what fills them would be speculative schema built on an unmade decision — so they
were **not** added. Nothing is blocked by waiting: the allocation waterfall already handles
transportation correctly at any value, zero included, and historical orders keep their own snapshot
because the allocation is frozen per payment (§10).

## 9. Gateway fee · `UNDECIDED`

| Question | Status |
|---|---|
| Who bears it — business or customer? | **UNDECIDED** |
| How is it recorded? | `gateway_fee_paise`, a separate column, `NULL` until settlement data supplies it. **No rate is assumed** |
| Effect on refunds | Excluded from customer refunds — already structural: `refundablePaise()` returns product + GST only, and `payment_refund` has no column for it |
| Account | **`NEW ACCOUNT — REQUIRES ACCOUNTING APPROVAL`** — verified: no account in `chart_of_accounts` matches *gateway* or *clearing* |

Unchanged from P0-7. No percentage is hardcoded anywhere.

## 10. Partial payment accounting — **money tracked, not booked**

The one area that could be advanced. The allocation is approved policy and fully specified, so the
arithmetic was completed and proven; only the *posting* is blocked by D35.

Demonstrated with real test data (`PartialPaymentLifecyclePostgresTest`), all in integer paise:

```text
Product        ₹10,000.00        First collection  ₹6,250.00  (half the invoice)
GST             ₹1,800.00          GST             ₹1,800.00  ← 100%, settled first
Transportation    ₹500.00          Transportation    ₹500.00  ← 100%
Other charges     ₹200.00          Other charges     ₹200.00  ← 100%
─────────────────────────          Product         ₹3,750.00  ← the remainder
Final invoice  ₹12,500.00        ─────────────────────────
                                   Outstanding product ₹6,250.00

Second collection ₹6,250.00        GST ₹0 · Transport ₹0 · Other ₹0 · Product ₹6,250.00
                                   → invoice settled exactly; nothing over-collected
```

Proven by test:

- the first collection is exactly half and follows the priority order;
- the order **does not** report itself as paid after the first half (`fullyPaid = false`,
  outstanding ₹6,250.00);
- the balance is exactly the product still owed;
- across both collections each head receives precisely what the invoice said, summing to ₹12,500.00;
- a **third** collection is refused — *"This order has already been paid in full"*;
- the allocation is **frozen**: rewriting the order's product and tax values afterwards does not
  change what the customer is recorded as having paid.

## 11. Payment collection accounting · **BLOCKED**

`Dr Bank / Cr AR` requires AR, which only `postSale` creates, which runs only at backfill. Posting a
collection first would credit a receivable that was never debited and drive AR negative. Blocked on
D35, and then on D19 for the recognition date. **Stop condition 8 territory — nothing implemented.**

## 12. Payment failure accounting — correct as it stands

`PAYMENT_FAILED` → order `CANCELLED` (actor `SYSTEM`) → stock released once → **no SALE, no
collection**. Re-proven: after twelve concurrent failure arrivals, SALE journals for the order = 0
and captured total = 0.

The brief's caution — that `PAYMENT_FAILED` must not mean "no accounting event whatsoever" if a
sale legitimately exists — is already handled: P0-5B reverses a posted sale on cancellation, using
the existing non-destructive mechanism. Under today's Option B no sale exists yet, so there is
nothing to reverse. Under Option A there would be, and the reversal path is already built and
tested. The four events stay distinct: **SALE · PAYMENT_COLLECTION · PAYMENT_FAILURE · REVERSAL**.

## 13. Refund accounting · **BLOCKED**

The reversal architecture could represent it — non-destructive, audited, idempotent. What is missing
is the trigger and the entries, both downstream of collection accounting. The refund *arithmetic* is
settled and tested: actual amount paid, product + GST refundable, transportation and gateway fee
excluded, never more than was paid.

## 14. GST integration — unchanged

No redesign, no second engine. The payment layer reads `total_tax_amount` from the order's snapshot
and allocates it; it never computes tax, applies a rate, or knows what a rate is. Frontend values
are never consulted.

## 15. AR impact

**AR is unchanged: 1,979,633 paise = ₹19,796.33.**

No adjustment was made, so no before/after justification is required. No `PAYMENT_COLLECTION`
journal exists; ledger source types remain `SALE` × 28, `REVERSAL` × 21, `PAYROLL` × 2,
`EXPENSE` × 1, `PURCHASE` × 1, `GST_PAYMENT` × 1.

## 16. Accounting integration architecture — design, not built

```text
Order created ──► [SALE + AR]        ← D35: real-time or batch?  UNDECIDED
                       │
Payment CAPTURED ──────┼──► [PAYMENT COLLECTION] ──► [AR SETTLEMENT]   BLOCKED on D35, D19
                       │
Payment FAILED ────────┴──► order CANCELLED ──► inventory released ✓ IMPLEMENTED
                                              └► [REVERSAL, if a sale existed] ✓ mechanism ready
Refund ─────────────────► [REVERSAL / adjustment]                      BLOCKED on D15
```

## 17. Payment-to-accounting flow — what actually runs today

```text
Order → Razorpay order → Payment → CAPTURED
  │                                   ├─ allocation frozen on the attempt      ✓
  │                                   ├─ outstanding balance derived           ✓
  │                                   ├─ order eligible for fulfilment         ✓
  │                                   └─ SALE / collection posting             ✗ BLOCKED
  └─ FAILED → CANCELLED (SYSTEM) → stock released once → no SALE, no collection ✓
```

## 18. Data integrity

Snapshot taken before any work and again after the full run — **byte-identical**, including a
checksum over every journal's id, number, source, status and total:

```text
journal checksum   eea1e82cfa93ce16f7963524e3eaf56f   (before and after)

orders 7 · SALE 28 · REVERSAL 21 · journals 54 · lines 187
payment_attempt 0 · payment_refund 0 · payment_webhook_event 0
AR 1,979,633 paise · all 13 account balances unchanged
```

No historical journal was modified, deleted or rewritten. No test data remains. No migration was
created.

## 19. Test coverage

| Suite | Baseline | After |
|---|---|---|
| Backend | 434 / 434 | **440 / 440**, 0 failures |
| Cucumber | 87 run, 81 pass, 6 known | **87 run, 81 pass, the same 6** |
| Playwright client | 12 passed, 1 conditional skip | **12 passed, 1 skip** |
| Playwright admin | 15 passed | **15 passed** |

**New:** `PartialPaymentLifecyclePostgresTest` — 6 tests covering the full two-collection cycle.

**Required tests, and their honest status:**

| Required | Status |
|---|---|
| Duplicate order → no duplicate SALE | **Covered** — P0-3 idempotency + P0-5B any-status existence check |
| Partial payment — 50%, GST/transport/other/product allocation, outstanding | **Covered, new** |
| Second payment — remaining product amount | **Covered, new** |
| Payment failure — no new SALE, cancellation, one inventory release | **Covered** |
| 16 concurrent callback/webhook → one transition, one effect | **Covered** |
| 12 concurrent failures → one cancellation, one release | **Covered** |
| Real-time SALE → exactly one SALE + AR | **Not applicable** — blocked on D35 |
| Payment captured → one collection, AR reduced | **Not applicable** — blocked on D35 |
| Duplicate callback/webhook → one collection | **Partially** — one *capture* proven; "one collection" needs D35 |
| COD sale and collection timing | **Not applicable** — blocked on COD-1/D17 |
| Refund arithmetic and GST treatment | **Arithmetic covered**; postings blocked on D15 |

No test was modified to make the suite green.

## 20. Remaining blockers

```text
BLOCKED  D35 — SALE recognition timing
  Required   Real-time or batch? And CP-1 if real-time.
  Note       The journal is identical either way; what differs is the trigger, and
             what a cancelled order leaves behind in the books.

BLOCKED  CP-1 — closed-period order accounting
BLOCKED  COD-1 / D17 — COD sale timing and the cash-collection event
BLOCKED  TR-1 / TR-2 — transportation pricing and outbound/return split
BLOCKED  D24 / GF-1 — gateway fee ownership; 4 new accounts need approval
BLOCKED  D19 — when AR is cleared (on D35)
BLOCKED  D15 — refund trigger and postings (on D19)
UNDECIDED D36 — historical duplicate journals: audit review
BLOCKED  Sandbox validation — RAZORPAY_KEY_ID / KEY_SECRET / WEBHOOK_SECRET not set
```

## 21. Final approval checklist

| # | Item | Status |
|---|---|---|
| 1 | D35 — real-time or batch SALE | ☐ |
| 2 | CP-1 — closed-period order accounting | ☐ *(needed only if D35 = real-time)* |
| 3 | COD-1 — COD sale timing | ☐ |
| 4 | D17 — the event meaning cash collected | ☐ |
| 5 | TR-1 — transportation calculation method | ☐ |
| 6 | TR-2 — outbound / return split | ☐ |
| 7 | GF-1 — who bears the gateway fee | ☐ |
| 8 | 4 new accounts: gateway charges, gateway clearing, COD cash-in-transit, refunds contra | ☐ |
| 9 | D19 — AR settlement trigger | ☐ *(follows D35)* |
| 10 | D15 — refund trigger and postings | ☐ *(follows D19)* |
| 11 | D36 — historical journal audit review | ☐ |
| 12 | Sandbox credentials configured | ☐ |

**Accounting implementation cannot begin until items 1–4 are resolved.** Items 1 and 2 together
unblock the largest share: collection accounting, AR settlement, refunds and reconciliation all sit
behind them.
