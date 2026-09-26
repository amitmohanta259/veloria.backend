package com.app.master.service.core.payment;

import java.util.Arrays;
import java.util.Optional;

/**
 * Why an order was cancelled.
 *
 * <p>A controlled list rather than free text, because these are counted and
 * reported on: "how often does delivery fail" is not answerable from a column
 * of typed sentences. P0-5A's {@code cancel_reason} column now carries one of
 * these names for cancellations raised through the payment-aware path, and
 * {@link #OTHER_APPROVED_REASON} exists so an operator with a genuinely novel
 * situation is not forced to mislabel it.
 */
public enum CancellationReason {

    /** Raised by the application when an online payment did not succeed. */
    PAYMENT_FAILED,

    /** The customer would not accept the delivery. */
    CUSTOMER_REFUSED_DELIVERY,

    /** The courier could not complete the delivery. */
    DELIVERY_FAILED,

    OPERATIONAL_ISSUE,
    ADDRESS_ISSUE,
    INVENTORY_ISSUE,
    ADMIN_REQUEST,

    /** The customer changed their mind. */
    CUSTOMER_REQUEST,

    OTHER_APPROVED_REASON;

    public static Optional<CancellationReason> of(String raw) {
        if (raw == null) return Optional.empty();
        String name = raw.trim().toUpperCase();
        return Arrays.stream(values()).filter(r -> r.name().equals(name)).findFirst();
    }

    public static String all() {
        return Arrays.stream(values()).map(Enum::name).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
