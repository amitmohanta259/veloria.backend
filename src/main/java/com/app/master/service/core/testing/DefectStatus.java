package com.app.master.service.core.testing;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The defect lifecycle.
 *
 * <pre>
 * NEW → CONFIRMED → IN_PROGRESS → FIXED → READY_FOR_RETEST → VERIFIED → CLOSED
 *                                                   ↑                      │
 *                                                REOPENED ←────────────────┘
 * </pre>
 *
 * <p>A defect cannot jump from IN_PROGRESS to CLOSED. Closing requires a retest
 * that actually ran, which is why VERIFIED exists as its own state rather than
 * being folded into CLOSED: "somebody says it works" and "the failing test now
 * passes" are different claims.
 */
public enum DefectStatus {
    NEW(true),
    CONFIRMED(true),
    IN_PROGRESS(true),
    FIXED(true),
    READY_FOR_RETEST(true),
    VERIFIED(true),
    /** Out of the default open list, never out of the register. */
    CLOSED(false),
    REOPENED(true);

    private final boolean open;
    DefectStatus(boolean open) { this.open = open; }

    /** Whether it belongs in the default Open Defects list. */
    public boolean isOpen() { return open; }

    public Set<DefectStatus> allowedNext() {
        return switch (this) {
            case NEW              -> EnumSet.of(CONFIRMED, IN_PROGRESS, CLOSED);
            case CONFIRMED        -> EnumSet.of(IN_PROGRESS, CLOSED);
            case IN_PROGRESS      -> EnumSet.of(FIXED, CONFIRMED);
            case FIXED            -> EnumSet.of(READY_FOR_RETEST, IN_PROGRESS);
            case READY_FOR_RETEST -> EnumSet.of(VERIFIED, IN_PROGRESS);
            // Only a verified defect may close. This is the gate that stops a
            // defect being closed because somebody changed code.
            case VERIFIED         -> EnumSet.of(CLOSED, IN_PROGRESS);
            case CLOSED           -> EnumSet.of(REOPENED);
            case REOPENED         -> EnumSet.of(CONFIRMED, IN_PROGRESS);
        };
    }

    public boolean canMoveTo(DefectStatus next) { return next != null && allowedNext().contains(next); }

    public static Optional<DefectStatus> of(String raw) {
        if (raw == null) return Optional.empty();
        return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(raw.trim())).findFirst();
    }
}
