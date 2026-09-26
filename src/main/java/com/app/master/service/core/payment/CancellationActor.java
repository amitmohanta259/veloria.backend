package com.app.master.service.core.payment;

import com.app.master.service.core.order.OrderStatus;

import java.util.Arrays;
import java.util.Optional;

/**
 * Who cancelled an order, and what each of them is allowed to cancel.
 *
 * <p>The approved matrix, expressed against the statuses this application
 * actually has. The business names its stages "processing" and "shipped"; the
 * order machine has held {@code ORDER_PLACED}/{@code PACKED} and
 * {@code IN_TRANSIT} since P0-5A, so the mapping is stated here rather than
 * renaming states underneath a working lifecycle:
 *
 * <pre>
 *   business "processing" → ORDER_PLACED, PACKED
 *   business "shipped"    → IN_TRANSIT
 * </pre>
 *
 * <pre>
 *                      CUSTOMER   ADMIN   DELIVERY_PARTNER   SYSTEM
 *   ORDER_PLACED         yes       yes           no           yes
 *   PACKED               yes       yes           no           yes
 *   IN_TRANSIT           yes       yes           no           no
 *   OUT_FOR_DELIVERY     no        yes          yes           no
 *   DELIVERED            no        no            no           no
 * </pre>
 *
 * <p>A delivered order is never cancelled; it goes through the existing return
 * process instead.
 *
 * <p>{@code SYSTEM} exists only for events the application raises itself, which
 * today means a failed payment. It is deliberately not permitted after dispatch:
 * nothing automatic should be able to cancel goods that are already moving.
 */
public enum CancellationActor {

    CUSTOMER,
    ADMIN,
    DELIVERY_PARTNER,
    SYSTEM;

    /** Whether this actor may cancel an order currently in {@code status}. */
    public boolean mayCancelFrom(OrderStatus status) {
        return switch (this) {
            case CUSTOMER -> status == OrderStatus.ORDER_PLACED
                          || status == OrderStatus.PACKED
                          || status == OrderStatus.IN_TRANSIT;

            case ADMIN    -> status == OrderStatus.ORDER_PLACED
                          || status == OrderStatus.PACKED
                          || status == OrderStatus.IN_TRANSIT
                          || status == OrderStatus.OUT_FOR_DELIVERY;

            // Only at the doorstep, and only for the delivery outcomes below.
            case DELIVERY_PARTNER -> status == OrderStatus.OUT_FOR_DELIVERY;

            // A payment failure can only reach an order that has not moved.
            case SYSTEM   -> status == OrderStatus.ORDER_PLACED
                          || status == OrderStatus.PACKED;
        };
    }

    /** Whether a reason must be supplied. Only the customer may cancel without giving one. */
    public boolean requiresReason() {
        return this != CUSTOMER;
    }

    public static Optional<CancellationActor> of(String raw) {
        if (raw == null) return Optional.empty();
        String name = raw.trim().toUpperCase();
        return Arrays.stream(values()).filter(a -> a.name().equals(name)).findFirst();
    }
}
