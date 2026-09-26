package com.app.master.service.core.payment;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The life of one refund.
 *
 * <p>Five states, because a refund is not one event. It is asked for, it is agreed
 * to, it is sent, and only then has the customer got their money — and each of
 * those can be where it stops.
 *
 * <p><b>Why five and not two.</b> P0-11 had {@code REQUESTED → COMPLETED | FAILED},
 * which worked because an online refund is agreed and sent in the same breath: the
 * gateway answers synchronously. A cash-on-delivery refund cannot work that way. It
 * can be approved and then have nowhere to go, because cash has no electronic
 * destination and the destination decision is not made. Without a state for
 * "agreed, not yet sent" such a refund has to be either pretended complete or
 * pretended never approved, and both are lies the ledger would inherit.
 *
 * <p>{@link #APPROVED} is therefore where a blocked refund rests: the business has
 * accepted that the money is owed, and nothing has moved.
 *
 * <p>Transitions are enforced rather than documented, for the same reason as
 * {@link PaymentStatus}: a gateway can answer twice, out of order, or after an
 * operator has already acted.
 */
public enum RefundStatus {

    /** The refund exists and has been judged eligible. Nothing has moved. */
    REQUESTED(false, false),

    /**
     * An authorised person has accepted that the money is owed.
     *
     * <p>Where a refund waits when it cannot yet be sent. A cash-on-delivery refund
     * stops here today, and that is the honest state — not a failure, because
     * nothing failed, and not a completion, because the customer has nothing.
     */
    APPROVED(false, false),

    /**
     * Handed to the gateway; the outcome is not yet known.
     *
     * <p>Money must be treated as gone from this point: it may already have left,
     * and a second attempt could send it twice.
     */
    PROCESSING(false, true),

    /** The gateway confirmed it. The customer has their money. */
    REFUNDED(true, true),

    /** It did not happen. No money moved, and no accounting may say otherwise. */
    FAILED(true, false);

    private final boolean terminal;
    private final boolean committed;

    RefundStatus(boolean terminal, boolean committed) {
        this.terminal = terminal;
        this.committed = committed;
    }

    /** Whether this refund can still change. */
    public boolean isTerminal() { return terminal; }

    /**
     * Whether this refund must be counted as money already going out.
     *
     * <p>True from {@link #PROCESSING} onwards. A refund in flight is spent even
     * though it is not confirmed — treating it as unspent is what would let a
     * second request send the same money again.
     */
    public boolean isCommitted() { return committed; }

    /** Whether the accounting for this refund should stand. */
    public boolean isAccountable() { return this == REFUNDED; }

    public Set<RefundStatus> allowedNext() {
        return switch (this) {
            // An online refund is approved and sent in one call, so PROCESSING is
            // reachable directly as well as through APPROVED.
            case REQUESTED  -> EnumSet.of(APPROVED, PROCESSING, FAILED);
            case APPROVED   -> EnumSet.of(PROCESSING, FAILED);
            case PROCESSING -> EnumSet.of(REFUNDED, FAILED);
            case REFUNDED, FAILED -> EnumSet.noneOf(RefundStatus.class);
        };
    }

    public boolean canMoveTo(RefundStatus next) {
        return next != null && allowedNext().contains(next);
    }

    public static Optional<RefundStatus> of(String raw) {
        if (raw == null) return Optional.empty();
        return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(raw.trim())).findFirst();
    }

    public static String namesOf(Set<RefundStatus> statuses) {
        return statuses.isEmpty() ? "nothing" : statuses.stream().map(Enum::name).sorted()
                .reduce((a, b) -> a + ", " + b).orElse("nothing");
    }
}
