# P0-10 — Accounting implementation report

## 1. Executive summary

The approved accounting model is implemented. An order now books its sale when
it is created; a payment or a cash collection settles the receivable that sale
raised; a cancellation reverses it. All of it is idempotent, concurrency-safe
and period-aware.

**Historical data is provably untouched.** The journal checksum taken before any
work — `eea1e82cfa93ce16f7963524e3eaf56f` — is identical after implementation
*and* after a full regression run. AR is still ₹19,796.33, SALE is still 28,
REVERSAL still 21.

Three things the implementation surfaced that were not visible from inspection,
each reported rather than worked around:

1. **The COD fee has no accounting home.** It is added to the order when the
   customer chooses COD — after the sale has been recognised — so the customer
   pays ₹50 that no receivable stands behind. Collections are now capped at the
   receivable rather than pushing it negative, and the ₹50 is recorded on the
   payment but stays out of the ledger pending an approved treatment.
2. **Order creation now costs twice the database work.** The sale posts in a
   second transaction after the order commits, so concurrent checkout needs
   roughly double the connections. A twelve-way concurrency test failed with
   `too many clients` until the pools were sized for it.
3. **Every fixture that creates an order now creates a journal.** Several test
   cleanups predated orders having journals and left 101 orphans behind. Both
   automation fixtures and two integration tests were corrected.

**CP-1 is an implementation gap**, reported not invented: the approved policy is
to defer accounting out of a closed period, and no deferred-posting mechanism
exists.

## 2. Decisions applied

| | Approved | Implemented |
|---|---|---|
| **D35** | SALE at order creation | ✅ Posted from checkout, after commit. Journal unchanged — same accounts, same amounts, still dated `orderPlacedAt` |
| **CP-1** | Order stands; accounting deferred | ⚠️ **Half.** The order always stands. The deferral does not exist — §13 |
| **COD-1 / D17** | Sale at creation; cash at delivery | ✅ Both |
| **D36** | Do not modify historical journals | ✅ Checksum identical |
| **TR-1 / TR-2** | Undecided | ✅ Nothing invented; `shipping_value` still 0 |
| **GF-1** | Gateway fee is a separate cost | ⚠️ Recorded separately on the payment; **no journal** — no account exists |

## 3. Accounting flow

```text
Order created ──► SALE            Dr 1100 AR / Cr 4010 Sales / Cr 2100·2110·2120 Output GST
                    │                dated orderPlacedAt · posted after the order commits
                    ▼
                   AR outstanding
                    │
      ┌─────────────┼──────────────────────────┐
      ▼             ▼                          ▼
  online payment   COD delivered            cancelled
  CAPTURED         cash collected           (any approved actor)
      │             │                          │
      ▼             ▼                          ▼
  COLLECTION     COLLECTION                 REVERSAL
  Dr 1020 Bank   Dr 1010 Cash               mirror of the sale, dated today
  Cr 1100 AR     Cr 1100 AR                 original kept, marked REVERSED
      │             │                          │
      ▼             ▼                          ▼
  AR reduced     AR reduced                 AR cleared · stock released once
```

A failed payment produces **no collection**: the order is cancelled by `SYSTEM`,
which reverses the sale and releases the stock exactly once.

## 4. Partial payment

Invoice ₹12,500 — product ₹10,000, GST ₹1,800, transportation ₹500, other ₹200:

| | Amount | GST | Transport | Other | Product | AR after |
|---|---:|---:|---:|---:|---:|---:|
| **Sale** | — | — | — | — | — | ₹12,500 |
| **First collection** | ₹6,250 | ₹1,800 | ₹500 | ₹200 | ₹3,750 | **₹6,250** |
| **Second collection** | ₹6,250 | ₹0 | ₹0 | ₹0 | ₹6,250 | **₹0** |

One sale, two collections. The order is **not** treated as settled after the
first half, and a third collection is refused — *"This order has already been
paid in full."* The allocation is stored on each attempt and never recomputed:
rewriting the order's product and tax values afterwards does not change what the
customer is recorded as having paid.

## 5. Historical data

| | Before | After implementation | After a full regression run |
|---|---|---|---|
| Journal checksum | `eea1e82c…` | `eea1e82c…` | **`eea1e82c…`** |
| AR (account 1100) | 1,979,633 paise | 1,979,633 | **1,979,633** |
| SALE | 28 | 28 | **28** |
| REVERSAL | 21 | 21 | **21** |
| Journals · lines | 54 · 187 | 54 · 187 | **54 · 187** |
| All 13 account balances | — | unchanged | **unchanged** |

The checksum is reproducible — it is not a claim, it is a command:

