package com.app.master.service.core.payment;

import java.util.Arrays;
import java.util.Optional;

/**
 * How the customer chose to pay for an order.
 *
 * <p>Distinct from the instrument the gateway ends up using: a customer who
 * chooses {@link #ONLINE_FULL} may pay by card or UPI, and which of those it
 * was is reported by Razorpay afterwards and stored on the attempt.
 */
public enum PaymentMode {

    /** The whole invoice, online, in one collection. */
    ONLINE_FULL(true),

    /** Half the invoice online now, the balance later. */
    ONLINE_PARTIAL(true),

    /** Cash on delivery. No gateway is involved at any point. */
    COD(false);

    private final boolean online;

    PaymentMode(boolean online) { this.online = online; }

    /** Whether this mode collects through the payment gateway. */
    public boolean isOnline() { return online; }

    public static Optional<PaymentMode> of(String raw) {
        if (raw == null) return Optional.empty();
        String name = raw.trim().toUpperCase();
        return Arrays.stream(values()).filter(m -> m.name().equals(name)).findFirst();
    }
}
