# P0-13 — COD handling fee: GST treatment

> **This document records the application's configured treatment. It is not a tax
> opinion, and this software does not determine legal tax classification.**
>
> **SAC 998599 is the configured application classification and must be validated by
> the business's GST adviser/CA before actual GST filing if the COD charge is
> intended to be treated as a separate service supply.**
>
> The classification lives in configuration precisely so that validation can change
> it without a code release. See §10.

## 1. The business definition of the charge

A customer choosing cash on delivery is charged a flat handling fee. It pays for
work the business does *because* the customer chose to pay at the door — cash
handling, reconciliation, the courier's collection service, and the higher failure
rate of undelivered COD parcels.

It is not part of the price of the garment. The customer would not pay it if they
paid by card, and it does not change if the basket changes.

| | |
|---|---|
| Charge | **₹50.00** (5,000 paise) |
| Applies to | Cash-on-delivery orders only |
| Charged at | The point the customer chooses COD |
| Collected at | Delivery, with the rest of the invoice |
| Accounting classification | Other Income (account 4090) |

## 2. The proposed classification — SAC 998599

```
SAC          998599
Description  Other support services nowhere else classified
Service      Cash on Delivery Handling Fee
```

**Why this code is the implementation candidate.** The charge is a service, not a
supply of goods, so it needs a SAC rather than an HSN. It is not a transport
service (the courier's own supply is a separate transaction between the business
and the courier), not a financial service, and not a commission. What remains is a
support service incidental to the sale, and 998599 is the residual heading for
support services not classified elsewhere.

**What this reasoning is not.** It is a developer's reading of how the charge is
described in this application, not a tax determination. Several things could change
the answer and none of them are visible from the code:

- whether the charge is, in substance, a **composite supply** with the goods rather
  than a separate service — in which case it would take the principal supply's rate
  rather than its own;
- whether the business's actual contractual arrangement makes it a recovery of the
  courier's charge rather than the business's own service;
- whether a more specific SAC describes what the business actually does.

Those are questions about the business, not about the software. **A GST adviser must
confirm the classification before it is used for filing.**

## 3. GST rate configuration

```
SAC 998599
CGST   9%   (900 basis points)
SGST   9%   (900 basis points)
IGST  18%  (1800 basis points)
Effective from  2017-07-01
Active          true
Match type      EXACT
```

Stored as a row in `gst_tax_rules`, not in code. No rate appears in Java or
TypeScript anywhere in the COD path — a test reads the source and fails on one.

**Why 2017-07-01.** Every existing rule in this master carries that date, being when
GST came into force. A later date would leave a gap: an order dated before it would
find no rule and COD would be refused for that order.

**Changing the rate later** is a new row with a later `effective_from` and an
`effective_to` on this one. Historical orders keep the rate they were charged at,
because the rate is frozen onto each order's snapshot (§9).

## 4. The inclusive calculation

The customer-facing charge is **₹50 including GST**. They see ₹50 and they pay ₹50.

```
gross   = 5000 paise
rateBp  = 1800

taxable = round_half_up(gross × 10000 / (10000 + rateBp))
        = round_half_up(5000 × 10000 / 11800)
        = round_half_up(4237.288…)
        = 4237

tax     = gross − taxable
        = 5000 − 4237
        = 763
```

```
₹42.37  taxable value
₹ 7.63  GST
──────
₹50.00  charged to the customer
```

**The tax is the remainder, not the rate re-applied.** Applying 18% to 4237 gives
762, and 4237 + 762 is 4999 — a paisa short of what the customer pays, on a document
that has to reconcile. Taking the tax as what is left makes

```
taxablePaise + taxPaise == grossPaise
```

true by construction, at every rate and every amount. A test sweeps five rates
(0%, 5%, 12%, 18%, 28%) across seven amounts and both supply types and asserts it on
all of them.

Integer paise throughout. There is no floating-point arithmetic in any COD figure.

### The exclusive alternative, for contrast

If `COD_FEE_TAX_BASIS` were set to `EXCLUSIVE`, the ₹50 would be the taxable value
and the tax would be added:

```
₹50.00  taxable
₹ 9.00  GST
──────
₹59.00  charged to the customer
```

That is a different bill, which is why there is no default: the application refuses
rather than choosing. The configured basis is **INCLUSIVE**.

## 5. Place of supply

The charge is taxed against the **order's own place of supply**, read from the
snapshot the order carries — never from the product, the inventory, the seller's
catalogue or an HSN.

```
order.placeOfSupply   resolved at checkout from the delivery address on record
                      falling back to order.buyerStateCode
```

For an unregistered customer this is the recipient's address on record, which is the
place-of-supply rule for a service supplied to an unregistered person.

**A missing place of supply is refused, not guessed.** The GST engine will not choose
between CGST/SGST and IGST without it, and this returns

```
taxResolution = NO_PLACE_OF_SUPPLY      HTTP 400
```

with a message naming what is missing. It is a business validation problem, so it is
never a 500 and never a silent default.