```sql
SELECT md5(string_agg(id||':'||journal_number||':'||source_type||':'
                      ||COALESCE(source_id::text,'-')||':'||status||':'
                      ||total_debit_paise, '|' ORDER BY id))
FROM journal_entry;
-- eea1e82cfa93ce16f7963524e3eaf56f
```

**The checksum did change mid-phase, and was restored.** After the first full
regression it became `9ce6fcdf…`, with SALE at 129 and AR at 24,267,633. Every
extra journal was **orphaned test residue** — its order had been deleted, while
the journal remained, because several fixtures predated orders having journals.
The seven historical orders' 28 sale journals were verified intact throughout.
Only journals referencing a non-existent order were removed; the fixtures were
then corrected, and a further full run left the checksum identical.

## 6. Architecture changes

| File | Change |
|---|---|
| `AccountingPostingService` | `PAYMENT_COLLECTION` source type; `postPaymentCollection`; backfill now also settles missed collections |
| `JournalEntryRepository` | `outstandingReceivableFor` — reads the receivable from the ledger, so it cannot disagree with the books |
| `ClientOrderServiceImpl` | Posts the sale after the order's transaction commits |
| `PaymentService` | Posts a collection on capture; `recordCodCollection`; COD attempts start `PENDING` |
| `SalesOrderServiceImpl` | `DELIVERED` triggers COD collection, idempotently |
| `PaymentAttemptRepository` | `findByStatusOrderByIdAsc`, for the backfill |
| 11 test classes | Property sets standardised so Spring shares contexts (§12) |
| `TestData.java`, `db.js`, 2 integration tests | Clean up sales, reversals, collections and payment rows |

**No migration was created.** The payment and journal tables already carried
everything needed. `ux_journal_source` — the partial unique index P0-5B relies
on — now guards collections as well, because a collection is keyed on the
payment attempt and goes through the same posting path.

## 7. Idempotency and concurrency

| Guarantee | Mechanism | Proven by |
|---|---|---|
| One sale per order | `ux_journal_source` + any-status existence check (P0-5B, unchanged) | 20 concurrent backfills → 1 sale |
| Backfill cannot duplicate | Same | 2 further backfills → still 1 |
| A reversed sale is not resurrected | Any-status check | Cancel, then backfill twice → still 1, still reversed |
| One collection per payment | Keyed on the attempt, same index | 5 repeat captures + backfill → 1 collection |
| COD collected once | Attempt row lock + status check | 6 delivery updates + a direct call → 1 collection |
| Stock released once | P0-2 derived model, unchanged | 12 concurrent failures → released once |
| Callback/webhook converge | Attempt row lock | 16 concurrent arrivals → 1 capture |

## 8. Payment failure

`PAYMENT_FAILED` → order `CANCELLED` by `SYSTEM` → stock released once → the
sale that was already raised is **reversed** through the existing mechanism →
**no collection is ever created**. The four events stay distinct: SALE,
PAYMENT_COLLECTION, PAYMENT_FAILURE, REVERSAL.

## 9. GST

Untouched. No second engine. The payment layer reads `total_tax_amount` from the
order's snapshot and allocates it first; it never computes tax or knows a rate.
The sale journal's tax split is exactly what `postSale` always produced.

## 10. Transportation

`shipping_value` is still **0 on every order**, and no pricing logic, rate
table, distance, weight, pincode or courier algorithm was written. The model is
not hardcoded to zero: `OrderInvoice` reads the column, and the allocation
waterfall settles transportation in full before the product — proven at ₹500 in
the partial-payment tests. A non-zero value will flow through without further
change. **TR-1 / TR-2 remain undecided.**

## 11. Gateway fee

Recorded on the payment attempt as `gateway_fee_paise`, separate from
transportation, `NULL` until settlement data supplies it, never a rate. It is
**not posted to the ledger**:

> **`NEW ACCOUNT — REQUIRES ACCOUNTING APPROVAL`** — the chart of accounts has no
> gateway or payment-processing expense account. Its expense accounts are 5010
> COGS, 5100 Marketing, 5200 Payroll, 5300 Rent, 5400 Utilities, 5900 Other
> Operating Expenses. Posting to 5900 would be inventing a classification.

Refunds do not deduct it, unchanged.

## 12. Connection budget — an operational consequence

Order creation now opens a second transaction. A twelve-way concurrent checkout
therefore needs roughly twenty-four sessions where it needed twelve, and the
first full run failed with `FATAL: sorry, too many clients already`: five test
contexts held 160 connections against a server allowing 100.

Fixed by giving every integration test an identical property set, so Spring
caches **2** contexts instead of 5. Assertions are unchanged.

**For deployment:** size the pool for roughly twice the concurrent checkout rate,
and check the total against the server's `max_connections`. At the configured 20
per instance, five instances would reach 100.

