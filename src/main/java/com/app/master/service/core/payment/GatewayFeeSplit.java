package com.app.master.service.core.payment;

/**
 * The approved division of a payment gateway's fee.
 *
 * <pre>
 *   Gateway fee = ₹X
 *   Business expense = ₹X / 2
 *   Customer charge  = ₹X / 2
 * </pre>
 *
 * <p>Two things this deliberately does not do:
 *
 * <ol>
 *   <li><b>Work out what the fee is.</b> It takes the fee the gateway actually
 *       charged. There is no rate here, no 2%, no percentage of anything — a rate
 *       would be a prediction of the fee, and the split is meant to divide the
 *       real one.</li>
 *   <li><b>Put either half in the ledger.</b> Posting is a separate question with
 *       its own open decisions; see the gateway-fee blockers in the P0-11
 *       report.</li>
 * </ol>
 *
 * <p>An odd fee cannot be halved exactly. The business takes the extra paisa, so
 * the customer is never charged more than half — the same direction of rounding
 * the partial-payment split already uses, and for the same reason.
 */
public record GatewayFeeSplit(
        long grossPaise,
        long feePaise,
        long businessPaise,
        long customerPaise,
        long netSettlementPaise) {

    /**
     * Splits a fee the gateway reported.
     *
     * @param grossPaise what the customer paid
     * @param feePaise   what the gateway charged, as the gateway stated it
     * @throws IllegalArgumentException if the fee exceeds the payment, which would
     *                                  mean the figures do not describe one
     *                                  transaction
     */
    public static GatewayFeeSplit of(long grossPaise, long feePaise) {
        if (feePaise < 0 || grossPaise < 0) {
            throw new IllegalArgumentException(
                    "A payment of " + grossPaise + " paise with a fee of " + feePaise
                    + " paise is not a transaction that happened");
        }
        if (feePaise > grossPaise) {
            throw new IllegalArgumentException(
                    "Gateway fee of " + feePaise + " paise exceeds the " + grossPaise
                    + " paise payment it was charged on");
        }
        long customer = feePaise / 2;
        long business = feePaise - customer;
        return new GatewayFeeSplit(grossPaise, feePaise, business, customer, grossPaise - feePaise);
    }
}
