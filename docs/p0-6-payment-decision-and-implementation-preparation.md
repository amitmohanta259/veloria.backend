# P0-6 — Payment Decision Gate & Implementation Preparation

**This phase implements nothing.** It separates the decisions that need human approval from the
technical facts and proposals that do not, so that payment implementation can begin from approved
policy rather than from assumptions.

Part A records decisions. Part B records technical preparation. They are deliberately not mixed: no
proposal in Part B settles a question in Part A, and no unresolved question in Part A is quietly
answered by a technical default.

---

## 1. Executive summary

**No decision in this document is `DECIDED`.**

That is a finding, not an omission. The repository was searched for business, product, accounting or
tax documentation that could make any payment question authoritative. The only documents that exist
are the registers produced in P0-4 (`p0-4-payment-business-decision-register.md`,
`payment-business-decision-and-architecture.md`), and both record *open questions*. Neither contains
an approval, an owner, or a date. A grep for `DECIDED` across them returns zero.

So the register below is **14 UNDECIDED · 23 BLOCKED · 2 TECHNICAL · 0 DECIDED**.

The root of the dependency graph is a single question — **D1: will CARD/UPI move real money?** —
and it blocks 23 of the 39 entries, directly or transitively. Nothing in the payment domain can be
designed until it is answered, because the answer changes the schema, the state machine, the
inventory policy and the accounting integration all at once.

Three facts from re-inspection frame everything:

1. **Payment does not exist anywhere in the backend.** Word-boundary searches for `paymentMethod`,
   `paymentStatus`, `paymentTransaction`, `transactionId`, `gateway`, `webhook`, `refund`,
   `Razorpay`, `Stripe`, `UPI`, `COD` and `PARTIAL` across `src/main/java` return **zero files**.
   `customer_order` has **zero** payment columns. There are **zero** customer-payment tables.

2. **The checkout screen presents four payment methods and collects none of them.** Card number,
   name, expiry, **CVV** and UPI VPA are held in React state and never transmitted. The one payment
   field that is sent — `paymentMethod` — is silently discarded by the backend. The ₹50 COD fee and
   the 50% partial split are float arithmetic in the browser that never reaches the server.

3. **Stock is consumed by order creation, before any money could be confirmed.** This is the P0-2
   design, unchanged. It makes **D10 (inventory after payment failure)** the decision with the
   widest technical blast radius after D1.

Two decisions inherited from earlier phases are recorded here rather than left implicit: P0-5B's
exclusion of `PAYMENT_FAILED` from revenue (**D25**), and P0-5B's behaviour of failing a
cancellation when the accounting period is closed (**D26**). Both are live in the code today and
both need explicit ratification or reversal.

---

# PART A — DECISION REQUIREMENTS

## 2. Business decision register

Status rules applied exactly as specified in Part A5: `BLOCKED` means the decision depends on
another unresolved decision. Where this yields a different status from the starter table supplied
with the task, the rule was followed and the divergence is noted at the foot of the table.

| ID | Area | Decision Question | Current System Behavior | Options | Selected Decision | Status | Decision Owner | Dependencies | Implementation Consequence | Approval Date | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|
| D1 | Online Payment | Will CARD/UPI involve real money collection? | No collection of any kind exists | Yes / No / Defer | — | **UNDECIDED** | Business | — | Determines whether a payment domain is built at all | — | Root of the graph; blocks 23 entries |
| D2 | Gateway | Which payment gateway will be used? | None | Provider selection *(vendor)* | — | **BLOCKED** | Business + Finance | D1 | Fixes SDK, webhook shape, settlement file format | — | Do not choose on technical convenience |
| D3 | CARD | Is CARD supported? | UI only, never transmitted | Supported / Not / Temporarily disabled | — | **BLOCKED** | Product | D1, D2 | Determines PCI scope and tokenisation | — | See D32 |
| D4 | UPI | Is UPI supported? | UI only, never transmitted | Supported / Not / Temporarily disabled | — | **BLOCKED** | Product | D1, D2 | Determines collect-request vs intent flow | — | |
| D5 | COD | Is COD supported? | UI only; never reaches backend | Supported / Not / Temporarily disabled | — | **UNDECIDED** | Business | — | COD needs no gateway; could be built first | — | Independent of D1 |
| D6 | COD Fee | Is the ₹50 COD fee real? | Frontend-only float; never stored | Real / Removed / Configurable / Other amount | — | **BLOCKED** | Business + Finance | D5 | If real: an order-level charge line, priced in paise | — | Currently shown to customers and never charged |
| D7 | Partial Payment | Is partial payment supported? | Frontend-only calculation | Supported / Not / Temporarily disabled | — | **UNDECIDED** | Business | — | Forces one-order-to-many-payments cardinality | — | |
| D8 | Partial Amount | What amount/percentage is initially collected? | UI uses 50% | 50% / other % / fixed / tiered | — | **BLOCKED** | Business | D7 | Fixes the rounding rule in paise | — | UI value is not authoritative |
| D9 | Payment Failure | What happens when online payment fails? | No real payment | Several (§6) | — | **BLOCKED** | Business + Ops | D1, D2 | Order state, retry contract | — | |
| D10 | Inventory | What happens to inventory after payment failure? | Consumed at order creation | Release now / hold to timeout / reservation / other | — | **BLOCKED** | Business + Ops | D9 | Largest technical blast radius after D1 | — | Would change P0-2 assumptions (§5) |
| D11 | Payment Success | When does an order become paid? | No payment state | On capture / on webhook / on settlement | — | **BLOCKED** | Business + Finance | D1, D2 | Defines the paid boundary for fulfilment and AR | — | |
| D12 | Fulfilment | When can a paid order enter fulfilment? | Existing lifecycle, no payment gate | On paid / on cleared / immediately | — | **BLOCKED** | Ops | D11 | Adds or omits a gate on `ORDER_PLACED → PACKED` | — | |
| D13 | Cancellation | Which states may be cancelled? | Backend state machine: only `ORDER_PLACED` and `PACKED` | Keep / widen / narrow | — | **UNDECIDED** | Business + Ops | — | P0-5A's transition table | — | Decidable today |
| D14 | Paid Cancellation | Can paid orders be cancelled? | No payment exists | Yes / No / Window-limited | — | **BLOCKED** | Business | D11, D13 | Forces a refund path | — | |
| D15 | Refund | When is money refunded? | No refund path | Several (§11) | — | **BLOCKED** | Business + Finance | D13, D14 | Refund records and postings | — | |
| D16 | Partial Refund | Are partial refunds supported? | Not implemented | Yes / No | — | **BLOCKED** | Business + Finance | D7, D15 | Refund-to-capture cardinality | — | |
| D17 | COD Collection | What event means COD was collected? | No collection event | On delivery / on courier remittance / on bank credit | — | **BLOCKED** | Finance + Ops | D5 | The event that clears AR for COD | — | `DELIVERED ≠ CASH_COLLECTED` unless approved |
| D18 | COD Refusal | What happens when COD is refused? | Not defined; nearest is `CANCELLED` | Several (§8) | — | **BLOCKED** | Business + Ops | D5, D17 | New lifecycle states, RTO handling | — | |
| D19 | AR Settlement | When is AR cleared? | Never credited except by manual reversal | On capture / settlement / collection | — | **BLOCKED** | Finance | D11 | `Dr Cash/Bank / Cr AR` trigger | — | |
| D20 | Existing AR | What happens to ₹19,796.33? | Existing balance, uncollected | Collect / write off / reclassify / retain | — | **UNDECIDED** | Finance | — | Opening-balance treatment | — | Do not modify in P0-6 |
| D21 | Webhooks | Are gateway webhooks authoritative? | None | Authoritative / advisory / both with reconciliation | — | **BLOCKED** | Business + Ops | D1, D2 | Who wins when browser and webhook disagree | — | |
| D22 | Payment Idempotency | What constitutes a duplicate payment? | None | Several (§15) | — | **BLOCKED** | Business + Finance | D1, D2 | Key definition, distinct from `clientOrderReference` | — | |
| D23 | Reconciliation | How are gateway/bank/application records reconciled? | None | Several (§17) | — | **BLOCKED** | Finance + Ops | D1, D2, D19 | Settlement ingestion, exception queue | — | |
| D24 | Gateway Fees | How are gateway fees accounted for? | None | Expense on capture / netted at settlement | — | **BLOCKED** | Finance | D2 | New expense account (§30) | — | |
| D25 | PAYMENT_FAILED Revenue | Can `PAYMENT_FAILED` create revenue? | **Excluded by P0-5B, live today** | Approve / reject / accounting review | — | **UNDECIDED** | Finance | — | Ratifies or reverses shipped behaviour | — | §19 |
| D26 | Closed Period | What happens when cancellation requires reversal in a closed period? | **Cancellation fails, live today** | A reject / B defer / C credit-note / D other | — | **UNDECIDED** | Finance + Ops | — | Ratifies or reverses shipped behaviour | — | §18 |
| D27 | COD GST | Is the COD fee taxable, and how? | No authoritative backend fee | Taxable supply / reimbursement / exempt | — | **BLOCKED** | Tax | D6 | HSN/SAC, rate, place of supply | — | Requires tax opinion |
| D28 | Refund GST | How are refunds/credit notes handled? | GST snapshots and credit notes exist | Existing credit note / new adjustment | — | **BLOCKED** | Tax + Finance | D15 | Whether refunds reuse the credit-note flow | — | |
| D29 | Payment Timeout | What happens when payment stays pending too long? | None | Expire / hold / manual review | — | **BLOCKED** | Business + Ops | D9, D21 | Timeout job, inventory consequence | — | |
| D30 | Multiple Attempts | Can multiple payment attempts exist for one order? | None | Yes / No | — | **BLOCKED** | Business | D22 | One-to-many order→attempt model | — | |
| **D31** | Currency | Is INR the only currency? | `currency` accepted from the client, **unvalidated**, defaults `"INR"` | INR only / multi-currency | — | **UNDECIDED** | Business | — | Validation, money model | — | Found by inspection |
| **D32** | Card data in browser | Should card/CVV/VPA fields be removed before payment work? | Collected in React state and **discarded** | Remove now / replace with hosted fields / keep | — | **UNDECIDED** | Product + Security | — | A CVV collected and thrown away should not survive this decision | — | Found by inspection |
| **D33** | Request strictness | Should the API reject unknown payment-shaped fields? | `paymentMethod`, `razorpayPaymentId`, `amountPaid` accepted and silently dropped | Reject / ignore | — | **TECHNICAL** | Engineering | — | Jackson `FAIL_ON_UNKNOWN_PROPERTIES` or explicit validation | — | No business policy involved |
| **D34** | Accounting reads | Should trial balance / P&L / balance sheet be readable anonymously? | **They are** — `GET /accounting/trial-balance` → 200 with no token | Lock down / keep open | — | **UNDECIDED** | Business + Security | — | Pre-existing; flagged in P0-5B | — | Same conversation as P0-5A's `/sales-order` lock-down |
| **D35** | Sale posting timing | Should checkout post the sale in real time? | `postSale` is called only by `backfill()` | Real-time / batch | — | **UNDECIDED** | Finance | — | Payment postings against a batch-built ledger would be inconsistent | — | Constrains D19 |
| **D36** | Historical duplicates | Should the 28 historical SALE journals be formally reviewed? | 4 per order, 21 reversed, net correct | Review and sign off / accept as-is | — | **UNDECIDED** | Finance | — | Audit record, not a code change | — | §20 |
| **D37** | Sales analytics | Should demand/sales analytics exclude `PAYMENT_FAILED`? | They **count it as a sale** (`status NOT IN ('RETURNED','CANCELLED')`) | Exclude / keep | — | **UNDECIDED** | Business + Finance | — | Changes reported revenue and demand figures | — | P0-5A finding, deliberately not fixed |
| **D38** | Connection pool | Confirm dev/prod pools moving from an effective 10 to the configured 20 | P0-5B fixed the binding | Confirm / re-tune | — | **TECHNICAL** | Ops | — | Connection budget (§33) | — | Needs ops confirmation, not business policy |
| **D39** | Reversal semantics | Should a reversed sale ever be re-postable? | **No longer is**, as of P0-5B | Keep / allow re-post | — | **UNDECIDED** | Finance | — | Ratifies shipped behaviour | — | Changes what reversal means operationally |

