# P0-14 — Decision register

The current status of every open financial decision, as verified against the code and
the database in this phase — not copied forward from the previous reports.

Anything recorded here as UNDECIDED or BLOCKED is not approved, and the
implementation refuses to act on it rather than choosing for itself.

---

## D-COD-REFUND — where a cash refund goes

```
COD_REFUND_DESTINATION
STATUS = UNDECIDED

Required decision:
  A. Store credit / wallet
  B. Customer bank account
  C. Customer UPI
  D. Manual refund — recorded here, paid by finance outside the system
  E. Other
```

**No existing approved destination was found.** Searched the code, the frontends and
the schema for a customer-side payout facility:

```
wallet · storeCredit · store_credit · creditBalance · customerCredit
refundWallet · customerBalance · refundDestination · beneficiary · vpa · ifsc
bank · account_number · payout
```

What exists:

| Hit | What it actually is |
|---|---|
| `payment_refund.refund_destination` | A label on a refund already made (`RAZORPAY_SOURCE`), not a payout instruction |
| `supplier.account_number`, `supplier.bank_name` | **Vendor** payment details. Not customers, and deliberately not reused for them |
| `payment_attempt.method` = `"wallet"` | Razorpay's word for how the customer *paid* |
| `gst_credit_note` | A statutory GST document, not customer credit |

No customer wallet, no balance, no bank or UPI column. Confirmed against the live
schema: the only columns in the entire database matching bank/ifsc/upi/vpa/
account_number/beneficiary/payout are the two supplier ones.

### A correction this phase had to make

Two earlier reports and the code itself stated that **store credit was the approved
destination**:

```
p0-14-cod-service-tax-implementation-report.md:241   "The approved destination is store credit"
p0-13-cod-gst-implementation-report.md:340           "store credit approved, no facility exists"
RefundService.refundableAttempt()                    "The approved refund destination is store credit"
```

No approval record exists. `p0-13-final-financial-decision-register.md` records
**D-COD-REFUND: STATUS UNDECIDED** with store credit as option C of four, and
`p0-11-approved-financial-decisions.md` lists the destination under "Still
unresolved". The claim appears to have originated as a paraphrase and then been
repeated forward.

It mattered because the sentence was **customer-facing**: the refusal message told a
customer that store credit was approved policy. That is now corrected — the message
names the open decision and picks nothing:

> "Order … was paid in cash on delivery, and no refund destination has been approved
> for cash payments, so the refund cannot be issued here. The amount owed stands; how
> it reaches the customer needs a business decision."

Two tests hold it: the reason must name the open decision, and must **not** present
any destination as approved.

### What is implemented, and what is not

**Not implemented:** any payout. No banking column was added, and the supplier
columns were not reused. A COD-paid order is refused at eligibility.

**Implemented:** everything up to the payout. The debt is recognised, the eligibility
gates run, and the refusal is safe under concurrency — twelve simultaneous attempts
all refuse, write no refund row, post no journal, and leave the receivable untouched.

### The six refund states P0-14 §5 asks for, and where each lives

They are all representable now, across the two entities that already exist. **No
state was added and no second state machine was created** — adding a
`REFUND_ELIGIBLE` to `RefundStatus` would record in a second place the fact that
`order_return_request.VERIFIED` already records.

| Required state | Where it lives today |
|---|---|
| `REFUND_REQUESTED` | `order_return_request.status = REQUESTED` — the customer has asked |
| `REFUND_ELIGIBLE` | `order_return_request.status = VERIFIED` **and** `RefundService.eligibilityFor()` returns eligible. A `payment_refund` row is only created once eligible, which is why its own `REQUESTED` means "exists and judged eligible" |
| `REFUND_APPROVED` | `RefundStatus.APPROVED` — the business accepts the money is owed, nothing has moved. Where a blocked COD refund rests |
| `REFUND_PROCESSING` | `RefundStatus.PROCESSING` — handed to the gateway; treat the money as gone |
| `REFUNDED` | `RefundStatus.REFUNDED` — the only state whose accounting may stand |
| `REFUND_FAILED` | `RefundStatus.FAILED` — nothing moved, and no accounting may say otherwise |

The authoritative eligibility chain is unchanged and still enforced in this order:

```
return created → goods received → warehouse verified → refund eligible → refund
```

No refund is issued before verification.

---

## D-TRANSPORTATION — TR-1 / TR-2 pricing

```
TRANSPORTATION_PRICING
STATUS = UNDECIDED
```

Searched again for any pricing mechanism:

```
shipping rate · delivery charge · shipping fee · transportation fee · courier
pincode · distance · weight · slab · seller shipping · delivery zone
```

**Nothing prices transportation.** Every hit is unrelated: `courier` appears in
order-status and cancellation-reason prose, `slab` and `pincode` belong to the GST
rate master and the place-of-supply resolver. There is no rate table, no zone map, no
weight or distance input, and no formula anywhere.

