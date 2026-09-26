# P0-10 — Approved accounting decisions

A snapshot of what was approved, taken before implementation. Everything in
§Implemented was applied; everything in §Still unresolved was not, and no code
assumes an answer to it.

## Implemented

| ID | Decision | Approved position |
|---|---|---|
| **D35** | SALE recognition timing | **Recognised at order creation.** The trigger moves from `backfill()` to checkout; the journal itself is unchanged — same accounts, same amounts, still dated `orderPlacedAt`. `backfill()` must not be able to produce a second sale. |
| **COD-1 / D17** | COD sale and cash timing | **Sale at order creation**, the same as any other order. Cash is collected when the customer receives the order, and that settles the receivable. COD is never a Razorpay payment. |
| **D36** | Historical journals | **Do not modify.** The 28 SALE and 21 REVERSAL entries, and the ₹19,796.33 receivable, stay exactly as they are. |
| **GF-1** *(partial)* | Gateway fee | A separate business cost. Never inside the product price, never inside the GST base, never deducted from a customer's payment allocation, never a hardcoded percentage. Not passed to the customer on a refund. |
| — | Partial payment | 50% of the final invoice, allocated GST → transportation → other charges → product. Stored, never recomputed. |

## Still unresolved — nothing was built on these

| ID | Decision | Status |
|---|---|---|
| **CP-1** | Closed-period order accounting | **IMPLEMENTATION GAP** — the approved policy is to defer the accounting, and no deferred-posting mechanism exists. See the report. |
| **GF-1** *(account)* | Which account a gateway fee is posted to | **BLOCKED** — no gateway or payment-processing expense account exists in the chart of accounts. |
| **COD fee treatment** | Revenue and tax treatment of the ₹50 | **BLOCKED** — surfaced by implementation; see the report. |
| **TR-1 / TR-2** | Transportation pricing | **UNDECIDED** — `shipping_value` stays 0 and no pricing logic was written. |
| **D15** | Refund trigger and postings | **BLOCKED** |
| — | Razorpay sandbox credentials | **BLOCKED — not configured** |
