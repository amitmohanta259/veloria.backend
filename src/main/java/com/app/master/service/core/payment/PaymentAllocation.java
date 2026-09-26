package com.app.master.service.core.payment;

/**
 * How much of one payment went to each head of the invoice.
 *
 * <p>The approved waterfall settles the charges the business has already
 * incurred before any of the money is treated as payment for goods:
 *
 * <pre>
 *   1. GST                 in full
 *   2. Transportation      in full
 *   3. Other charges       in full
 *   4. Product             whatever remains
 * </pre>
 *
 * <p>Worked through with the approved example — product ₹10,000, GST ₹1,800,
 * transportation ₹500, other ₹200, so a ₹12,500 invoice and a ₹6,250 first
 * payment:
 *
 * <pre>
 *   GST            1,800   →  4,450 left
 *   Transport        500   →  3,950 left
 *   Other            200   →  3,750 left
 *   Product        3,750   →      0 left
 *   Product still outstanding: 10,000 − 3,750 = 6,250
 * </pre>
 *
 * <p>Every value is integer paise. There is no floating-point arithmetic
 * anywhere in this class, and none may be introduced: a half-paise rounding
 * error in an allocation is a permanent discrepancy between the payment record
 * and the invoice it settles.
 *
 * <p>An allocation is stored with the payment that produced it and is never
 * recomputed. Refunds and outstanding balances read the frozen figures, so a
 * later change to a product price or a tax rate cannot retrospectively alter
 * what a customer was recorded as having paid.
 */
public record PaymentAllocation(
        long amountPaise,
        long gstPaise,
        long transportPaise,
        long otherPaise,
        long productPaise) {

    public PaymentAllocation {
        if (amountPaise < 0 || gstPaise < 0 || transportPaise < 0 || otherPaise < 0 || productPaise < 0) {
            throw new IllegalArgumentException("A payment allocation cannot be negative");
        }
        long parts = gstPaise + transportPaise + otherPaise + productPaise;
        if (parts != amountPaise) {
            throw new IllegalStateException(
                    "Allocation does not add up: " + parts + " allocated against " + amountPaise + " paid");
        }
    }

    /**
     * Applies the waterfall to {@code amountPaise} of a payment against an
     * invoice that still owes the given amounts under each head.
     *
     * <p>Each head takes the lesser of what it is owed and what is left, so a
     * payment smaller than the charges settles them in priority order and the
     * product simply receives nothing — the arithmetic never goes negative and
     * never over-allocates.
     */
    public static PaymentAllocation waterfall(long amountPaise,
                                              long gstOutstanding,
                                              long transportOutstanding,
                                              long otherOutstanding,
                                              long productOutstanding) {
        if (amountPaise < 0) {
            throw new IllegalArgumentException("Cannot allocate a negative payment");
        }
        long left = amountPaise;

        long gst = Math.min(left, Math.max(0, gstOutstanding));
        left -= gst;

        long transport = Math.min(left, Math.max(0, transportOutstanding));
        left -= transport;

        long other = Math.min(left, Math.max(0, otherOutstanding));
        left -= other;

        long product = Math.min(left, Math.max(0, productOutstanding));
        left -= product;

        if (left > 0) {
            // More money than the invoice owes. Refusing is the only safe
            // answer: silently keeping the excess would be an unrecorded
            // over-collection, and spreading it over the heads would misstate
            // both the revenue and the tax.
            throw new IllegalStateException(
                    "Payment of " + amountPaise + " paise exceeds what the order still owes by " + left);
        }
        return new PaymentAllocation(amountPaise, gst, transport, other, product);
    }

    /**
     * The allocation recorded against a captured payment, as it was stored.
     *
     * <p>Read back, never recomputed — that is the whole point of storing it. An
     * attempt captured before an allocation was recorded reads as zero, which is
     * correct: nothing is known to have been allocated, so nothing is refundable
     * from it.
     */
    public static PaymentAllocation of(com.app.master.service.core.entity.PaymentAttemptEntity attempt) {
        long gst = nz(attempt.getGstAllocatedPaise());
        long transport = nz(attempt.getTransportAllocatedPaise());
        long other = nz(attempt.getOtherAllocatedPaise());
        long product = nz(attempt.getProductAllocatedPaise());
        return new PaymentAllocation(gst + transport + other + product, gst, transport, other, product);
    }

    /** The share of this payment that is refundable under the approved policy. */
    public long refundablePaise() {
        // Transportation is not refunded, and neither is the COD handling
        // charge that makes up "other". Product and its GST are.
        return productPaise + gstPaise;
    }

    private static long nz(Long v) { return v == null ? 0L : v; }
}