`shipping_value` is a snapshot column, defaulting to 0 on both
`customer_order` and `sales_invoice`, and it is written from the order, never
computed:

```java
CustomerOrderEntity.shippingValue  = 0L   // @Builder.Default
SalesInvoiceEntity.shippingValue   = 0L
SalesInvoiceService: long shipping = nvl(order.getShippingValue());   // read, not derived
```

### Still to be decided

| Field | Status |
|---|---|
| Outbound transportation fee | UNDECIDED |
| Return transportation fee | UNDECIDED |
| Customer transportation charge | UNDECIDED |
| Calculation method | UNDECIDED |

```
Possible calculation methods, none selected:
FIXED · WEIGHT · PINCODE · DISTANCE · COURIER · SLAB · PRODUCT · SELLER · OTHER
```

**No speculative rate table was created.** What §16 requires already holds: the
financial snapshot can store a `transportationAmount` and preserve the amount an
order actually used. A non-zero value flows through the invoice waterfall, the
payment allocation and the refund snapshot with no further change — covered by an
existing test that writes a non-zero amount directly onto an order and follows it
through, deliberately without inventing a rate.

**Historical transportation is never recalculated from future rules.** The amount
lives on the order.

---

## D-COD-GST — the COD handling charge (CLOSED in P0-13, re-verified here)

```
COD_ENABLED           true
COD_FEE_PAISE         5000
COD_FEE_SAC           998599
COD_FEE_TAX_BASIS     INCLUSIVE
COD_FEE_SERVICE_NAME  Cash on Delivery Handling Fee
COD_FEE_REFUNDABLE    false

SAC 998599 · CGST 9% · SGST 9% · IGST 18% · EXACT · from 2017-07-01 · active
₹50 gross = ₹42.37 taxable + ₹7.63 GST
```

Read from the live database in this phase, not from the previous report.
`COD_FEE_REFUNDABLE = false` is preserved: the COD fee is not refunded.

**Tax compliance caveat, unchanged and still open.** SAC 998599 and 18% are an
application tax configuration reflecting the chosen implementation model. They are
**not** an independent legal determination that this business's COD fee is a separate
taxable service supply. A GST adviser or CA must validate the classification before
filing. If they conclude it forms part of a composite supply, or belongs under another
classification:

```
Do not rewrite historical orders.
Make a controlled tax-configuration change.
Re-test future orders only.
```

---

## D-GST-PREVIEW — the addressless product GST preview (CLOSED in this phase)

```
STATUS = IMPLEMENTED
Behaviour: HTTP 200, gstResolved = false, taxResolution = NO_PLACE_OF_SUPPLY,
           every tax amount null — never 0
```

Not invented: **§9 option D applied.** The frontend already defined the behaviour —
the bag has shown *"Add a delivery address to see GST"* since before this phase — and
reached it only because the request failed. The server now says what the UI already
assumed. Details in `p0-14-refund-and-gst-preview-report.md`.

---

## D-GATEWAY-CUSTOMER-CHARGE — unchanged, not touched

```
Accounting for the recovered half    UNDECIDED
GST treatment                       UNDECIDED
How it is known before payment      BLOCKED
Whether it is refunded              BLOCKED
```

Nothing in this phase touched the gateway fee. The business still bears it; no
customer gateway charge exists.

---

## D-RAZORPAY-SANDBOX — credentials

```
STATUS = BLOCKED — NOT CONFIGURED
```

**No sandbox call was made in this phase, and none is claimed.**

What was verified is that the environment supports configuring them securely:

```
RAZORPAY_KEY_ID        @Value("${RAZORPAY_KEY_ID:}")        empty default
RAZORPAY_KEY_SECRET    @Value("${RAZORPAY_KEY_SECRET:}")    empty default
RAZORPAY_WEBHOOK_SECRET @Value("${RAZORPAY_WEBHOOK_SECRET:}") empty default
```

- Read from the environment only — no value in any tracked file.
- A live `RAZORPAY_KEY_ID` is **refused**: the integration is approved for test mode.
- A webhook arriving without `RAZORPAY_WEBHOOK_SECRET` is refused, not trusted.
- `application-local.yaml`, `*.env` and `.keycloak-secret` are git-ignored and
  untracked, verified with `git ls-files`.
- `NoCommittedSecretsTest` fails the build on a credential-shaped literal in a
  tracked config file, and never prints a matched value.

---

## Summary

```
UNDECIDED  D-COD-REFUND destination — five options, none chosen
UNDECIDED  D-TRANSPORTATION pricing — four fields, nine possible methods
UNDECIDED  Gateway customer charge — accounting and GST
BLOCKED    Gateway customer charge — pre-payment determination, refund treatment
BLOCKED    Razorpay sandbox — credentials not configured
CAVEAT     SAC 998599 / 18% needs CA validation before filing

CLOSED     D-GST-PREVIEW — addressless preview answered, not crashed
CLOSED     Store-credit misstatement — code no longer claims an unapproved approval
```
