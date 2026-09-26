# P0-5B — Accounting backfill and cancellation correctness

**Scope:** make the existing sale-posting and backfill behaviour correct and idempotent.

**Explicitly not in scope and not implemented:** payment gateway, payment transactions, refunds,
COD collection, partial payments, webhooks, payment reconciliation, gateway fees, new GST rules.

---

## Executive summary

Re-inspection confirmed the reported defect and found that **one of its consequences had already
happened in the live database**.

`backfill()` and `postSale()` did not look at order status, so a cancelled order could be booked as
revenue. That was the reported defect and it is fixed. But the duplicate problem was not
hypothetical either: **every one of the seven historical orders carries four SALE journals** — 28 in
total, of which 21 were afterwards reversed by hand to leave one standing each.

The mechanism turned out to be an interaction nobody had written down. The unique index
`ux_journal_source` is **partial**:

```sql
UNIQUE (source_type, source_id) WHERE source_id IS NOT NULL AND status = 'POSTED'
```

and the application's existence check asked the same question — is there a `POSTED` entry for this
source? Once an entry is reversed its status is no longer `POSTED`, so both the index and the check
stop seeing it, and the next backfill posts the sale again. Reverse, re-post, reverse, re-post: four
journals per order.

That interaction also made the primary fix futile on its own. Reversing a cancelled order's sale
would simply free the source for the next backfill to re-post. Both had to be fixed together.

Three changes, plus one configuration defect found along the way:

1. `postSale()` judges eligibility itself, on the status read back **under the order's row lock**.
2. `postSale()` refuses an order that has **ever** been accounted for, reversed entries included.
3. Cancelling an order reverses its posted sale through the existing reversal mechanism, idempotently.
4. `spring.datasource.hikari.*` had never been binding to the connection pool (§ Database changes).

No migration was required: the index this relies on already exists.

---

## Before

| Aspect | Behaviour before P0-5B |
|---|---|
| `backfill()` | Loaded every non-archived order and offered each to `postSale`. The only filter was `orderPlacedAt != null`. Status was never consulted. One `@Transactional` spanning the whole run, so a single constraint violation poisoned the transaction and every later posting in that run failed too — despite the documentation promising the opposite. |
| `postSale()` | Posted `Dr AR / Cr Sales / Cr Output GST` for any order handed to it. No eligibility check, no duplicate check of its own; it relied entirely on `JournalService.post`. |
| Duplicate behaviour | `JournalService.post` returned an existing entry only when one was `POSTED`. A reversed entry left the source looking unposted, so the next backfill wrote a new one. |
| Cancellation | P0-5A set the status, released stock and recorded a reason. It did nothing to the ledger, so revenue and a receivable stayed booked for an order that no longer existed. |
| Existing journal state | 7 orders · 28 SALE journals (4 each) · 21 REVERSAL entries · 187 journal lines. AR ₹19,796.33 — the net of four postings less three reversals per order. |

The cancelled-order path was verified end to end before any edit:

```text
order → status CANCELLED → backfill() → postSale() → SALE journal   (no check anywhere)
```

---

## After

### Eligibility

`OrderStatus.isSaleEligible()` — false for `CANCELLED` and `PAYMENT_FAILED`, true for everything
else.

Both exclusions are states in which the business never supplied anything. Neither is a payment
test: an order that was supplied remains a sale whether or not money has arrived, because payment
collection does not exist in this application. A `RETURNED` order also stays eligible — the supply
did happen, and the return is accounted for by its own credit note rather than by pretending the
sale never occurred.

`PAYMENT_FAILED` is a judgement call flagged for confirmation (§ Open items). No historical order
holds that status, so excluding it changes nothing in the existing books.

### Duplicate protection, in three layers

| Layer | What it stops |
|---|---|
| `postSale` — `existsBySourceTypeAndSourceId(SALE, orderId)`, **any status** | A sale already accounted for, including one deliberately reversed. This is the layer that closes the reverse-then-repost loop. |
| The order's row lock (`SELECT status … FOR UPDATE`) | Concurrent posters for one order queue instead of racing, so the common case never reaches the index at all. |
| `ux_journal_source` (already existed) | The database's own guarantee: at most one `POSTED` entry per source. Survives a second application instance. |

The application layers are a fast path; the index is the authority. Experiment A2 proves the index
is load-bearing.

### Cancellation

`applyStatus(…, CANCELLED, …)` calls `reverseSaleOfCancelledOrder`. It lives in the shared
transition method rather than in `cancelOrder`, because P0-5A left **two** routes to cancellation —
`cancelOrder` and an ordinary status update to `CANCELLED` — and both must have the same accounting
consequence.