**Divergence from the starter statuses.** The task's example table marked D2, D3, D4, D9, D11, D15,
D19 and D21 as `UNDECIDED`. Each depends on an unresolved decision, so Part A5's rule makes them
`BLOCKED`. The rule was followed. D13, D20, D25 and D26 have no unresolved dependency and are
genuinely `UNDECIDED` — they can be answered today.

---

## 3. Payment method decisions

### CARD — `BLOCKED` on D1, D2

**Verified today:** `Payment.tsx` renders a full card form — number, name on card, expiry, CVV —
validates it client-side (`digits.length >= 16 && cardExpiry.length === 5 && cardCvv.length >= 3`),
and enables "Place Order" once it passes. **None of those values is ever sent anywhere.** They live
in React state and are discarded when the component unmounts.

A customer today types a real card number and a real CVV into a form that does nothing with them,
and receives a confirmed order. Whatever D1 and D3 decide, **D32** should be answered first.

### UPI — `BLOCKED` on D1, D2

Same shape: a VPA field validated with `upiId.includes("@")`, never transmitted.

### COD — `UNDECIDED`

COD is the one method that needs **no gateway**, so D5 is independent of D1. If COD alone were
approved, a complete payment domain could be built and exercised — attempt records, state machine,
idempotency, AR settlement — with no vendor decision at all. That is reflected in the proposed
sequence (§35).

### PARTIAL — `UNDECIDED` (amount `BLOCKED` on D7)

The UI offers "Pay 50% Now" and computes `partialAmount = grandTotalRupees / 2` — a float division
of a float rupee value. For a total of ₹13,955.21 that yields ₹6,977.605, which is not expressible
in paise. The rounding rule is part of **D8**; the existing UI value is not authoritative.

---

## 4. Payment timing — `BLOCKED` on D1

The application today is unambiguously **Model B**, with the payment step absent:

```text
Checkout → Order → Inventory consumed → [ no payment ]
```

