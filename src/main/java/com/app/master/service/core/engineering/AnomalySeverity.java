package com.app.master.service.core.engineering;

import java.util.Arrays;
import java.util.Optional;

/**
 * How much an anomaly matters.
 *
 * <p>Severity comes from the rule that produced the finding, deterministically —
 * never from a heuristic over the data, so the same condition is always classified
 * the same way. §48 warns against over-classifying: CRITICAL is reserved for the
 * books not balancing.
 */
public enum AnomalySeverity {

    /** The ledger itself is wrong — an unbalanced journal. */
    CRITICAL,

    /** Money or tax is inconsistent: GST totals, a refund exceeding what was collected. */
    HIGH,

    /** A reconciliation or classification gap that does not misstate an amount. */
    MEDIUM,

    /** Missing optional metadata. */
    LOW,

    /** Observational; no action implied. */
    INFO;

    public static Optional<AnomalySeverity> of(String raw) {
        if (raw == null) return Optional.empty();
        return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(raw.trim())).findFirst();
    }
}