| Case | Behaviour |
|---|---|
| **A** — cancelled, no SALE journal | Nothing posted, nothing reversed. |
| **B** — cancelled, SALE journal exists and is POSTED | The existing `JournalService.reverse` posts a mirror image dated today; the original is marked `REVERSED` and left untouched. Net effect on AR, Sales and Output GST for that order: zero. |
| **C** — cancelled, SALE already reversed | Nothing further. Repeating the cancellation, in any number of concurrent threads, still yields one reversal. |

### Transaction boundaries

| Operation | Boundary |
|---|---|
| `postSale` | `REQUIRES_NEW` — one order's entry and its lines commit or roll back together, and a failure on one order no longer aborts the rest of a backfill. This is what the backfill documentation always claimed. |
| `reverseSaleOfCancelledOrder` | Joins the caller's transaction, so the status change and the reversal are one unit. The books cannot end up disagreeing with the order. |
| `backfill` | Unchanged as a coordinator; per-record failures are counted and reported. |

### Period locks

Untouched. `JournalService.post` still calls `assertPeriodOpen`, and every new path goes through it.

A reversal is dated **today**, so it lands in the current period, not the original one — closing a
month does not prevent cancelling an order that was sold within it. If the *current* period is
closed, the reversal cannot be posted, the exception propagates, and **the whole cancellation rolls
back**. The order stays live rather than becoming a cancelled order with revenue still standing.
That is the conservative reading and it invents no policy; an operator is told the period is closed.
Whether cancellation should instead be allowed to defer its accounting is a business decision and is
listed in § Open items.

---

## Accounting state machine

```text
ORDER
  │
  ├─ status CANCELLED or PAYMENT_FAILED ──────────────► NOT SALE ELIGIBLE
  │                                                     no journal, ever
  │
  └─ any other status ──► SALE ELIGIBLE
                             │
                             ├─ already has a SALE journal (any status) ──► nothing posted
                             │
                             └─ none yet ──► SALE POSTED
                                               Dr 1100 AR
                                                  Cr 4010 Sales
                                                  Cr 2100/2110/2120 Output GST
                                               │
                                               └─ order later CANCELLED
                                                     │
                                                     └─► REVERSAL (existing mechanism)
                                                           mirror image, dated today
                                                           original kept, marked REVERSED
                                                           → source is now permanently
                                                             accounted for; no re-post
```

**These are accounting events, not payment events.** The distinction matters and is preserved:

| Event | Exists? | Meaning |
|---|---|---|
| **SALE** | Yes | A supply was made. Revenue and a receivable are recognised. |
| **SALE REVERSAL** | Yes (this phase wires it to cancellation) | The supply did not happen after all. Revenue and the receivable are taken back out. **No money moves.** |
| **PAYMENT COLLECTION** | **No** | Money arriving. Not implemented; `credit(RECEIVABLE, …)` still appears nowhere and account `1010 CASH` still has no rows. |
| **REFUND** | **No** | Money going back. Not implemented. A reversal is not a refund — nothing was ever collected to refund. |
| **GST CREDIT / ADJUSTMENT** | Yes, pre-existing | Handled by the credit-note flow. Untouched. |

---

## Database changes

**No migration was created.** The guarantee this work relies on —
`ux_journal_source (source_type, source_id) WHERE source_id IS NOT NULL AND status = 'POSTED'` —
already existed, and it is the right shape. Adding a broader unique constraint would have been
wrong: reversed entries must be allowed to coexist with the original they reverse.

One **application configuration defect** was found and fixed, in `DataSourceConfig`:

```java
@Bean
@ConfigurationProperties(prefix = "spring.datasource.hikari")
public DataSource dataSource() { … return routingDataSource; }   // before
```

`@ConfigurationProperties` binds to the **returned** object, which was the
`AbstractRoutingDataSource` wrapper. That class has no pool settings, so every configured Hikari
value was silently discarded and the application always ran on Hikari's defaults. Proven rather than
inferred: a test setting `maximum-pool-size=30` still exhausted at 10.

The fix splits the pooled datasource into its own bean so the properties bind to it, with the
routing wrapper marked `@Primary`. Consequences:

- **local** profile: configured 10, previously effective 10 — no change.
- **dev / prod**: configured 20, previously effective 10 — these will now get the 20 their
  configuration has always asked for. PostgreSQL `max_connections` is 100, so there is headroom, but
  this should be reviewed before deploying.
- Rollback: revert the one file; nothing persists.

This was not cosmetic. It was the reason the mandated 20-way concurrency test could not run: twenty
concurrent posters need twenty sessions, and the pool served ten. With the binding fixed the suite
went from **372s with two failures → 17s with none**.

---

## Tests

