# P0-12 — Financial decision closure report

This phase closed what could be closed from the repository, and established — by
looking, not by assuming — that four things cannot be closed without a business
decision. Nothing was guessed.

## 0. What was inspected, and what the reports got wrong

The P0-10 and P0-11 reports were read and then checked against the code. Two
findings:

| Claim | Verdict |
|---|---|
| COD fee credits 4090 Other Income | ✅ 4090 exists, is `REVENUE` / `CREDIT` / `Revenue`, and is what the code uses. Valid — unchanged |
| Gateway fee: actual fee kept separate from the split | ✅ `gateway_fee_paise` holds the gateway's figure; the business and customer portions are separate columns. Nothing overwrites the actual fee — now pinned by a test |
| No gateway-fee journal exists | ✅ confirmed: no `GATEWAY_FEE` source type anywhere |
| Refund excludes the gateway fee | ✅ `refundablePaise()` is product + GST only, and `payment_refund` has no gateway column |
| P0-11 used 18% for the COD fee | ✅ **only in a test**, against a rule the test seeds and removes. No 18% is hardcoded and none is seeded into the tax master |
| **The refund model had three states** | ⚠️ true, and not enough — fixed, §2 below |
| **The checkout screen added ₹50 itself** | ⚠️ true — fixed, §8 below |

## 1. COD GST — BLOCKED

Searched, not assumed. The tax master and the HSN master were both read in full:

```
gst_tax_rules   8 rows — HSN 4202, 4203, 61, 62, 63 (textiles and leather only)
gst_hsn_master  19 rows — chapters 42, 61, 62, 63 only
Rows matching ^99 / service / COD / fee / charge / handling:   0
```

There is **no service code in the system at all**, so the existing engine returns
`NO_RULE`, which it deliberately distinguishes from 0%.

```
COD GST RATE:
BLOCKED

COD TAXABLE VALUE:
BLOCKED

Required business/accounting decision:
  (a) Under which SAC is the ₹50 handling charge supplied, and at what rate?
      Nothing in the repository answers this. A rate cannot be inferred from the
      goods rates, because the charge is a service, not a garment.
  (b) Is ₹50 the taxable value, so the customer pays ₹50 + tax — or the whole
      charge, so the tax is carved out of it? The repository contains no rule
      either way. The only related statement anywhere is GstCalculationService's
      "tax-exclusive base", which describes a product price, not this charge.
```

The two are one decision in practice: (b) changes what the customer is billed, and
(a) is needed before (b) can be computed.

**What is implemented and waiting:** the charge is quoted, snapshotted and posted
through the existing engine, gated on `cod_fee_tax_resolution == RULE_APPLIED`. One
row in `gst_tax_rules` plus `veloria.payment.cod-fee.sac-code` turns it on; a test
proves the whole path end to end against a seeded rule. Until then the charge is
₹50 with no tax, the reason is recorded on every order, and nothing is posted.

## 2. COD refund destination — BLOCKED

Searched the schema and the code for a customer refund destination:

```
Columns matching bank / ifsc / upi / vpa / account_number / beneficiary / payout:
  payment_refund.refund_destination   (a label: RAZORPAY_SOURCE)
  supplier.account_number             (vendors, not customers)
  supplier.bank_name                  (vendors, not customers)
```

There is **no customer bank account, IFSC, UPI id or payout route anywhere**. Cash
collected at a door has no electronic destination to reverse to.

```
COD REFUND DESTINATION:
BLOCKED

Required business decision — reported for approval, not chosen:
  A. Capture a destination through a secure refund workflow
     The customer supplies bank or UPI details at the point of claiming. Needs a
     decision on what is stored, for how long, and who may read it.
  B. Manual refund, recorded but executed outside the system
     Finance pays by their own means; the application records what is owed and
     that it was settled. Needs no new customer data at all.
  C. Store credit or another mechanism
     Needs a decision on expiry, transferability and how it is accounted for.
```

**Not chosen, and not half-built.** No banking column was added.

**What was built instead is the state the model was missing.** The refund status
model had three states — `REQUESTED → COMPLETED | FAILED` — which works only
because an online refund is agreed and sent in the same call. A COD refund can be
agreed and then have nowhere to go, and with three states such a refund must be
recorded either as complete (a lie: the customer has nothing) or as never approved
(also a lie: the business accepted the debt). `RefundStatus` now has five:

```
REQUESTED  → judged eligible; nothing has moved
APPROVED   → the business accepts the money is owed; still nothing has moved
PROCESSING → handed to the gateway; treat the money as gone
REFUNDED   → confirmed; the only state that may be posted
FAILED     → nothing moved, and no accounting may say otherwise
```

`APPROVED` is where a blocked COD refund will rest. Transitions are enforced, not
documented: a refund cannot skip the gateway, go backwards, or turn a failure into
a success. The online path now commits `PROCESSING` **before** calling Razorpay,
closing a window in which the customer could have been paid while the application
still believed nothing had been sent.