An unrecognised state code that differs from the seller's is treated as inter-state.
The engine compares codes rather than validating them against the state master, and
inter-state is the safer direction: IGST charged on an unknown destination is
correctable, whereas a wrong CGST/SGST split has been apportioned to the wrong
state.

## 6. CGST / SGST / IGST

### Intra-state — place of supply equals the seller's state

```
CGST = totalTax / 2          (integer division)
SGST = totalTax − CGST       (the remainder)
IGST = 0
```

At ₹50 and 18%: `CGST = 763 / 2 = 381`, `SGST = 763 − 381 = 382`.

**The odd paisa goes to SGST, always.** The rule is deterministic and fixed, because
the alternative — rounding each head independently — loses or creates a paisa, and an
arbitrary-but-varying rule would make the same charge reconcile differently on
different days. The invariant is

```
CGST + SGST == totalTax
```

### Inter-state — place of supply differs from the seller's state

```
IGST = totalTax
CGST = 0
SGST = 0
```

Both cases carry the **same total tax**; only its division differs.

## 7. Accounting treatment

At the point COD is chosen, the charge is recognised and a receivable raised:

```
Dr 1100  Accounts Receivable            ₹50.00

   Cr 4090  Other Income                        ₹42.37
   Cr 2100  Output CGST                         ₹ 3.81      intra-state
   Cr 2110  Output SGST                         ₹ 3.82
```

Inter-state:

```
Dr 1100  Accounts Receivable            ₹50.00

   Cr 4090  Other Income                        ₹42.37
   Cr 2120  Output IGST                         ₹ 7.63
```

Posted as its own journal, source type `COD_FEE`, keyed on the order and therefore
idempotent. **₹50 is not credited to Other Income with tax added on top** — that
would be the exclusive treatment, and it would bill ₹59.

At delivery the cash is collected against the receivable:

```
Dr 1010  Cash                           invoice total
   Cr 1100  Accounts Receivable                 invoice total
```

Every account used already existed. No account was created for this.

**The accounting label does not determine the GST.** "Other Income" is where the
charge sits in the profit and loss account; the SAC is what determines its tax. The
two are configured independently and neither is derived from the other.

## 8. Invoice presentation

The charge appears as **its own line**, with its own service code:

```
  Description                        SAC      Taxable    CGST    SGST   Total
  ─────────────────────────────────────────────────────────────────────────────
  Midnight Silk Maxi                 6211    12,460.00    …       …        …
  Cash on Delivery Handling Fee    998599        42.37    3.81    3.82    50.00
```

Not merged into a product line, and not apportioned across the product lines the way
shipping is. Shipping is treated as incidental to a composite supply of goods;
handling is a separate service with its own classification, and folding it into a
garment's line would declare it at the garment's rate and lose the classification
entirely.

## 9. Product-HSN isolation

The charge's tax is independent of the goods, and this is enforced in three places
rather than left to convention:

1. **Different code.** The rate is looked up by SAC, never by the product's HSN.
2. **Different code *type*.** `gst_tax_rules.tax_code_type` is `HSN` or `SAC`, and
   resolution filters on it. A goods rule cannot price a service even if someone
   configured the service code to `6211`, and a SAC rule cannot price goods.
3. **Different snapshot.** The charge's taxable value, rate and per-head amounts live
   in their own `cod_fee_*` columns, so the product's statutory per-line figures
   never absorb them.

Consequences, each covered by a test: changing a product's HSN or rate does not
change the COD tax; a COD rate of 18% coexists with a product rate of 5% or 12%; and
with no SAC configured the charge is refused even though a perfectly good product
rule is sitting in the same table.

**Historical orders are never recalculated.** The rate, the taxable value and the
amounts are frozen on the order when COD is chosen. A later rate change, or a later
change to the classification after a CA review, cannot alter an order already
placed.

## 10. The tax classification caveat

> SAC 998599 is the configured application classification and must be validated by
> the business's GST adviser/CA before actual GST filing if the COD charge is
> intended to be treated as a separate service supply.

The software's role is to apply a configured classification consistently, freeze it
onto each order, and refuse to invent one. It does not and cannot determine the
correct legal treatment.

If the adviser concludes something different, the change is configuration, not code:

| Conclusion | Change |
|---|---|
| A different SAC is correct | New rule for that code; update `COD_FEE_SAC` |
| A different rate applies | New rule with a later `effective_from`; close the current one with `effective_to` |
| The charge is tax-exclusive | Set `COD_FEE_TAX_BASIS` to `EXCLUSIVE` |
| It is a composite supply, taxed at the goods' rate | **Stop** — that is a different model, not a configuration change. The charge would cease to be a separate service line, and the invoice and accounting presentation would need re-approval |
| The charge should not be levied | Set `COD_FEE_PAISE` to `0`, or `COD_ENABLED` to `false` |

Nothing in that table requires a release except the composite-supply case, which is
flagged as a stop condition rather than accommodated speculatively.
