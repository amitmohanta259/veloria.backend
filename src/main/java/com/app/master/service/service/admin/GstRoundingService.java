package com.app.master.service.service.admin;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The single GST rounding authority.
 *
 * Every GST amount in the system must be produced here so that item-level tax,
 * invoice-level tax, credit-note tax and period summaries agree.
 *
 * Policy
 * ------
 * 1. Tax is computed per line item on the item's taxable value, never on an
 *    order-level aggregate. The order total is the sum of already-rounded item
 *    amounts, so item sums always reconcile to the invoice.
 * 2. Rounding is HALF_UP to the paise, which is the convention Indian invoicing
 *    software uses and what CBIC's own rounding guidance implies for tax amounts.
 *    The previous implementation truncated (integer division by 10000), which
 *    systematically under-reported liability by up to 1 paise per line.
 * 3. Rates are basis points (bp): 250 = 2.5%. taxable * bp / 10000.
 * 4. A reversal of N units out of Q reverses a proportional share computed from
 *    the original snapshot, with the final unit absorbing any rounding remainder
 *    so that reversing every unit returns exactly the original amount and never
 *    more (invariant: sum of reversals <= original).
 */
@Service
public class GstRoundingService {

    private static final BigDecimal BP_DIVISOR = BigDecimal.valueOf(10000);

    /** Tax on a taxable value at a basis-point rate, rounded HALF_UP to paise. */
    public long taxOn(long taxableValuePaise, int rateBp) {
        if (rateBp <= 0 || taxableValuePaise == 0) return 0L;
        return BigDecimal.valueOf(taxableValuePaise)
                .multiply(BigDecimal.valueOf(rateBp))
                .divide(BP_DIVISOR, 0, RoundingMode.HALF_UP)
                .longValue();
    }

    /** Line taxable value. Kept here so quantity maths has one home. */
    public long taxableValue(long unitPricePaise, int quantity) {
        return unitPricePaise * (long) quantity;
    }

    /**
     * Proportional share of an original amount for {@code returnQty} of
     * {@code originalQty} units.
     *
     * When returnQty == originalQty this returns originalAmount exactly, so a
     * full return never over- or under-reverses because of rounding. For a
     * partial return it rounds HALF_UP, and the caller reconciles the remainder
     * on the final return via {@link #remainingShare}.
     */
    public long proportionalShare(long originalAmount, int returnQty, int originalQty) {
        if (originalQty <= 0 || returnQty <= 0) return 0L;
        if (returnQty >= originalQty) return originalAmount;
        return BigDecimal.valueOf(originalAmount)
                .multiply(BigDecimal.valueOf(returnQty))
                .divide(BigDecimal.valueOf(originalQty), 0, RoundingMode.HALF_UP)
                .longValue();
    }

    /**
     * Share for a return that settles the remaining units, given how much has
     * already been reversed. Guarantees the running total lands exactly on the
     * original amount rather than drifting by accumulated rounding.
     */
    public long remainingShare(long originalAmount, int returnQty, int originalQty,
                               int alreadyReturnedQty, long alreadyReversedAmount) {
        if (originalQty <= 0 || returnQty <= 0) return 0L;
        boolean settlesRemainder = (alreadyReturnedQty + returnQty) >= originalQty;
        if (settlesRemainder) {
            return originalAmount - alreadyReversedAmount;
        }
        return proportionalShare(originalAmount, returnQty, originalQty);
    }


    /**
     * Proportional share over long operands.
     *
     * Same rule as {@link #proportionalShare(long, int, int)}, but the part and
     * whole are paise amounts, which exceed the range of an int on a large
     * order. Kept here so there remains one place that rounds.
     */
    public long proportionalShare(long originalAmount, long part, long whole) {
        if (whole <= 0 || part <= 0) return 0L;
        if (part >= whole) return originalAmount;
        return BigDecimal.valueOf(originalAmount)
                .multiply(BigDecimal.valueOf(part))
                .divide(BigDecimal.valueOf(whole), 0, RoundingMode.HALF_UP)
                .longValue();
    }

    /** Converts rupees (as extracted from a vendor PDF) to paise, HALF_UP. */
    public long rupeesToPaise(BigDecimal rupees) {
        if (rupees == null) return 0L;
        return rupees.multiply(BigDecimal.valueOf(100))
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }
}
