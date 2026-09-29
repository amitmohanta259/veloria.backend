package com.app.master.service.core.testing;

import java.util.Arrays;
import java.util.Optional;

/** How much a defect matters. Assigned from the failure, never from a guess. */
public enum DefectSeverity {
    /** Money, tax or the ledger is wrong, or data is lost. */
    CRITICAL,
    /** A workflow is broken, or an authorization gap exists. */
    HIGH,
    /** Wrong behaviour with a workaround; missing validation. */
    MEDIUM,
    /** Cosmetic or inconvenient. */
    LOW;

    public static Optional<DefectSeverity> of(String raw) {
        if (raw == null) return Optional.empty();
        return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(raw.trim())).findFirst();
    }
}