**COD refund safety (§14) is already enforced** by the existing eligibility gates —
verified return, goods received, not already refunded, something actually
collected — and a COD order is additionally refused outright with a reason that
names the open decision. A cancellation does not trigger a cash refund: cancellation
reverses the sale, and refunds run only off a verified return.

## 3. Gateway fee model — three separate facts, verified

```
amount_paise                  gross the customer paid
gateway_fee_paise             the fee the gateway actually charged
gateway_fee_business_paise    50%, taking the odd paisa
gateway_fee_customer_paise    50%
net_settlement_paise          gross − actual fee
```

All five persisted, none derived from a percentage. `actualGatewayFee` is **never**
overwritten by the customer portion — now asserted against the database rather than
the in-memory object, and asserted to be three genuinely different numbers so a
change that collapsed them could not pass.

## 4. Gateway customer charge — BLOCKED

Searched for any pre-payment customer fee that could serve as the mechanism:

```
convenience fee / platform fee / service fee / transaction fee / processing fee
/ handling fee / customer fee, across backend, both frontends and all docs:
  no pre-payment customer fee exists
```

```
GATEWAY CUSTOMER CHARGE
BLOCKED

Reason:
Actual gateway fee is known only after payment.

Required decision:
How should the customer-facing 50% charge be determined before payment?
```

Models are presented for approval, and **none is implemented**:

| | Model | What it costs |
|---|---|---|
| A | Estimate at checkout from approved configuration, true up later | Needs an approved estimate — which is a percentage, and the brief forbids inventing one. Also needs a rule for the difference |
| B | Bill it on the customer's next order | The charge and the payment it relates to sit on different invoices; needs a decision on what happens if there is no next order |
| C | Flat approved charge, independent of the actual fee | Simple and pre-payment, but then it is not "50% of the fee" — it is a separate approved price |
| D | Drop the pass-through; the business bears the whole fee | No customer-facing change at all. The split then describes intent, not money |

`customer charge = actual fee / 2` applied after payment was **not** implemented:
it would alter an invoice the customer has already paid.

## 5. Gateway customer charge GST — UNDECIDED

```
Gateway customer charge GST treatment:
UNDECIDED
```

Same search as §1, same answer: no service code exists, so no authoritative rule
can be read. Nothing was assumed, and no rate was hardcoded.

## 6. Gateway refund — BLOCKED

The existing rule is preserved and unchanged: the gateway fee is not deducted from
a customer's refund, expressed structurally by `payment_refund` having no column
for it.

```
GATEWAY CUSTOMER CHARGE REFUND:
BLOCKED
```

The existing rule concerns the fee the **business** bore. Whether a charge the
**customer** paid comes back is a different question, and the repository holds no
answer. It cannot be settled before §4 anyway — there is no customer charge yet to
refund.

## 7. Transportation — UNDECIDED

Unchanged, and nothing was written: no distance, weight, pincode or courier logic
exists. `shipping_value` is 0 on every order.

The model still supports a non-zero future value: `OrderInvoice` reads the column,
the approved waterfall settles transportation in full before the product, and a
refund reads the stored snapshot. A non-zero value flows through with no further
change.

## 8. COD UI correction — CLOSED

The P0-11 follow-up is fixed. The payment screen computed
`dueNow = grandTotal + 50` and rendered a literal `₹50`.

**Now:** the server quotes the charge with the cart, priced by the **same**
`CodFeeTaxResolver` that prices the order, and the screen renders those figures.

```
CartGstPreviewResponse.codCharge {
  feePaise, taxPaise, taxRateBp, taxResolution, totalPaise
}
```

| | Before | After |
|---|---|---|
| COD total | `grandTotal + 50` in the browser | `codCharge.totalPaise` from the server |
| Fee line | literal `₹50` | `codCharge.feePaise` |
| Fee GST line | did not exist | shown only when `taxResolution == RULE_APPLIED` |
| The notice text | "Additional ₹50 handling charge" | the server's figure |
| Paise | rounded away | kept whenever the amount has them |

`taxResolution` is passed through to the client deliberately: an unresolved rate is
not a zero rate, and the page must not render one as the other, so the GST line is
absent rather than showing ₹0.

There is now no COD amount in the React source at all. Two Playwright tests hold
that:

- **COD-001** compares the displayed fee and total against the backend's own
  figures, and checks the server's arithmetic first (`total = grandTotal + fee +
  tax`). It deliberately never asserts "₹50" — matching the literal would pass
  against the very bug being fixed.
- **COD-002** intercepts the response and changes the server's fee to ₹77.77. The
  page must display ₹77.77. A literal left in the component would still show ₹50
  and fail.

## 9. Accounting changes

| | |
|---|---|
| **New** | `RefundStatus` — five states with an enforced transition table |
| **Changed** | The refund flow commits `PROCESSING` before the gateway call |
| **Changed** | `postRefund` and the backfill accept only `REFUNDED` |
| **Changed** | `CodFeeTaxResolver` gained an order-less overload, so a quote and an invoice share one arithmetic |
| **Changed** | `CartGstPreviewResponse` carries the server-computed COD charge |
| **Unchanged** | Every account code. No new code was created and none was invented |
| **Unchanged** | Every posting rule, the closed-period mechanism, the AR query, the waterfall, the GST engine |
| **No migration** | `payment_refund.status` is a VARCHAR(24) and the table was empty, so the five names needed no schema change and no data migration |

## 10. Test fixture protection (§18)

Per-statement error handling catches only the statements a test remembered to
write. `AccountingResidue.assertNone` checks the **outcome** instead, and now runs
in the teardown of all five order-creating integration tests:

```
orphaned SALE / COD_FEE journals        (order deleted, journal left)
orphaned PAYMENT_COLLECTION journals    (payment deleted before its journal)
orphaned REFUND journals                (refund row deleted before its journal)
journal lines with no entry
reversals of a journal that is gone
payments with no order
refunds with no order
accounting periods left closed          (would silently defer later postings)
```

Any of these fails the test, with the count, naming the consequence: it will move
the historical journal checksum. This catches a cleanup that failed, one that ran
in the wrong order, and one that forgot a table — the three ways P0-11's residue
actually happened.

**The guard was verified to be non-vacuous**, because a check that always passes is
worse than none: an orphaned SALE journal was inserted inside a transaction, the
query returned 1, and the transaction was rolled back — leaving the checksum
unchanged. The same was done for a period left closed.

Scoped to orphans rather than absolute counts on purpose: a shared database
legitimately holds the historical orders, but a journal whose order is gone is never
legitimate.

## 11. Historical checksum

Measured after the **complete** suite — 505 backend, 87 Cucumber, 29 Playwright:

| | Baseline | After P0-12 |
|---|---|---|
| Journal checksum | `eea1e82cfa93ce16f7963524e3eaf56f` | **`eea1e82cfa93ce16f7963524e3eaf56f`** |
| Orders | 7 | **7** |
| SALE | 28 | **28** |
| REVERSAL | 21 | **21** |
| Journals · lines | 54 · 187 | **54 · 187** |
| AR | 1,979,633 paise (₹19,796.33) | **1,979,633** |
| All 13 account balances | — | **unchanged** |
| Orphans | 0 | **0** |

No historical journal was modified, deleted or rewritten. No closed period was
reopened. No account code was added. No schema change was made in this phase at
all.

## 12. Test results

| Suite | P0-11 | P0-12 |
|---|---|---|
| Backend | 494 | **505**, 0 failures |
| Cucumber | 87 run, 6 known failures | **87 run, the same 6** |
| Playwright client | 12 passed, 1 skipped | **14 passed, 1 skipped** |
| Playwright admin | 15 passed | **15 passed** |

New in P0-12:

| | |
|---|---|
| `RefundStatusTest` | 8 — the five states, the blocked path resting at APPROVED, in-flight money counted as spent, only REFUNDED accountable, no backwards moves, no skipping the gateway |
| `FinancialDecisionsPostgresTest` | +3 — the quote equals what is charged, an unresolved rate is reported as unresolved, and the actual gateway fee is never overwritten |
| `cod-charge.spec.js` | 2 — the displayed figures are the backend's, and the page follows a changed server figure |

## 13. Sandbox status

```
SANDBOX
BLOCKED — credentials not configured
```

`RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET` and `RAZORPAY_WEBHOOK_SECRET` are unset;
no `.env` exists. No CARD, UPI, payment-failure, partial-payment, refund,
refund-failure, webhook, duplicate-webhook, signature, wrong-amount,
wrong-currency or wrong-gateway-order test has been run against Razorpay, and none
is claimed. Live keys are still refused at startup.

## 14. Security

No customer banking data was added — deliberately, since the destination decision
is open. Re-verified unchanged: no Razorpay secret in source, frontend or git; no
card, CVV, VPA or PIN column anywhere; no secret reachable from the browser. The new
COD quote exposes only amounts the customer is about to be charged.

## 15. Remaining blockers

```
BLOCKED    COD fee GST rate, and whether ₹50 is the taxable value       §1
BLOCKED    COD refund destination — three models presented for approval §2
BLOCKED    Gateway customer charge — how it is determined pre-payment   §4
UNDECIDED  Gateway customer charge GST treatment                        §5
BLOCKED    Gateway customer charge refund treatment                     §6
UNDECIDED  TR-1 / TR-2 transportation pricing                           §7
BLOCKED    Razorpay sandbox — credentials                              §13
```

Closed in this phase: the COD frontend display, and the refund domain model's
inability to represent an approved-but-unsent refund.
