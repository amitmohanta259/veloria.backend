package com.app.master.service.core.engineering;

import java.util.Arrays;
import java.util.Optional;

/**
 * Where an anomaly stands.
 *
 * <p>The scanner may only ever create {@link #OPEN} (§49). It cannot resolve
 * anything, because it repairs nothing — a finding leaves OPEN only when a person
 * says so.
 */
public enum AnomalyStatus {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED,
    FALSE_POSITIVE;

    public static Optional<AnomalyStatus> of(String raw) {
        if (raw == null) return Optional.empty();
        return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(raw.trim())).findFirst();
    }
}
