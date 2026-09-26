package com.app.master.service.core.payment;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The life of one payment attempt.
 *
 * <p>Deliberately separate from {@link com.app.master.service.core.order.OrderStatus}.
 * An order that is being packed while its payment is captured is perfectly
 * ordinary, and so is a cancelled order whose payment has been refunded; a
 * single status column could express neither. Nothing here reads or writes an
 * order status, and nothing in the order state machine reads one of these.
 *
 * <p>Transitions are enforced rather than documented. A gateway can deliver the
 * same event twice, out of order, or after the browser has already reported the
 * outcome, so every move is checked against this table before it is applied.
 */
public enum PaymentStatus {

    /** The attempt row exists; no gateway order has been created yet. */
    INITIATED(false),

    /** A Razorpay order exists and the customer is with the gateway. */
    PENDING(false),

    /** Funds confirmed and held by the gateway, not yet taken. */
    AUTHORIZED(false),

    /** Money taken. The only status that means the customer has paid. */
    CAPTURED(false),

    /** The attempt did not succeed. Terminal for this attempt. */
    FAILED(true),

    /** Abandoned before completion — the modal was dismissed, or it expired. */
    CANCELLED(true),

    /** The whole captured amount has been given back. */
    REFUNDED(true),

    /** Part given back; a balance remains captured, so further refunds are possible. */
    PARTIALLY_REFUNDED(false);

    private final boolean terminal;

    PaymentStatus(boolean terminal) { this.terminal = terminal; }

    /** Whether this attempt can still change. A new attempt is not a transition. */
    public boolean isTerminal() { return terminal; }

    /** Whether the customer's money is currently with the business. */
    public boolean isPaid() { return this == CAPTURED || this == PARTIALLY_REFUNDED; }

    public Set<PaymentStatus> allowedNext() {
        return switch (this) {
            // A gateway may capture without a separate authorization step, so
            // CAPTURED is reachable directly as well as through AUTHORIZED.
            case INITIATED          -> EnumSet.of(PENDING, FAILED, CANCELLED);
            case PENDING            -> EnumSet.of(AUTHORIZED, CAPTURED, FAILED, CANCELLED);
            case AUTHORIZED         -> EnumSet.of(CAPTURED, FAILED, CANCELLED);
            case CAPTURED           -> EnumSet.of(REFUNDED, PARTIALLY_REFUNDED);
            case PARTIALLY_REFUNDED -> EnumSet.of(REFUNDED, PARTIALLY_REFUNDED);
            default                 -> EnumSet.noneOf(PaymentStatus.class);
        };
    }

    public boolean canMoveTo(PaymentStatus next) {
        return allowedNext().contains(next);
    }

    public static Optional<PaymentStatus> of(String raw) {
        if (raw == null) return Optional.empty();
        String name = raw.trim().toUpperCase();
        return Arrays.stream(values()).filter(s -> s.name().equals(name)).findFirst();
    }

    public static String namesOf(Set<PaymentStatus> statuses) {
        return statuses.stream().map(Enum::name).sorted().reduce((a, b) -> a + ", " + b).orElse("none");
    }
}