| Suite | Baseline (P0-5A) | After P0-5B |
|---|---|---|
| Backend (surefire) | 384 / 384 | **400 / 400**, 0 failures |
| PostgreSQL integration (`AccountingBackfillPostgresTest`) | — | **16 / 16**, real PostgreSQL |
| Cucumber | 86 run, 80 pass, 6 known failures | **86 run, 80 pass, the same 6** |
| Playwright client | 12 passed, 1 conditional skip | **12 passed, 1 conditional skip** |
| Playwright admin | 15 passed | **15 passed** |

`AccountingBackfillPostgresTest` covers every case the brief lists:

| Brief | Test | Result |
|---|---|---|
| 1 valid sale | `eligibleOrderPostsOnce` | one balanced journal; AR debit = invoice total |
| 2 repeated backfill | `repeatedPostingIsIdempotent`, `repeatedBackfillIsIdempotent` (3 runs), `backfillDoesNotResurrectAReversedSale` | still one journal |
| 3 concurrent posting | `concurrentPostingProducesOneJournal` — **20 threads** | exactly one journal, no caller errored |
| 4 cancelled order | `cancelledOrderIsNotPosted`, `paymentFailedOrderIsNotPosted`, `concurrentPostingOfCancelledOrderProducesNothing` (**20 threads**), `statusIsRereadUnderTheLock` | zero journals |
| 5 existing sale + cancellation | `cancellationReversesAnExistingSale`, `cancellingAnUnpostedOrderDoesNothingToTheBooks` | original kept and marked reversed; one reversal; net AR/Sales/GST zero |
| 6 repeated reversal | `repeatedCancellationDoesNotDuplicateTheReversal` | one reversal after five cancellations plus a direct call |
| 7 concurrent reversal | `concurrentCancellationsProduceOneReversal` — 12 threads | one reversal, ledger balanced, no caller errored |
| 8 journal balance | `everyJournalBalances` | every header matches its lines; whole ledger nets to zero |
| 9 period lock | `closedPeriodIsNotBypassed` | refused, no journal, no orphaned lines; the period's prior status is restored |
| 10 failure rollback | `failedPostingRollsBackCompletely` | journal and line counts unchanged |
| 11 backfill regression | the historical 7 orders were re-verified after the full suite | unchanged |

The concurrency tests use real PostgreSQL. No in-memory substitute or mock is used for any
concurrency or stock guarantee.

---

## Failure injection

Three experiments, each reverted afterwards and the restoration verified by checksum.

**A1 — application-level duplicate protection removed** (row lock and the any-status existence
check; index left in place):

```
concurrentPostingProducesOneJournal:287
    DataIntegrityViolationException: duplicate key value violates unique constraint "ux_journal_source"
repeatedPostingIsIdempotent:215
    a repeat must write nothing ==> expected: <null> but was: <JournalEntryEntity@…>
```

The index still held the line at one journal — but callers now collide, and a plain repeat writes
again. This is what the application layers are for.

**A2 — the unique index dropped as well:**

```
concurrentPostingProducesOneJournal:283
    one order, one sale — got 20 ==> expected: <1> but was: <20>
```

**Twenty concurrent attempts produced twenty SALE journals.** The database is genuinely
load-bearing; the application check alone would not have been enough. The index was recreated
immediately and verified.

**B — the cancelled-order eligibility check removed:**

```
cancelledOrderIsNotPosted:329            postSale must refuse it — expected <null> but was <JournalEntryEntity@…>
concurrentPostingOfCancelledOrderProducesNothing:319   a cancelled order is not revenue — expected <0> but was <1>
paymentFailedOrderIsNotPosted:341        expected <null> but was <JournalEntryEntity@…>
statusIsRereadUnderTheLock:356           eligibility must be judged on the committed status — expected <null> but was <…>
```

Four tests failed, including the one proving the status is re-read under the lock rather than taken
from the caller's stale copy.

---

## Existing known failures

The same six Cucumber failures as the P0-4 and P0-5A baselines, with **identical messages**:

| Failure | Related to this phase? |
|---|---|
| 3 × missing `Authorization` header returns 400 rather than 401 (`/bag`, `/bag/add`, `/favourites`) | No |
| Place-of-supply 500 on a free-text delivery location | No |
| Reconciliation check: `Revenue: ledger vs orders; GST payments: ledger vs payments made this period` | **Accounting-adjacent — checked specifically.** It reads the same ledger, and the ledger is byte-identical to the pre-change baseline (see Data verification), so this check sees exactly the data it saw before and fails for exactly the same reason. Not caused or worsened here. |
| `/products/new-in` returns `[]` — newest seed product is now 35 days old against a 30-day window | No |

None was fixed, per the brief, and none changed.

---

## Data verification

Captured before any edit and again after the full suite, the experiments and the regression runs.
**Every figure is identical.**