| | Model A (payment first) | Model B (order first — today's shape) |
|---|---|---|
| Money collected | Before the order exists | After the order exists |
| Order confirmed | On payment success | On creation (today: immediately) |
| Inventory committed | After payment | At creation (today) |
| Fulfilment may begin | After the order is created | After the order is created |
| If payment fails | No order was ever created | An order and a stock deduction already exist — **D10** |

Model A would be a larger change than it appears: P0-3's idempotency is keyed on order creation
(`clientOrderReference`), and moving payment before order creation means the idempotency boundary
has to cover a payment attempt that has no order yet. That is a design consequence, not a
recommendation — see §29.

---

## 5. Inventory timing — `BLOCKED` on D9

**Verified current behaviour:** `reserveInventory` runs inside `placeOrder`'s transaction, takes
`SELECT … FOR UPDATE` on each product row in ascending id order, and the committed order row *is*
the deduction (availability is derived, `initial_stock − away + handled back`). `ORDER_PLACED` is in
the consuming set. There is no reservation concept.

| Option | Description | P0-2 assumptions that would have to change |
|---|---|---|
| **A** Release immediately on failure | A failed payment moves the order to a non-consuming status | **None.** `PAYMENT_FAILED` is already non-consuming, so the derived model releases the units with no schema change. The change is to the order lifecycle, not to inventory. |
| **B** Hold until timeout | Stock stays committed for a defined window, then releases | **None structurally**, but it needs a timeout job and a new non-consuming terminal status. The derived model has no notion of "expires at", so the expiry would live on the order. |
| **C** Reservation semantics | A hold that is neither an order nor free stock | **Substantial.** P0-2 deliberately has no `reserved_quantity` and no reservation table. Availability would stop being derivable from order rows alone, and the `FOR UPDATE` serialisation point would have to cover reservations too. This is the option that invalidates the current design. |
| **D** Other approved process | — | To be assessed |

Options A and B fit the existing architecture. **Option C does not**, and choosing it means
revisiting P0-2 rather than extending it. That is the single most important technical consequence in
this document, and it is why D10 cannot be deferred (§23).

---

## 6. Payment failure — `BLOCKED` on D1, D2

Nothing in this table is implemented. Each row is a question, with the current state recorded for
contrast.

| Scenario | Order state | Payment state | Inventory | Customer sees | Retry | Accounting |
|---|---|---|---|---|---|---|
| Declined | ? | ? | ? — see D10 | ? | ? | ? |
| Failed (technical) | ? | ? | ? | ? | ? | ? |
| Pending | ? | ? | ? | ? | ? | ? — D29 |
| Timeout | ? | ? | ? | ? | ? | ? — D29 |
| Abandonment | ? | ? | ? | ? | ? | ? |
| Gateway unavailable | ? | ? | ? | ? | ? | ? |

**Current behaviour for contrast.** The browser calls `POST /order/record-failed` when
`/order/place` errors. That writes a `customer_order` row with status `PAYMENT_FAILED`, which
consumes no stock, posts no journal, and — since P0-5B — is not sale-eligible. The client offers a
"retry payment" link for such orders; the admin screen offers them no action at all. Whether a
failed order can be paid later is part of **D9**.

---

## 7. Payment success — `BLOCKED` on D1, D2

Open, in this order:

1. **When is an order paid?** On authorization, on capture, on webhook confirmation, or on bank
   settlement. These can be days apart.
2. **May fulfilment begin at that point?** D12. *Paid does not automatically mean shippable* — a
   business may want a fraud hold, an address check, or cleared funds.
3. **Does inventory stay committed?** It does today and nothing would change it, but this should be
   stated rather than assumed.
4. **When does accounting recognise collection?** D19. `Dr Cash/Bank / Cr AR` — but triggered by
   which event.
5. **Does success require webhook confirmation?** D21. If the browser returns success and the
   webhook never arrives, what is true.

---

## 8. COD — `BLOCKED` on D5

### COD fee — D6

Today: `dueNow = grandTotalRupees + 50` in the browser. The backend never sees it; `shipping_value`,
`discount_value` and `round_off` are `0` on all 7 live orders and are never written at checkout. If
the fee is real it needs a home on the order, a paise representation, and a GST treatment (**D27**).

### COD collection — D17

The task's constraint is recorded verbatim: **do not assume `DELIVERED = CASH_COLLECTED`.**

Today `DELIVERED` is settable by any `ADMIN_GST` holder (P0-5A) and records no money. Candidate
events, each with a different accounting moment:

| Candidate | Cash is recognised when | Risk |
|---|---|---|
| Courier marks delivered | The order status changes | Cash may be with the courier, not the business |
| Courier remits | A remittance record is created | Needs a remittance concept that does not exist |
| Bank credit | The settlement lands | Furthest from the customer event, closest to the truth |

Between delivery and bank credit the money is a real asset held by someone else. Whether that
deserves its own account is part of §30.

### COD refusal — D18

Undefined today; the nearest existing status is `CANCELLED`, which releases stock and (since P0-5B)
reverses the sale. Open: order state, inventory, accounting, return-to-origin, customer liability
for shipping, and whether any refund applies.

---

## 9. Partial payment — `BLOCKED` on D7

Open: percentage or fixed amount; whether 50% is right; what event collects the balance; whether
shipment may precede full payment; whether COD may carry the balance of a partial order;
cancellation and refund behaviour; AR treatment of the outstanding balance; and the posting model.

**Technical consequence, stated not decided:** partial payment is the decision that forces
**one order → many payment attempts**. If D7 is approved, a one-payment-per-order schema is wrong
from the start. If it is rejected, the simpler shape holds. This is why D7 cannot be deferred past
the schema (§23).

---

## 10. Cancellation — D13 `UNDECIDED`, D14 `BLOCKED`

**Verified current behaviour (P0-5A + P0-5B):**

- Cancellable only from `ORDER_PLACED` and `PACKED`. `CANCELLED` is terminal and cannot be reversed.
- Requires `ADMIN_GST`, enforced at both the controller and the service.
- Releases stock exactly once (the status is non-consuming).
- Reverses the posted SALE journal through the existing reversal mechanism, idempotently.
- **Fails entirely if the current accounting period is closed** — D26.

Open: whether that cancellable window is right; who may cancel (today only an administrator — the
customer cannot); whether paid orders may be cancelled (D14); whether dispatched or delivered orders
may be; and the refund consequence (D15).

---

## 11. Refunds — `BLOCKED` on D13, D14, D15

The three concepts the task requires be kept separate, with their current status:

| Concept | Exists today | Meaning |
|---|---|---|
| **Payment reversal** | **No** | Money returned to the customer through the provider |
| **Accounting reversal** | **Yes** — used by cancellation since P0-5B | Revenue and receivable taken back out of the books. No money moves |
| **GST credit-note adjustment** | **Yes** — `GstCreditNoteService`, used by returns | Statutory tax adjustment |

An accounting reversal is *not* a refund. Today it cannot be, because nothing was ever collected.

**An existing unfunded obligation.** `OrderExchangeService` already computes a settlement direction
and stores `REFUND_TO_CUSTOMER` on `order_exchange_request.settlement_direction`. **Nothing reads
it.** It is a label describing money that ought to move, with no mechanism to move it. Whether the
payment build must settle these, including historical ones, is part of D15.

Open for each refund trigger (cancellation, return, failed delivery, partial, COD), plus:
authorization, timing, method, gateway vs manual, and idempotency.

---

## 12. Accounts receivable — D19 `BLOCKED`, D20 `UNDECIDED`

**Verified:** AR (`1100`) = **1,979,633 paise = ₹19,796.33**. Debits 7,918,523; credits 5,938,890.
**Every credit came from a `REVERSAL` entry** — not one rupee from a collection.
`credit(RECEIVABLE, …)` appears nowhere in the codebase. Account `1010 Cash` is declared and has
**zero** ledger rows.

D20 must determine what this balance represents: legitimate outstanding receivables, historical or
test data, a reconciliation discrepancy, an amount to collect, an amount to write off, or another
treatment. **No entry was made in P0-6.**

Note for D20: `/sales-order/stats` reports `totalRevenue` 1,979,628 against the ledger's 1,979,633 —
a **5-paise** divergence between order totals and posted revenue. Small, but it is exactly the kind
of gap reconciliation (D23) must be able to explain.

---

## 13. Accounting treatment — mostly `BLOCKED`

Intended future treatment, all unresolved. Account mapping is in §30.

| Event | Exists | Decision |
|---|---|---|
| SALE | Yes | D35 — real-time or batch |
| PAYMENT COLLECTION | No | D11, D19 |
| PAYMENT FAILURE | No | D9 — whether it posts at all |
| CANCELLATION | Yes (reversal) | D26, D39 |
| REFUND | No | D15 |
| PARTIAL PAYMENT | No | D7, D19 |
| AR SETTLEMENT | No | D19 |
| GATEWAY FEE | No | D24 |
| SETTLEMENT | No | D23 |

---

## 14. GST / tax — `BLOCKED`

**GST calculation is not redesigned and was not touched.** What exists: CGST/SGST vs IGST from place
of supply, transaction-time snapshots, output-tax records written at checkout, a movement ledger,
credit and debit notes, period locks.

Decisions requiring **tax approval**:

| # | Question | Blocked by |
|---|---|---|
| D27 | Is a COD handling fee a taxable supply? At what rate, under what SAC, with what place of supply? | D6 |
| D24/D27 | Is input GST on gateway commission creditable? | D2 |
| D28 | Does a refund reuse the existing credit-note flow, or need a separate adjustment? | D15 |
| — | Cancellation **after** an invoice has been issued — credit note or cancellation of the invoice? | D13 |
| — | Does a partial payment change the time of supply? | D7 |

No tax treatment is invented in this document.

---

## 15. Payment idempotency — `BLOCKED` on D1, D2

The architectural distinction the task requires be preserved, and which this document preserves:

```text
clientOrderReference          →  ORDER CREATION idempotency   (P0-3, implemented)
payment idempotency key       →  PAYMENT ATTEMPT idempotency  (not implemented)
```

**These must not be the same value.** P0-3's reference identifies one *checkout attempt* and is
enforced by `ux_customer_order_client_reference`, a partial unique index on `customer_order`. A
payment key identifies one *payment attempt*, and one order may legitimately have several — a
retry after a decline, a second method after the first failed, or (if D7 is approved) a deposit and
a balance. Reusing the order reference would make a second legitimate payment look like a duplicate.

Business requirements to determine: what counts as a retry versus a new attempt; what a duplicate
attempt means; whether concurrent attempts for one order are permitted (D30); how gateway-initiated
retries are treated; and how duplicate webhook deliveries are recognised (D21).

---

## 16. Webhooks — `BLOCKED` on D1, D2

None exist. To determine: whether the gateway webhook is authoritative for success, failure,
pending, refund and chargeback; what happens when the browser and the webhook disagree; and **who
owns webhook operational failure** — a missed webhook is a payment the business took and the
application does not know about, which is an operations question before it is a technical one.

---

## 17. Reconciliation — `BLOCKED` on D1, D2, D19

```text
Gateway ↕ Payment records ↕ Orders ↕ Accounting ↕ Bank settlement
```

Of that chain, **only Orders ↕ Accounting exists today**, and it is built by batch backfill rather
than in real time (D35). To determine: frequency; the identifiers that join each hop; the settlement
ingestion process; discrepancy handling; ownership; and operational reporting.

The existing 5-paise divergence (§12) and the pre-existing Cucumber reconciliation failure
(`Revenue: ledger vs orders`) are both evidence that this chain needs a defined process, not an
assumed one.

---

## 18. Closed-period cancellation — D26 `UNDECIDED`

**Live behaviour today, shipped in P0-5B:** cancelling an order reverses its posted sale in the same
transaction. A reversal is dated *today*, so it lands in the current period. If the **current**
period is closed, `assertPeriodOpen` throws, the exception propagates, and **the entire cancellation
rolls back**. The order stays live rather than becoming a cancelled order with revenue still
standing.

| Option | Description | Main consequences |
|---|---|---|
| **A** | Reject the cancellation (**current behaviour**) | Ledger always consistent. Operations blocked while a period is closed. |
| **B** | Allow operational cancellation, defer the accounting adjustment | Operations never blocked. Requires a deferred-adjustment queue that does not exist, and a window in which stock is released but revenue still stands. |
| **C** | Use a credit-note or other approved adjustment process | Reuses an existing statutory mechanism. Changes what cancellation means in the books. |
| **D** | Other | — |

**No option is selected.** P0-5B chose A as the conservative default precisely because it never
silently corrupts the ledger, and flagged it for exactly this decision.

---

## 19. `PAYMENT_FAILED` revenue — D25 `UNDECIDED`

**Live behaviour today, shipped in P0-5B:** `OrderStatus.isSaleEligible()` returns false for
`CANCELLED` and `PAYMENT_FAILED`, so neither can be posted as revenue by `postSale` or by a backfill.

The reasoning recorded at the time: both are states in which the business never supplied anything,
and booking revenue and a receivable for either would overstate both. It is **not** a payment test —
an order that was supplied remains a sale whether or not money has arrived.

**Status must be set explicitly to `approved`, `rejected`, or `requires accounting review`.** No
order currently holds `PAYMENT_FAILED`, so nothing historical changed either way. The implementation
was not altered in P0-6.

---

## 20. Historical duplicate SALE journals — D36 `UNDECIDED`

**Verified:** 7 orders · **28 SALE journals** (exactly 4 per order) · 21 marked `REVERSED` · 1
`POSTED` each · 21 `REVERSAL` entries · 187 journal lines.

### Why the duplicates occurred

`ux_journal_source` is **partial**:

```sql
UNIQUE (source_type, source_id) WHERE source_id IS NOT NULL AND status = 'POSTED'
```

and the application's existence check asked the same question. Once an entry is reversed its status
is no longer `POSTED`, so both the index and the check stop seeing it and the next backfill posts
the sale again. Backfill was run four times with manual reversals in between: four journals per
order.

### Why the current net position is correct

Each order has exactly one `POSTED` sale; the other three are `REVERSED` and each has a matching
mirror-image `REVERSAL` entry. Arithmetically: AR debits 7,918,523 ≈ 4 postings, credits 5,938,890
≈ 3 reversals, leaving 1,979,633 — one net sale per order. Every journal balances, header and lines
alike.

### Why cleanup is outside P0-6

Deleting or rewriting them would destroy audited history, which both P0-5B and this phase forbid.
The net position already reflects reality, so there is no accounting defect to correct — only a
tidiness question about entries that exist for a documented reason.

### Whether owners should formally review

**Recommended, as an audit record rather than a code change.** The duplicates are evidence of a real
defect that has since been fixed (P0-5B added an any-status existence check, so a reversed sale is
never resurrected). Finance may reasonably want that sequence documented and signed off. That is
D36.

---

## 21. Decision records

### Part A3 — `DECIDED` records

**None.** No decision in this register is `DECIDED`, because no business, product, accounting or tax
documentation exists in the repository that approves any of them. The Part A3 template is therefore
unused, and remains available for the first approval.

### Part A4 — `UNDECIDED` records

Full records follow for the decisions that have **no unresolved dependency** and can therefore be
answered immediately. `BLOCKED` decisions get their record when their blocker resolves — writing
options for them now would mean inventing the premise they depend on.

---

## D1 — Online Payment Availability

### Decision Question

> Will CARD and UPI result in real money movement through a payment provider?

### Current Verified Behavior

Zero occurrences of `gateway`, `webhook`, `paymentStatus`, `transactionId`, or any provider name in
`src/main/java`. Zero customer-payment tables. Zero payment columns on `customer_order`. The
checkout screen collects card and UPI credentials and discards them. `paymentMethod` is sent and
silently dropped. An order reaches `ORDER_PLACED` and consumes stock with nothing collected.

### Why Unresolved

No business or product documentation states whether Veloria intends to take money online.

### Options

| Option | Description | Main Consequences |
|---|---|---|
| A | Yes — build online collection | Unlocks D2–D4, D9–D12, D21–D24. Requires a vendor decision, PCI scoping, webhook operations, reconciliation. |
| B | No — catalogue and manual settlement only | The payment domain is not built. The checkout UI must stop presenting card/UPI as payment methods (**D32** becomes urgent). AR stays uncollected by design (**D20**). |
| C | Defer — disable the methods meanwhile | Smallest immediate work, but leaves a customer-facing UI that claims capability the system lacks. |

### Status

```text
UNDECIDED
```

### Required Owner

> Business

### Blocking Implementation

> The entire payment domain: schema, state machine, gateway integration, webhooks, reconciliation,
> accounting collection, refunds. 23 of 39 register entries depend on this directly or transitively.

### Dependencies

> None — this is the root.

---

## D5 — COD Support

### Decision Question

> Is Cash on Delivery a genuine offer?

### Current Verified Behavior

A COD option in the UI that never reaches the backend. No collection event, no cash account
movement, no remittance concept. `1010 Cash` has zero ledger rows.

### Why Unresolved

No documentation states whether COD is offered, who physically collects, or how cash reaches the
bank.

### Options

| Option | Description | Main Consequences |
|---|---|---|
| A | Supported | Needs D6 (fee), D17 (collection event), D18 (refusal). **Needs no gateway** — could be built and proven first. |
| B | Not supported | Remove the option from checkout. Simplifies the initial payment model considerably. |
| C | Temporarily disabled | UI must say so rather than accepting the selection silently. |

### Status

```text
UNDECIDED
```

### Required Owner

> Business, with Finance and Operations

### Blocking Implementation

> D6, D17, D18, D27. Also determines whether the first payment implementation can proceed without a
> vendor decision.

### Dependencies

> None — independent of D1.

---

## D7 — Partial Payment Support

### Decision Question

> Is partial payment offered, and is it a real commitment rather than aspirational UI?

### Current Verified Behavior

The UI offers "Pay 50% Now" and computes `grandTotalRupees / 2` in floating point. Nothing reaches
the backend. There is no outstanding-balance concept anywhere.

### Why Unresolved

No documentation establishes the offer, the split, or how the balance is collected.

### Options

| Option | Description | Main Consequences |
|---|---|---|
| A | Supported | Forces **one order → many payments** in the schema from day one. Needs D8 (amount and rounding), a balance concept, AR ageing, and shipment-before-full-payment policy. |
| B | Not supported | The payment model can be one-to-one initially. Materially simpler. |
| C | Temporarily disabled | UI must stop offering it. |

### Status

```text
UNDECIDED
```

### Required Owner

> Business, with Finance

### Blocking Implementation

> D8, D16, and the payment-to-order cardinality — a schema decision that is expensive to reverse.

### Dependencies

> None.

---

## D13 — Cancellable States

### Decision Question

> Which order states may be cancelled, and by whom?

### Current Verified Behavior

P0-5A's server-side transition table permits cancellation only from `ORDER_PLACED` and `PACKED`.
`CANCELLED` is terminal. Only `ADMIN_GST` may cancel; the customer cannot cancel at all. Cancelling
releases stock once and reverses any posted sale (P0-5B).

### Why Unresolved

The window was derived from the admin screen's existing buttons, not from a stated policy. Whether
it is the *intended* policy has never been confirmed, and customer-initiated cancellation has never
been considered.

### Options

| Option | Description | Main Consequences |
|---|---|---|
| A | Keep the current window | No change. Customers must contact support to cancel. |
| B | Widen (e.g. allow through `IN_TRANSIT`) | Needs a recall process with the courier; stock returns only when goods do. |
| C | Narrow, or add customer self-cancellation | Changes the transition table and the authorization model. |

### Status

```text
UNDECIDED
```

### Required Owner

> Business, with Operations

### Blocking Implementation

> D14, D15. Answerable today without any payment decision.

### Dependencies

> None.

---

## D20 — Existing Accounts Receivable

### Decision Question

> What does the ₹19,796.33 receivable represent, and what should happen to it?

### Current Verified Behavior

AR = 1,979,633 paise. Debits 7,918,523, credits 5,938,890, every credit from a `REVERSAL`. No
collection has ever been posted. `1010 Cash` is unused. The balance corresponds to the seven
historical orders, net of the duplicate-posting cleanup described in §20.

### Why Unresolved

Nobody has established whether these are real amounts owed by real customers or the residue of
development and test activity.

### Options

| Option | Description | Main Consequences |
|---|---|---|
| A | Legitimate receivables to collect | They must appear in collection and reconciliation once payment exists. |
| B | Historical/test data to write off | Needs an approved write-off entry, in an open period, with an audit reason. |
| C | Reclassify as an opening balance | Needs an opening-balance treatment; the mechanism exists (`/accounting/opening-balances`). |
| D | Retain pending reconciliation | Defers, but the figure keeps appearing in every financial report. |

### Status

```text
UNDECIDED
```

### Required Owner

> Finance

### Blocking Implementation

> Nothing technically, but it distorts every financial report until resolved, and D19's design
> should know whether it is settling real debt.

### Dependencies

> None. Related to D36.

---

## D25 — `PAYMENT_FAILED` Revenue Exclusion

### Decision Question

> Is excluding `PAYMENT_FAILED` orders from revenue the approved business rule?

### Current Verified Behavior

`OrderStatus.isSaleEligible()` returns false for `CANCELLED` and `PAYMENT_FAILED`. Shipped in P0-5B
and live today. No order currently holds `PAYMENT_FAILED`, so no historical figure changed.

### Why Unresolved

It was an engineering judgement, explicitly flagged at the time for ratification. It is not a
payment test, but it does decide that a failed checkout is not a sale.

### Options

| Option | Description | Main Consequences |
|---|---|---|
| A | Approve | Current behaviour stands. |
| B | Reject | `PAYMENT_FAILED` orders would book revenue and a receivable for goods never supplied. |
| C | Accounting review | Defer to Finance with the reasoning above. |

### Status

```text
UNDECIDED
```

### Required Owner

> Finance

### Blocking Implementation

> Nothing new, but it ratifies or reverses behaviour already in production.

### Dependencies

> None. Related to D37, which currently counts `PAYMENT_FAILED` as a sale in *analytics* — the two
> should be answered together, since they presently disagree.

---

## D26 — Closed-Period Cancellation

Covered in full at §18, with the same four options and `UNDECIDED` status. Owner: Finance with
Operations. Blocks nothing technically; it ratifies or reverses live behaviour.

---

## 22. Decision dependency graph

```text
D1 Online Payment ..................................... UNDECIDED  ← ROOT
 ├── D2 Gateway ....................................... BLOCKED
 │    ├── D3 CARD ..................................... BLOCKED ──┐
 │    ├── D4 UPI ...................................... BLOCKED   │→ D32 Card data in browser (UNDECIDED)
 │    ├── D21 Webhooks ................................ BLOCKED
 │    │    └── D29 Payment Timeout .................... BLOCKED
 │    ├── D22 Payment Idempotency ..................... BLOCKED
 │    │    └── D30 Multiple Attempts .................. BLOCKED
 │    ├── D23 Reconciliation .......................... BLOCKED
 │    └── D24 Gateway Fees ............................ BLOCKED
 │         └── (input GST creditability) ← Tax
 │
 ├── D9 Payment Failure ............................... BLOCKED
 │    ├── D10 Inventory After Failure ................. BLOCKED  ← widest technical impact
 │    └── D29 Payment Timeout ......................... BLOCKED
 │
 └── D11 Payment Success .............................. BLOCKED
      ├── D12 Fulfilment .............................. BLOCKED
      ├── D19 AR Settlement ........................... BLOCKED
      │    ├── D23 Reconciliation ..................... BLOCKED
      │    └── D35 Sale posting timing ................ UNDECIDED  (constrains D19)
      └── D14 Paid Cancellation ....................... BLOCKED
           └── D15 Refund ............................. BLOCKED
                ├── D16 Partial Refund ................ BLOCKED
                └── D28 Refund GST .................... BLOCKED ← Tax

D5 COD ................................................ UNDECIDED  ← independent of D1
 ├── D6 COD Fee ....................................... BLOCKED
 │    └── D27 COD GST ................................. BLOCKED ← Tax
 ├── D17 COD Collection ............................... BLOCKED
 │    └── D19 AR Settlement (shared) .................. BLOCKED
 └── D18 COD Refusal .................................. BLOCKED

D7 Partial Payment .................................... UNDECIDED  ← independent of D1
 ├── D8 Partial Amount ................................ BLOCKED
 ├── D16 Partial Refund ............................... BLOCKED
 └── D19 AR Settlement (shared) ....................... BLOCKED

D13 Cancellation ...................................... UNDECIDED  ← answerable today
 └── D14 Paid Cancellation ............................ BLOCKED

Independent, answerable today (no payment decision required):
    D20 Existing AR .................................... UNDECIDED  (related: D36)
    D25 PAYMENT_FAILED revenue ......................... UNDECIDED  (related: D37)
    D26 Closed-period cancellation ..................... UNDECIDED
    D31 Currency ....................................... UNDECIDED
    D32 Card data in browser ........................... UNDECIDED  (urgent if D1 = No)
    D34 Anonymous accounting reads ..................... UNDECIDED
    D35 Sale posting timing ............................ UNDECIDED
    D36 Historical duplicate journals .................. UNDECIDED
    D37 Analytics count failed orders as sales ......... UNDECIDED
    D39 Reversal re-post semantics ..................... UNDECIDED

Technical only (no business policy):
    D33 Request strictness ............................. TECHNICAL
    D38 Connection pool confirmation ................... TECHNICAL
```

---

## 23. Approval summary

| Status | Count | IDs |
|---|---:|---|
| DECIDED | **0** | — |
| UNDECIDED | **14** | D1, D5, D7, D13, D20, D25, D26, D31, D32, D34, D35, D36, D37, D39 |
| BLOCKED | **23** | D2, D3, D4, D6, D8, D9, D10, D11, D12, D14, D15, D16, D17, D18, D19, D21, D22, D23, D24, D27, D28, D29, D30 |
| TECHNICAL | **2** | D33, D38 |
| **Total** | **39** | |

### Decisions required before payment-domain implementation

These change the schema, the state machine, the order/payment lifecycle, inventory behaviour or the
accounting integration. **None can be deferred without creating an assumption that a later migration
or state-machine redesign would have to undo.**

| ID | Why it must come first |
|---|---|
| **D1** | Determines whether the domain exists at all. |
| **D5** | COD needs no gateway; if approved it can be the first implementation, which changes the build order. |
| **D7** | Decides one-order-to-one-payment versus one-to-many. Reversing this later is a schema migration. |
| **D8** | Fixes the rounding rule in paise. A wrong assumption becomes wrong money. |
| **D9, D10** | D10 decides whether P0-2's derived inventory model survives (option C invalidates it). |
| **D11, D12** | Define the "paid" boundary the state machine is built around. |
| **D13, D14** | Determine which transitions the machine must support. |
| **D19, D35** | Decide the accounting trigger and whether posting is real-time — payment postings against a batch-built ledger would be inconsistent. |
| **D22, D30** | Fix the idempotency key and whether multiple attempts per order exist. Both are structural. |
| **D25, D26, D39** | Ratify or reverse behaviour already shipped in P0-5B. |

### Decisions required before gateway integration

Blocking gateway work specifically, and only after D1 = yes: **D2** (provider), **D3**, **D4**
(methods), **D21** (webhook authority), **D23** (reconciliation), **D24** (fees), **D29**
(timeout), **D32** (whether the application ever handles card data).

### Decisions that can genuinely be deferred

Only these, and only because deferring them creates no structural assumption:

| ID | Why deferral is safe |
|---|---|
| **D20** | An opening-balance treatment applied later changes no schema and no code path. |
| **D27, D28** | Tax treatment of fees and refunds affects the *values* posted, not the structure that posts them — provided the structure can carry a tax component, which the existing GST model already does. |
| **D34** | A security lock-down of accounting reads is additive and independent of payment. |
| **D36** | An audit review of historical journals changes no code. |
| **D37** | Changing an analytics query alters reported figures, not structure. |
| **D31, D33, D38** | Validation and configuration; no structural assumption. |

**Explicitly not safe to defer:** D7 and D8 (cardinality and rounding), D10 (inventory model), D11
(paid boundary), D22 (idempotency key). Each would leave an assumption baked into the schema or the
state machine.

---

# PART B — IMPLEMENTATION PREPARATION

*Everything below is technical preparation. Nothing here decides business policy. Proposals are
labelled as proposals.*

## 24. Current-state architecture

Verified by inspection of source, live schema, and the running application.

```text
Customer
   ↓
Cart                       bag rows, server-priced, integer paise
   ↓
Checkout                   address selected; GST previewed
   ↓
Payment selection          ◄── EXISTS ONLY IN THE BROWSER (§25)
   ↓
POST /client/order/place
   │  ├─ clientOrderReference → P0-3 idempotency (partial unique index)
   │  ├─ reserveInventory()   → P0-2 FOR UPDATE, ascending product id
   │  └─ paymentMethod        → silently discarded
   ↓
Order created              status = ORDER_PLACED
   ↓
Inventory consumed         ◄── COMMITTED HERE, UNPAID
   ↓
GST output tax + movements written in real time
   ↓
[ NO PAYMENT EVENT ]       ◄── NOTHING COLLECTS, AUTHORIZES OR RECORDS MONEY
   ↓
Order lifecycle            P0-5A server-side state machine, ADMIN_GST
   ↓
Accounting                 Dr 1100 AR / Cr 4010 Sales / Cr 2100-2120 Output GST
                           …posted only when backfill() runs (D35)
                           cancellation reverses it (P0-5B)
```

**Where payment does not exist:** everywhere after "Payment selection". No authorization, no
capture, no callback, no settlement, no collection posting, and no field recording how the customer
intended to pay.

## 25. Current payment UI reality

`veloria.frontend/frontend-client/src/views/Payment.tsx`, verified line by line.

| Method | Fields displayed | Amount displayed | Sent to backend |
|---|---|---|---|
| **CARD** (default) | number, name, expiry, **CVV** | grand total | **nothing** |
| **UPI** | VPA | grand total | **nothing** |
| **COD** | — | `grandTotalRupees + 50` | **nothing** (the +50 never leaves the browser) |
| **PARTIAL** | card or UPI sub-form | `grandTotalRupees / 2` | **nothing** |

| Aspect | Reality |
|---|---|
| Values sent | `deliveryLocation`, `currency`, `paymentMethod`, `items`, `clientOrderReference` |
| Values received by backend | `deliveryLocation`, `currency`, `items`, `clientOrderReference` |
| Silently discarded | `paymentMethod` — `PlaceOrderRequest` has no such field and Spring Boot ignores unknown properties by default |
| Never transmitted at all | `cardNumber`, `cardName`, `cardExpiry`, `cardCvv`, `upiId`, `partialUpiId`, `partialCard*` |
| Money representation | `subtotalPaise` is correct integer paise; `grandTotalRupees`, `partialAmount` and `dueNow` are **float rupees** |
| Customer-facing claim | The screen presents four working payment methods, validates card and UPI input, shows an amount due, and returns a confirmed order |

**Runtime evidence.** A probe during P0-4 posted `paymentMethod: "CARD"`, a fabricated
`razorpayPaymentId`, and `amountPaid: 99999`. All three were accepted and discarded; the order was
created at the server-computed total with status `ORDER_PLACED`. (That probe order was deleted; the
database is at its original 7 rows.)

**Conclusion, on repository and runtime evidence: no real payment collection exists.** The UI was
not modified in P0-6.

## 26. Current order / inventory / accounting flow

| Event | Order state | Inventory effect | Accounting effect | Payment effect |
|---|---|---|---|---|
| Order created | `ORDER_PLACED` | **Consumes** (committed at COMMIT) | None at checkout; a SALE journal only when `backfill()` runs | `NOT IMPLEMENTED` |
| Order cancelled | `CANCELLED` (terminal) | **Releases**, exactly once | Reverses the posted SALE via the existing mechanism (P0-5B); fails if the current period is closed | `NOT IMPLEMENTED` |
| Order packed | `PACKED` | Remains consumed | None | `NOT IMPLEMENTED` |
| Order dispatched | `IN_TRANSIT` | Remains consumed | None | `NOT IMPLEMENTED` |
| Out for delivery | `OUT_FOR_DELIVERY` | Remains consumed *(fixed in P0-5A; previously released)* | None | `NOT IMPLEMENTED` |
| Delivered | `DELIVERED`, sets `delivered_at` | Remains consumed | None | `NOT IMPLEMENTED` — no cash event |
| Payment failed | `PAYMENT_FAILED` via `record-failed` | Holds none | None; not sale-eligible since P0-5B | `NOT IMPLEMENTED` |
| Return requested | `READY_TO_PICKUP`; `reason_for_return` set on every line | Remains consumed *(fixed in P0-5A; previously released and double-counted)* | None | `NOT IMPLEMENTED` |
| Return inspected | `RETURNED` / `PARTIALLY_RETURNED`; `return_condition` set | Units credited back on inspection | GST credit note issued | `NOT IMPLEMENTED` — no refund |

## 27. Proposed payment state machine

> ### **TECHNICAL PROPOSAL — NOT APPROVED BUSINESS POLICY**
> Nothing below exists. Every row depends on decisions that are `UNDECIDED` or `BLOCKED`.

| State | Meaning | Entry event | Exit event | Money moved | Order impact | Inventory impact | Accounting impact | Webhook can produce | Terminal |
|---|---|---|---|---|---|---|---|---|---|
| `INITIATED` | An attempt record exists; customer not yet sent to the provider | Checkout requests payment | Redirect / SDK handoff | No | None | None | None | No | No |
| `PENDING` | Customer is with the provider; outcome unknown | Handoff complete | Provider reports an outcome | No | None | Per **D10** | None | Yes | No |
| `AUTHORIZED` | Funds confirmed and held, not taken | Provider authorises | Capture or void | Held | Per **D11** | None | None (a hold is not revenue) | Yes | No |
| `CAPTURED` | Money taken | Capture succeeds | Refund | **Yes** | Paid, per **D11** | Stays committed | Collection posting, per **D19** | Yes | No — refunds may follow |
| `FAILED` | The attempt did not succeed | Provider declines or errors | A new attempt | No | Per **D9** | Per **D10** | None | Yes | Yes *for this attempt* |
| `CANCELLED` | Abandoned before completion | Customer abandons, or timeout | — | No | Per **D9**, **D29** | Per **D10** | None | Yes | Yes for this attempt |
| `REFUNDED` | The full captured amount returned | Refund completes | — | **Yes, reversed** | Per **D15** | Unchanged by the refund | Refund posting, per **D15** | Yes | Yes |
| `PARTIALLY_REFUNDED` | Part returned, a balance remains captured | Partial refund completes | Further refunds | **Yes, partial** | Per **D16** | Unchanged | Refund posting | Yes | No |

Additional states that become necessary only if certain decisions land a particular way:

| State | Required if | Why |
|---|---|---|
| `AWAITING_COD_COLLECTION` | D5 = supported | A COD order has no payment attempt until delivery |
| `COLLECTED_COD` | D5 = supported | Cash received is not a gateway capture; **D17** defines the event |
| `PARTIALLY_CAPTURED` | D7 = supported | A deposit taken with a balance outstanding |
| `EXPIRED` | D29 | A pending attempt that timed out |
| `DISPUTED` / `CHARGEBACK` | D2 answered | Providers surface these and they move AR and revenue |

**Proposed principles, for review:** an attempt is immutable once terminal (a retry creates a new
attempt); only `CAPTURED`, `COLLECTED_COD`, `REFUNDED` and `PARTIALLY_REFUNDED` post to the ledger;
and no state may be produced by an unauthenticated caller.

## 28. Proposed order / payment interaction

> **TECHNICAL PROPOSAL — NOT APPROVED BUSINESS POLICY**

**Online payment** — cardinality depends on **D7** and **D30**:

```text
Order 1 ──── * PaymentAttempt ──── 1 Gateway transaction
      (one-to-many only if D7 or D30 permit; otherwise one-to-one)
```

**COD** — no gateway, so the collection event is internal (**D17**):

```text
Order → Delivery → Collection event → AR cleared
                        ▲
                        └─ D17: delivery, remittance, or bank credit?
```

**Partial payment** — requires an outstanding-balance concept that does not exist:

```text
Order → Partial collection → Outstanding balance → Final collection
             (D8 amount)         (AR ageing, D19)      (D17 if COD carries it)
```

## 29. Payment idempotency requirements

> Preserved distinction: **`clientOrderReference` ≠ payment idempotency key.**

| Concern | Requirement | Depends on |
|---|---|---|
| Payment idempotency key | Client- or server-generated, one per *attempt*, distinct from the order reference. Must survive a retry and change for a genuinely new attempt | D22 |
| Gateway transaction id | Provider's own id, stored for reconciliation; shape is provider-specific | D2, D23 |
| Gateway order / payment id | Provider may issue both; both may be needed to reconcile | D2 |
| Duplicate webhook | Deduplicated on the provider's event id; handling must be idempotent | D21 |
| Retry | What counts as a retry rather than a new attempt | D22 |
| Concurrent attempts | Permitted or refused | D30 |
| Attempt lifecycle | Immutable once terminal; a new attempt rather than mutation | D22, D30 |

**Precedent available.** P0-3 solved an equivalent problem for order creation and the pattern
transfers: a partial unique index as the database authority, an application-level fast path, and
recovery handled *outside* the failing transaction. That is an architectural observation, not a
decision.

## 30. Accounting integration map

Chart of accounts as it exists today (26 accounts verified).

| Future event | Proposed posting | Accounts | Status |
|---|---|---|---|
| **SALE** | `Dr 1100 AR / Cr 4010 Sales / Cr 2100-2120 Output GST` | All exist | **Implemented** (timing per D35) |
| **COLLECTION** | `Dr 1010 Cash or 1020 Bank / Cr 1100 AR` | All exist | Not implemented — D11, D19 |
| **REFUND** | `Dr 4010 Sales (or a refunds contra) / Cr 1010/1020` | A contra-revenue account does **not** exist | `NEW ACCOUNT — REQUIRES ACCOUNTING APPROVAL` if a contra is wanted; otherwise 4010 — D15 |
| **REVERSAL** | Mirror image of the original | Existing mechanism | **Implemented** (P0-5B) |
| **GATEWAY FEE** | `Dr <fee expense> / Cr 1020 Bank` or netted at settlement | No gateway-charges account; `5900 Other Operating Expenses` could absorb it | `NEW ACCOUNT — REQUIRES ACCOUNTING APPROVAL` — D24 |
| **SETTLEMENT** | `Dr 1020 Bank / Cr <gateway clearing>` | No clearing account | `NEW ACCOUNT — REQUIRES ACCOUNTING APPROVAL` — D23 |
| **COD cash in transit** | `Dr <cash with courier> / Cr 1100 AR`, then `Dr 1020 / Cr <cash with courier>` | No such account | `NEW ACCOUNT — REQUIRES ACCOUNTING APPROVAL` — D17 |
| **COD fee income** | `Cr 4090 Other Income` or a new account | `4090 Other Income` exists | D6, D27 |

**No account was created.** Existing accounts, names and codes are unchanged.

## 31. Inventory integration map

| Event | Current inventory effect | Future decision | Technical impact |
|---|---|---|---|
| Order created | **Consumes** (`ORDER_PLACED` is in the consuming set) | D4 timing model | If Model A were chosen, the order — and therefore the deduction — happens after payment |
| Payment succeeds | No additional effect *(no payment event exists)* | D11 | None expected; stock is already committed |
| Payment fails | *No payment event exists* | **D10** | Option A: none (`PAYMENT_FAILED` is already non-consuming). Option B: a timeout job plus a new non-consuming status. **Option C: invalidates P0-2's derived model** |
| Payment pending | *No payment event exists* | D10, D29 | Whether stock stays committed while an outcome is unknown |
| Order cancelled | **Releases**, once | D13, D14 | None if the cancellable window is unchanged |
| Delivered | Remains consumed | D17 | None — COD collection is an accounting event, not an inventory one |
| Return requested | Remains consumed *(P0-5A fix)* | D15 | A refund does not move stock; inspection already does |
| Return inspected | Units credited on `return_condition` | D15, D28 | None |

**Inventory was not changed in P0-6.**

## 32. Security requirements

> **Raw card numbers and CVV must not be stored by the application unless a separately approved
> compliant architecture explicitly requires it.**

| Item | Requirement | Current state |
|---|---|---|
| Card data | Never stored; never logged; ideally never touches the application (hosted fields or provider SDK) | Collected in browser memory, **discarded**, never transmitted — **D32** |
| CVV | Never stored under any circumstance, even transiently | Collected in a form field and discarded |
| UPI VPA | Treat as PII; store only if a decision requires it | Collected, discarded |
| Payment tokens | Store provider tokens, never instruments | N/A |
| Gateway credentials | Never in source or committed config. Injected as environment variables with no defaults | Precedent exists: `AWS_ACCESS_KEY`/`AWS_SECRET_KEY` have no defaults and the app refuses to start without them |
| Webhook signatures | Verify before acting; preserve the raw body; enforce a replay window | N/A — D21 |
| Payment identifiers | Not secrets, but not guessable; never an authorization oracle | Precedent: P0-1's order-ownership fix, P0-3's "a denial must not double as an existence oracle" |
| Logs | Never log instruments, CVV, or full tokens | — |
| Audit records | Who moved money, when, immutable | `auditService` exists and is used by journal posting and reversal |
| Authorization | Payment mutations behind explicit authority at the service boundary | Precedent: P0-5A applies `@PreAuthorize` at both controller and service |
| PII | Minimise; do not compile across sources | — |

**Pre-existing finding, unchanged:** accounting *read* endpoints (`/accounting/trial-balance`,
`/profit-loss`, `/balance-sheet`, `/ledger`, `/journals`, `/reconciliation`) are anonymous —
verified `200` with no token. Payment endpoints must not follow that pattern. **D34.**

## 33. Hikari configuration finding

P0-5B discovered that the pool settings never reached the pool:

```text
@ConfigurationProperties("spring.datasource.hikari")
        ↓  bound to the RETURNED object…
routing datasource wrapper (AbstractRoutingDataSource — has no pool settings)
        ↓  …so nothing reached
inner Hikari datasource  → ran on defaults, always
```

Proven rather than inferred: a test setting `maximum-pool-size=30` still exhausted at 10. P0-5B
split the pooled datasource into its own bean so the properties bind to it.

| Measure | Value |
|---|---|
| Previous effective pool size | **10** (Hikari default) on every profile |
| Configured pool size | local 10 · dev 20 · prod 20 |
| Current effective pool size | As configured — local 10 (unchanged), dev/prod **20** |
| PostgreSQL `max_connections` | **100** (verified) |
| Expected application instances | **Not established** — required for the budget below |
| Approximate connection budget | 20 per instance. 2 instances = 40; 4 = 80; **5 instances would reach 100 and exhaust the server** |
| Deployment implication | dev/prod will hold twice as many connections as before. Confirm instance count against `max_connections` before deploying — **D38** |

**No further pool change was made in P0-6.**

## 34. Technical dependency map

| Future component | Depends on |
|---|---|
| `PaymentTransaction` / attempt entity | D1, D2, D3, D4, D7, D8, D9, D11, D22, D30 |
| Payment state machine | D9, D11, D12, D21, D29, D30 |
| `PaymentWebhook` endpoint | D1, D2, D21, D22 |
| Refund records | D13, D14, D15, D16, D28 |
| Payment → Accounting | D11, D19, D23, D24, D35 |
| Payment → Inventory | D9, D10, D11 |
| COD collection | D5, D6, D17, D18, D27 |
| Partial payment / outstanding balance | D7, D8, D19 |
| GST / credit note interaction | D6, D15, D27, D28 |
| Reconciliation and settlement ingestion | D2, D19, D23, D24 |
| Order lifecycle changes (payment gates) | D11, D12, D13, D14 — **would extend P0-5A's transition table** |
| Money and rounding utilities | D6, D8, D31 — paise only |
| Payment endpoint authorization | D34 precedent; P0-5A pattern |
| Request validation strictness | D33 |

## 35. Proposed implementation sequence

> **PROPOSAL ONLY.** Ordered by the dependencies above, not by convenience. Nothing is started.

```text
0.  BUSINESS DECISIONS                       ← D1, D5, D7, D8, D10, D11, D13, D19, D22
        ↓
1.  Ratify shipped behaviour                 ← D25, D26, D39   (no code; may confirm or reverse)
        ↓
2.  Money & validation groundwork            ← D8, D31, D33
        paise-only fee/split arithmetic; remove float rupee maths from checkout; validate currency
        ↓
3.  PAYMENT DOMAIN MODEL                     ← D1/D5/D7 answered
        attempt entity, state machine, audit trail — no provider
        ↓
4.  PAYMENT IDEMPOTENCY                      ← D22, D30
        key distinct from clientOrderReference; database-enforced
        ↓
5.  ORDER ↔ PAYMENT LIFECYCLE                ← D11, D12, D13, D14
        extends P0-5A's transition table rather than replacing it
        ↓
6.  COD COLLECTION  *(if D5 approved)*       ← D6, D17, D18
        exercises the whole model with no gateway — a natural first vertical slice
        ↓
7.  ACCOUNTING COLLECTION                    ← D19, D35, plus any new accounts (§30)
        ↓
8.  GATEWAY INTEGRATION  *(if D1 = yes)*     ← D2, D3, D4, D32
        ↓
9.  WEBHOOK VERIFICATION                     ← D21, D23, D29
        ↓
10. RECONCILIATION                           ← D23, D24
        ↓
11. REFUNDS                                  ← D15, D16
        ↓
12. GST / CREDIT-NOTE RECONCILIATION         ← D27, D28
```

**Two departures from the candidate sequence in the brief, both dependency-driven:**

1. **A money-and-validation step before the domain model.** D8's rounding rule and the paise
   representation must be settled before anything computes a fee or a split, or the wrong arithmetic
   gets baked into the schema.
2. **COD before gateway integration**, if D5 is approved. COD needs no vendor decision and exercises
   the attempt model, idempotency, the lifecycle and AR settlement end to end. It de-risks the
   gateway work rather than waiting on it.

A third observation: **step 1 costs nothing and unblocks nothing** — but it ratifies three behaviours
already running in production, and doing it early avoids building on top of an unconfirmed rule.

---

## 36. Open questions

**For Business — blocking everything**

1. Will CARD/UPI take real money? *(D1)*
2. Is COD a genuine offer, and who physically collects the cash? *(D5, D17)*
3. Is partial payment real, and is 50% the rule? *(D7, D8)*
4. At what moment is an order paid, and may it ship then? *(D11, D12)*
5. What happens to stock when a payment fails? *(D10)* — the answer decides whether P0-2 survives.
6. Is the ₹50 COD fee real? It is shown to customers today and never charged. *(D6)*

**For Finance**

7. What is the ₹19,796.33? *(D20)*
8. Is excluding `PAYMENT_FAILED` from revenue approved? *(D25)* — and should analytics agree? *(D37)*
9. Should cancellation be blocked when the period is closed? *(D26)*
10. Should checkout post sales in real time? *(D35)*
11. Should a reversed sale ever be re-postable? *(D39)*
12. Should the historical duplicate journals be formally reviewed? *(D36)*

**For Tax**

13. GST on a COD handling fee. *(D27)*
14. Input GST creditability on gateway commission. *(D24)*
15. Refunds — existing credit note, or a separate adjustment? *(D28)*

**For Operations / Security**

16. Who owns a missed webhook? *(D21)*
17. Should accounting reports be readable without authentication? *(D34)*
18. Confirm the dev/prod connection budget. *(D38)*

**For Product / Security — independent of everything else**

19. Should the checkout stop collecting card numbers and CVVs it throws away? *(D32)* — this becomes
    urgent, not optional, if D1 is answered "No".

## 37. Final approval checklist

| # | Item | Status |
|---|---|---|
| 1 | Payment method availability approved (CARD, UPI, COD, PARTIAL) | ☐ D1, D3, D4, D5, D7 |
| 2 | Gateway selected, with environments and merchant ownership | ☐ D2 |
| 3 | Payment timing model approved | ☐ §4, D1 |
| 4 | Inventory-after-failure policy approved | ☐ **D10 — decides whether P0-2 survives** |
| 5 | Payment failure behaviour defined for all six scenarios | ☐ D9, D29 |
| 6 | "Paid" boundary and fulfilment gate defined | ☐ D11, D12 |
| 7 | COD fee, collection event and refusal handling approved | ☐ D6, D17, D18 |
| 8 | Partial payment amount and balance collection approved | ☐ D7, D8 |
| 9 | Cancellation window and paid-cancellation policy approved | ☐ D13, D14 |
| 10 | Refund policy and authorization approved | ☐ D15, D16 |
| 11 | AR settlement trigger approved; existing AR treated | ☐ D19, D20 |
| 12 | Accounting events mapped; new accounts approved | ☐ D24, D23, D17 (§30) |
| 13 | Tax treatment confirmed for fees, refunds and credit notes | ☐ D27, D28 |
| 14 | Payment idempotency key defined, distinct from `clientOrderReference` | ☐ D22, D30 |
| 15 | Webhook authority and operational ownership assigned | ☐ D21 |
| 16 | Reconciliation model, frequency and ownership defined | ☐ D23 |
| 17 | Closed-period cancellation policy ratified | ☐ D26 |
| 18 | `PAYMENT_FAILED` revenue rule ratified | ☐ D25, D37 |
| 19 | Historical duplicate journals reviewed | ☐ D36 |
| 20 | Card-data collection in the browser resolved | ☐ D32 |
| 21 | Connection budget confirmed for deployment | ☐ D38 |

**Payment implementation must not begin until at least items 1–8, 14 and 21 are resolved.** Items
1–8 and 14 determine the schema and the state machine; item 21 is an operational precondition for
deploying anything at all.

---

## Verification

| Check | Result |
|---|---|
| Document created | `docs/p0-6-payment-decision-and-implementation-preparation.md` |
| Java source changed | **None** |
| TypeScript / React changed | **None** |
| SQL / Liquibase changed | **None** |
| Tests changed | **None** |
| Configuration changed | **None** |
| Database data changed | **None** — 7 orders, 54 journals, 187 lines, AR 1,979,633 paise, identical before and after |
| Payment dependency added | **None** |
| Payment table created | **None** |
| Test orders created | **None** |
| Historical journals modified | **None** |

Verified by checksum manifest over 1,478 source, resource, migration, test and configuration files
(the repository is untracked at its own root, so the manifest approach established in P0-4 and
P0-5B was used), plus a before/after database comparison.

**No payment implementation was performed. No application behaviour was changed.**
