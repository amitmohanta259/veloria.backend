package com.app.master.service.core.payment;

import com.app.master.service.core.entity.CustomerOrderEntity;

/**
 * What an order actually costs, read from the order's own stored figures.
 *
 * <pre>
 *   Product amount      taxable_value          ┐
 * + Product GST         total_tax_amount       ┘ together, total_value
 * + Transportation      shipping_value
 * + Other charges       — no charge of this kind exists today
 * + COD fee             cod_fee_paise
 * + COD fee GST         cod_fee_tax_paise
 * = Final invoice total
 * </pre>
 *
 * <p>Each charge is its own component. The COD fee used to be folded in with
 * "other charges", which made it impossible to say from the invoice what the
 * customer was charged for handling as opposed to anything else — and the
 * approved treatment now recognises it as income of its own, so it cannot share
 * a line with something that is not.
 *
 * <p>This is the only place a payable amount is derived, and it derives it from
 * the database. A figure sent by the browser is never consulted: the customer's
 * device is not a trustworthy source for how much the customer owes.
 *
 * <p>The GST figures are taken from the order's snapshots exactly as they were
 * written. Nothing here recalculates tax, applies a rate, or knows what a rate
 * is.
 */
public record OrderInvoice(
        long productPaise,
        long gstPaise,
        long transportPaise,
        long otherChargesPaise,
        /** The configured charge: the taxable value when exclusive, the gross when inclusive. */
        long codFeePaise,
        /** The value the COD tax was actually charged on. */
        long codFeeTaxablePaise,
        long codFeeTaxPaise) {

    public static OrderInvoice of(CustomerOrderEntity order) {
        return new OrderInvoice(
                nz(order.getTaxableValue()),
                nz(order.getTotalTaxAmount()),
                nz(order.getShippingValue()),
                // No column, and no approved charge of this kind. Kept as its own
                // component so a future one has somewhere to go that is not the
                // COD fee's line.
                0L,
                nz(order.getCodFeePaise()),
                // Falls back to the fee for an order whose charge was never taxed, so
                // gross stays the fee for every order placed before COD was taxed.
                nz(order.getCodFeeTaxablePaise()) > 0
                        ? nz(order.getCodFeeTaxablePaise()) : nz(order.getCodFeePaise()),
                nz(order.getCodFeeTaxPaise()));
    }

    /**
     * What the customer owes for the COD handling charge, tax included.
     *
     * <pre>
     *   gross = taxable + tax
     * </pre>
     *
     * <p>That one expression is correct under both tax bases, which is why it is
     * used instead of adding the fee and the tax together:
     *
     * <ul>
     *   <li><b>Exclusive</b> — taxable is the ₹50 charge and the tax is added on
     *       top, so gross is ₹50 + tax.</li>
     *   <li><b>Inclusive</b> — the ₹50 charge already contains the tax, so taxable
     *       is ₹42.37 and the tax ₹7.63, and gross is back to ₹50.</li>
     * </ul>
     *
     * <p>Adding {@code codFeePaise + codFeeTaxPaise} would be right for the first
     * and wrong for the second: it would bill an inclusive ₹50 charge as ₹57.63,
     * quoting the customer one figure at checkout and charging another. That is the
     * shape of the defect this replaced.
     *
     * <p>A historical order whose charge was never taxed has taxable equal to the
     * fee and no tax, so this returns the fee — unchanged for every order already
     * placed.
     */
    public long codGrossPaise() {
        return codFeeTaxablePaise + codFeeTaxPaise;
    }

    /** Everything the customer owes for this order, in paise. */
    public long finalInvoiceTotalPaise() {
        return productPaise + gstPaise + transportPaise
                + otherChargesPaise + codGrossPaise();
    }

    /**
     * The "other" head of the approved payment waterfall.
     *
     * <p>The approved waterfall settles GST, then transportation, then other,
     * then product. The COD fee and its tax sit in the other head, which is where
     * the fee has always been allocated — separating them in the invoice above is
     * about what the customer is told and what the books record, and is not a
     * licence to reorder an approved waterfall. The GST head stays strictly the
     * product's GST for the same reason.
     */
    public long otherPaise() {
        return otherChargesPaise + codGrossPaise();
    }

    /**
     * The first collection of a partially paid order: half the invoice.
     *
     * <p>Rounded <em>down</em> to the paise. An odd total cannot be halved
     * exactly, and taking the lower half means the customer is never asked for
     * more than they owe up front, while {@link #partialBalancePaise()} carries
     * the odd paise so the two collections add up to the invoice precisely.
     */
    public long partialFirstPaise() {
        return finalInvoiceTotalPaise() / 2;
    }

    /** The balance of a partially paid order, after the first collection. */
    public long partialBalancePaise() {
        return finalInvoiceTotalPaise() - partialFirstPaise();
    }

    private static long nz(Long v) { return v == null ? 0L : v; }
}