| Measure | Before | After |
|---|---|---|
| Orders | 7 | 7 |
| `journal_entry` total | 54 | 54 |
| `journal_entry` SALE | 28 | 28 |
| `journal_entry` REVERSAL | 21 | 21 |
| `journal_entry_line` | 187 | 187 |
| `1100` Accounts Receivable | 1,979,633 paise (₹19,796.33) | 1,979,633 |
| `4010` Sales | −1,685,790 | −1,685,790 |
| `2100` / `2110` Output CGST / SGST | −39,840 / −39,840 | −39,840 / −39,840 |
| `2120` Output IGST | −214,163 | −214,163 |
| `1020` Bank · `2200` GST Payable | −100,500 · 100,500 | unchanged |
| `1200` · `1300` · `2010` · `2300` · `5100` · `5200` | unchanged | unchanged |

A `diff` of the two captures is empty. No temporary or demo record remains: zero `VO-ACC*`,
`VO-LC*`, `VO-PWL*` orders and zero `@automation.veloria.test` users.

**The historical duplicates were deliberately left alone.** Each of the seven orders still carries
four SALE journals (1 POSTED, 3 REVERSED). The brief requires historical entries to remain intact
and forbids mutating posted journals, and the net position is already correct — one live sale per
order. Tidying them would mean deleting audited history. Their existence is recorded here as
evidence of the defect, not as something to erase.

---

## Security

No accounting endpoint was exposed by this work. Verified against the running server:

```
POST /accounting/backfill                 anonymous → 403
POST /sales-order/order/{code}/cancel     anonymous → 403
```

`backfill`, period close/reopen, journal reverse and opening balances all remain behind
`@PreAuthorize`. The new service method `reverseSaleOfCancelledOrder` is reachable only through
cancellation, which P0-5A already gates on `ADMIN_GST` at both the controller and the service.

**Pre-existing finding, not introduced here and not fixed here:** the accounting *read* endpoints —
`/accounting/trial-balance`, `/profit-loss`, `/balance-sheet`, `/ledger`, `/journals`,
`/reconciliation` — are anonymous (`GET /accounting/trial-balance` → 200 with no token). That is the
P0-1 design decision to gate mutations only. It means complete financial statements are readable by
anyone who can reach the port. It deserves its own decision, in the same conversation as the
`/sales-order` read lock-down from P0-5A.

---

## Open items

| # | Question |
|---|---|
| 1 | **Is excluding `PAYMENT_FAILED` from revenue correct?** It is not a payment test — the order explicitly failed at checkout and nothing shipped — but it is a judgement call. No existing order holds the status, so nothing historical changed. |
| 2 | **Should cancellation be blocked when the current accounting period is closed?** It is today, because the reversal cannot be posted and the whole cancellation rolls back. The alternative — cancel now, account later — is a policy this phase deliberately did not invent. |
| 3 | **Should a reversed sale ever be re-postable?** It no longer is. If an operator reverses a sale to correct it, re-posting now requires a deliberate new entry rather than a backfill run. That is the safer default, but it is a change in what reversal means operationally. |
| 4 | **Checkout still does not post sales in real time.** The books are built by backfill. Unchanged here; it remains the larger accounting question. |
| 5 | **dev / prod connection pools will now be 20 rather than an effective 10.** Confirm before deploying. |
| 6 | **Anonymous accounting reads** (§ Security). |

---

## Out of scope — explicitly not implemented

This phase implemented **no** payment functionality: no payment gateway, no payment transactions, no
refunds, no COD collection, no partial payments, no webhook handling, no payment reconciliation, no
gateway fees, and no new GST rules. Account numbers, account names, GST calculation, GST rates,
historical invoice amounts, journal semantics and period-lock design are all unchanged. Money
remains integer paise throughout; no float or double was introduced.

P0-2 inventory locking and P0-3 checkout idempotency are untouched and their tests still pass as
part of the 400.

---

## Acceptance criteria

| Criterion | Status |
|---|---|
| Cancelled orders cannot be newly posted as SALE revenue | ✅ `postSale` refuses; proven by Experiment B |
| Valid orders can be posted | ✅ |
| A sale can only be posted once | ✅ any-status existence check |
| Database integrity participates | ✅ `ux_journal_source`; proven load-bearing by Experiment A2 |
| Concurrent posting produces exactly one SALE | ✅ 20 threads, one journal, no errors |
| Repeated backfill is idempotent | ✅ three consecutive runs |
| Existing historical SALE journals intact | ✅ byte-identical |
| Existing reversal architecture respected | ✅ used, not replaced |
| Repeated reversal cannot duplicate accounting | ✅ 12 concurrent cancellations → one reversal |
| Journals remain balanced | ✅ header, lines and whole ledger |
| Period locks remain enforced | ✅ |
| No payment functionality introduced | ✅ |
| P0-2 and P0-3 intact | ✅ |
| No unrelated test weakened | ✅ one unit test's mocks updated to the new contract, assertions unchanged |
| No temporary test data remains | ✅ |
