# P0-11 — Approved financial decisions

The decisions recorded here are approved. Anything not recorded here as approved
is not approved, and the implementation refuses to act on it rather than choosing
for itself.

## D-COD-FEE — the cash-on-delivery handling charge

```
COD fee                     = ₹50
Accounting classification   = Other Income
Income description          = COD Fee
```

Separate from product revenue, from GST on the product, from transportation and
from the gateway fee. **Not** combined with product revenue.

| | |
|---|---|
| Account | **4090 Other Income** — already in the chart of accounts, reused, no new code created |
| Status | **Implemented.** Its own journal (`COD_FEE`), keyed on the order |

## COD fee GST

```
The COD fee is taxable as the business's income.
```

The rate comes from the existing `gst_tax_rules` master, looked up by service code
and by the order's own date. No rate is hardcoded and no second tax engine exists.

| | |
|---|---|
| Tax accounts | 2100 / 2110 / 2120 Output CGST / SGST / IGST — existing |
| Snapshot | Frozen on the order: fee, taxable value, rate in basis points, tax by head, and the service code it was priced under |
| Status | **Mechanism implemented; the tax itself BLOCKED** — see below |

## Gateway fee

```
50% of the gateway fee = business expense
50% of the gateway fee = passed to the customer
```

Computed from the fee the gateway actually charged. No percentage appears
anywhere: not 2%, not 2.36%, not 18%. An odd fee gives the extra paisa to the
business, never to the customer.

| | |
|---|---|
| Recorded | Gross paid, fee charged, business half, customer half, net settlement — all on the payment |
| Status | **Facts persisted. Nothing posted** — see the blockers |

## CP-1 — closed accounting period

```
1. Order creation remains valid.
2. SALE must NOT be posted into the closed period.
3. SALE is posted in the next open accounting period.
4. The original transaction date is retained separately.
```

| | |
|---|---|
| Mechanism | `journal_entry.journal_date` is the **posting date**; the new `journal_entry.transaction_date` is when the event happened. The next open period comes from `accounting_period`, never from arithmetic on the calendar |
| Applies to | The order lifecycle: sale, COD fee, collection, refund. An expense or payroll voucher backdated into a closed period is still refused |
| Never | Reopens a period, unlocks one, changes `orderPlacedAt`, or touches a historical order |
| Status | **Implemented** |

## Refund workflow

```
Customer requests return → return created → product received by warehouse
  → warehouse verification → refund eligibility confirmed → refund calculated
  → refund issued → original payment destination
```

A return request alone earns no refund.

| | |
|---|---|
| Status model | The existing one, reused: `order_return_request.status` REQUESTED → VERIFIED, plus the order reaching a received state. No second status system |
| Eligibility | Verified **and** goods received **and** not already refunded **and** something actually collected |
| Amount | Product + its GST, from the allocation **stored on the payment at capture**. Never recomputed from current prices or tax rules, and never more than was collected |
| Transportation | Refunded only from the stored snapshot. It is ₹0 today, so the refund is ₹0. No formula |
| COD fee | **Non-refundable** — the existing approved rule, preserved. `payment_refund` has no column for it |
| Destination | `RAZORPAY_SOURCE` — the gateway returns it to whatever instrument paid |
| Accounting | `Dr 4010 Sales`, `Dr` output tax, `Cr 1020 Bank`. The sale and the collection are kept, never deleted |
| Status | **Implemented for online payments** |

## Still unresolved — not implemented, not guessed

| | Decision required |
|---|---|
| **COD fee GST rate** | The tax master holds only textile and leather HSN chapters (4202, 4203, 61, 62, 63). No service code exists for a handling charge. **Which SAC, and what rate?** |
| **COD fee: inclusive or exclusive** | Is ₹50 what the customer pays for handling, or the taxable value with tax added on top? The two give the customer a different bill |
| **Gateway customer charge — accounting** | Which account the recovered half is credited to |
| **Gateway customer charge — GST** | `STATUS: UNDECIDED`. No rule exists in the application, and none was assumed |
| **Gateway customer charge — when** | The fee is not known until after the customer has paid, so it cannot be on the invoice they paid |
| **Gateway customer charge — refund** | Whether the recovered half is returned |
| **COD refund destination** | Cash has no electronic destination, and the schema holds no customer bank or UPI details |
| **TR-1 / TR-2 transportation pricing** | Unchanged. No algorithm, rate table, distance or weight logic was written |
| **Razorpay sandbox** | Credentials not configured. No gateway call has been made |
