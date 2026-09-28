package com.app.master.service.core.engineering;

import java.util.Arrays;
import java.util.Optional;

/** Which part of the system a finding belongs to — used for filtering, never encrypted. */
public enum AnomalyDomain {
    GST,
    ACCOUNTING,
    PAYMENT,
    REFUND,
    RETURN,
    INVENTORY,
    INVOICE,
    ORDER,
    COMPLIANCE,
    /**
     * Declared so the dashboard can show the domain with a zero count and an
     * explanation, rather than silently omitting it. No transportation rule exists:
     * pricing is UNDECIDED, so there is no expected value to compare against and
     * §51 excludes it until an approved rule exists.
     */
    TRANSPORTATION;

    public static Optional<AnomalyDomain> of(String raw) {
        if (raw == null) return Optional.empty();
        return Arrays.stream(values()).filter(d -> d.name().equalsIgnoreCase(raw.trim())).findFirst();
    }
}