## 13. CP-1 — closed-period accounting: **IMPLEMENTATION GAP**

**Approved:** the order stands, and its accounting is deferred to the next open
period. **Implemented:** the first half only.

An order whose period is closed is created normally — `assertPeriodOpen` throws,
the failure is logged, and the order commits regardless, because the sale posts
after the order's transaction. Nothing bypasses the period lock and no journal
is written into a closed period.

**What does not exist is the deferral.** The sale is simply unposted, and the
backfill cannot post it either — `postSale` dates the journal at
`orderPlacedAt`, so it will fail on the same closed period forever.

### The smallest mechanism that would close it

A journal needs a posting date distinct from its transaction date. That is one
column and one rule — *post into the period containing the transaction date, or
the earliest open period after it* — but it changes what a journal's date means,
which is an accounting decision:

```text
BLOCKER            CP-1 deferred posting
WHY                postSale dates the journal at orderPlacedAt; a closed period
                   rejects it permanently, so "deferred" has nowhere to defer to
ARCHITECTURE       assertPeriodOpen refuses; no pending-posting queue exists
MINIMUM DECISION   may a sale be posted into a later period than its order date,
                   and which date does the journal then carry?
NEXT STEP          answer that, then add the posting-date column and a
                   next-open-period rule
```

## 14. Test results

| Suite | Baseline (P0-9) | After |
|---|---|---|
| Backend | 440 / 440 | **451 / 451**, 0 failures |
| Cucumber | 87 run, 81 pass, 6 known | **87 run, 81 pass, the same 6** |
| Playwright client | 12 passed, 1 conditional skip | **12 passed, 1 skip** |
| Playwright admin | 15 passed | **15 passed** |

**New:** `SaleAndCollectionPostgresTest` — 11 tests covering sale at creation,
backfill non-duplication, 20-way concurrent posting, full payment clearing AR,
collection idempotency, partial payment settling in two, COD collected only on
delivery and only once, failure reversing the sale, and per-order reconciliation.

Coverage of the required list: sale creation · sale idempotency · sale
concurrency · backfill idempotency · payment collection · partial payment ·
second payment · third payment rejection · callback/webhook race · payment
failure · inventory release · COD collection · COD duplicate collection · AR
reconciliation · historical data protection — **all covered**. Refund and
closed-period posting are **blocked** (§13, §16), not silently skipped.

### The six known Cucumber failures — unchanged

Three missing-header 400-vs-401 (`/bag`, `/bag/add`, `/favourites`); a
place-of-supply 500 on a free-text address; the reconciliation check; and
`/products/new-in` returning `[]` because the seed data has aged past its 30-day
window. None was fixed, none was hidden, none is related to this work.

## 15. Security

Unchanged from P0-7 and re-verified: no Razorpay secret in source, frontend or
git; no card, CVV or VPA stored — zero instrument columns; no payment amount
accepted from the browser; no unsigned webhook accepted; no unauthenticated
payment mutation.

## 16. Remaining blockers

```text
BLOCKED  COD fee accounting
  WHY        the ₹50 is added after the sale is recognised, so no receivable
             stands behind it; collections are capped at the receivable and the
             fee stays out of the ledger
  DECISION   is it revenue, a recovery, or a reduction — and is it taxable?
  NEXT       answer, then post it at the point COD is chosen

BLOCKED  Gateway fee journal
  WHY        no gateway or payment-processing expense account exists
  DECISION   NEW ACCOUNT — REQUIRES ACCOUNTING APPROVAL
  NEXT       approve the account, then post on settlement

GAP      CP-1 deferred posting                       see §13
BLOCKED  D15 refund trigger and postings             arithmetic ready; workflow not built
UNDECIDED TR-1 / TR-2 transportation pricing
BLOCKED  Razorpay sandbox                            credentials not configured
```

## 17. Sandbox

```text
BLOCKED — CREDENTIALS NOT CONFIGURED
```

`RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` and `RAZORPAY_WEBHOOK_SECRET` are unset
and no `.env` exists. No CARD, UPI, live webhook or live signature test was run,
and none is claimed. The gateway is stubbed where tests needed one.

## 18. Production readiness

| | |
|---|---|
| **CODE COMPLETE** | For the approved model: sale at creation, AR, collection for online and COD, reversal on cancellation, partial payment. **Not** for refunds, gateway-fee posting, or CP-1 deferral |
| **TEST COMPLETE** | 451 backend · 87 Cucumber · 27 Playwright, with concurrency proven on real PostgreSQL |
| **SANDBOX VALIDATED** | **No** — no credentials; no gateway call has ever been made |
| **PRODUCTION READY** | **No.** Live keys are refused at startup. Four blockers open, no reconciliation, no refund workflow, no settlement validation, no runbook |
