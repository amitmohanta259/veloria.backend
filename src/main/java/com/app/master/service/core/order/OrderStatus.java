package com.app.master.service.core.order;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The statuses a customer order can hold, what each one means for stock, and
 * which moves between them the business actually performs.
 *
 * <p>Before this existed the lifecycle lived in two places that did not agree:
 * the admin screen decided which buttons to render, and the backend wrote
 * whatever string it was handed. A caller bypassing the screen could move an
 * order anywhere — including out of {@code CANCELLED}.
 *
 * <p>The transitions below are taken from behaviour the application already
 * performs, not from a redesign: the forward leg is the sequence the admin
 * screen offers, the return leg is the sequence the returns flow drives, and
 * the two terminal failure states are written by checkout itself. Where the
 * business meaning of a status could not be established from the code it is
 * marked {@link #isLegacy()} and given no transitions rather than guessed at.
 */
public enum OrderStatus {

    // ── Forward leg: the goods are on their way to the customer ──────────────

    /** Checkout succeeded. Stock is consumed from this moment. */
    ORDER_PLACED(true),
    PACKED(true),
    IN_TRANSIT(true),
    /** With the courier for the final hop. The goods are emphatically not on the shelf. */
    OUT_FOR_DELIVERY(true),
    DELIVERED(true),

    // ── Return leg: the goods are coming back, and are not sellable yet ──────

    /** A return has been requested. The goods are still with the customer. */
    READY_TO_PICKUP(true),
    PICKED_UP(true),
    IN_TRANSIT_TO_SELLER(true),
    /** Physically back, not yet inspected. Sellable only once a condition is recorded. */
    RECEIVED(true),
    /** Every line came back. What is resellable is added back by the return-condition credit. */
    RETURNED(true),
    /** Some lines came back; the rest are still sold. */
    PARTIALLY_RETURNED(true),

    // ── Terminal without a shipment ──────────────────────────────────────────

    /** Cancelled before dispatch. The goods never left, so they return to the shelf. */
    CANCELLED(false),
    /** Recorded when checkout could not be completed. Nothing was ever shipped. */
    PAYMENT_FAILED(false),

    // ── Legacy ───────────────────────────────────────────────────────────────

    /**
     * Named in the stock SQL that predates this enum but written by no code path
     * and held by no row. Kept so historical data would still be counted as
     * sold, and given no transitions because the business meaning is unknown.
     */
    DISPATCHED(true, true),
    DONE(true, true);

    private final boolean consumesStock;
    private final boolean legacy;

    OrderStatus(boolean consumesStock) { this(consumesStock, false); }

    OrderStatus(boolean consumesStock, boolean legacy) {
        this.consumesStock = consumesStock;
        this.legacy = legacy;
    }

    /**
     * Whether an order in this status is holding its units off the shelf.
     *
     * <p>The rule is physical, not commercial: stock is consumed for as long as
     * the goods are away from the warehouse. That covers the whole outbound
     * journey and the whole return journey — a return that has been
     * <em>requested</em> has not brought anything back. Units come back to
     * availability only when a return condition is recorded against the line,
     * which is the point at which someone has actually handled them.
     */
    public boolean consumesStock() { return consumesStock; }

    /** A name kept for historical rows, with no defined transitions. */
    public boolean isLegacy() { return legacy; }

    /**
     * Whether an order in this status is a sale the books should recognise.
     *
     * <p>Excluded are the two states in which the business never supplied
     * anything: an order cancelled before dispatch, and a checkout that failed.
     * Booking revenue and a receivable for either would overstate both.
     *
     * <p>This is deliberately <em>not</em> a payment test. An order that has
     * been supplied is a sale whether or not money has arrived — payment
     * collection is a separate event and is not implemented. The rule only
     * excludes orders the application itself has already marked as not
     * happening.
     *
     * <p>A returned order stays eligible: the supply did occur, and the return
     * is accounted for separately by its own credit note rather than by
     * pretending the original sale never took place.
     */
    public boolean isSaleEligible() {
        return this != CANCELLED && this != PAYMENT_FAILED;
    }

    public boolean isTerminal() { return allowedNext().isEmpty(); }

    /**
     * The moves the business performs out of this status.
     *
     * <p>Empty for the terminal states, and deliberately empty for the legacy
     * names: an order cannot be moved out of {@code CANCELLED}, and nothing may
     * transition into or out of a status whose meaning was never established.
     */
    public Set<OrderStatus> allowedNext() {
        return switch (this) {
            case ORDER_PLACED          -> EnumSet.of(PACKED, CANCELLED);
            case PACKED                -> EnumSet.of(IN_TRANSIT, CANCELLED);
            // Cancellation remains possible after dispatch under the approved
            // policy: a customer or an administrator may stop an order in
            // transit, and an administrator or the delivery partner may stop one
            // at the door. Who may do which is decided by CancellationActor; the
            // lifecycle only says the move itself is legal.
            case IN_TRANSIT            -> EnumSet.of(OUT_FOR_DELIVERY, CANCELLED);
            case OUT_FOR_DELIVERY      -> EnumSet.of(DELIVERED, CANCELLED);
            case DELIVERED             -> EnumSet.of(READY_TO_PICKUP);
            case READY_TO_PICKUP       -> EnumSet.of(PICKED_UP);
            case PICKED_UP             -> EnumSet.of(IN_TRANSIT_TO_SELLER);
            case IN_TRANSIT_TO_SELLER  -> EnumSet.of(RECEIVED);
            case RECEIVED              -> EnumSet.of(RETURNED, PARTIALLY_RETURNED);
            default                    -> EnumSet.noneOf(OrderStatus.class);
        };
    }

    public boolean canMoveTo(OrderStatus next) {
        return allowedNext().contains(next);
    }

    /** The status behind a stored or submitted string, or empty if it is not one we know. */
    public static Optional<OrderStatus> of(String raw) {
        if (raw == null) return Optional.empty();
        String name = raw.trim().toUpperCase();
        return Arrays.stream(values()).filter(s -> s.name().equals(name)).findFirst();
    }

    /** The statuses an order may be moved to by an operator, for error messages. */
    public static String namesOf(Set<OrderStatus> statuses) {
        return statuses.stream().map(Enum::name).sorted().reduce((a, b) -> a + ", " + b).orElse("none");
    }

    /**
     * The stock-consuming statuses, quoted for use inside a native {@code IN (…)}.
     *
     * <p>A literal because annotation values must be compile-time constants, and
     * every stock query is an annotated {@code @Query}. It is not free to drift:
     * {@code OrderStatusConsumptionTest} asserts it matches
     * {@link #consumesStock()} exactly, so adding a status without classifying
     * it fails the build rather than silently mis-counting inventory.
     */
    public static final String CONSUMING_SQL =
            "'ORDER_PLACED','PACKED','IN_TRANSIT','OUT_FOR_DELIVERY','DISPATCHED','DONE','DELIVERED',"
          + "'READY_TO_PICKUP','PICKED_UP','IN_TRANSIT_TO_SELLER','RECEIVED','RETURNED','PARTIALLY_RETURNED'";
}
