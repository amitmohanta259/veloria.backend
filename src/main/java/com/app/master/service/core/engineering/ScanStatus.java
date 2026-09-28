package com.app.master.service.core.engineering;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The life of one manual anomaly scan.
 *
 * <p>Transitions are enforced rather than documented, in the shape of the existing
 * {@code PaymentStatus} and {@code RefundStatus}: a scan that has finished must
 * never reopen, because its findings are attributed to it and a second execution
 * under the same id would make the history unreadable. A re-run is always a new
 * scan id.
 */
public enum ScanStatus {

    /** Created and holding the execution lock; the rules have not started. */
    QUEUED(false),

    /** Reading. The dashboard polls while a scan is here. */
    RUNNING(false),

    /** Every rule ran. */
    COMPLETED(true),

    /** At least one independent rule failed; the scan otherwise finished (§54). */
    PARTIAL(true),

    /** The scan could not proceed. Never hidden. */
    FAILED(true);

    private final boolean terminal;

    ScanStatus(boolean terminal) { this.terminal = terminal; }

    public boolean isTerminal() { return terminal; }

    /** True while the scan occupies the single active slot. */
    public boolean isActive() { return this == QUEUED || this == RUNNING; }

    public Set<ScanStatus> allowedNext() {
        return switch (this) {
            case QUEUED  -> EnumSet.of(RUNNING, FAILED);
            case RUNNING -> EnumSet.of(COMPLETED, PARTIAL, FAILED);
            case COMPLETED, PARTIAL, FAILED -> EnumSet.noneOf(ScanStatus.class);
        };
    }

    public boolean canMoveTo(ScanStatus next) {
        return next != null && allowedNext().contains(next);
    }

    public static Optional<ScanStatus> of(String raw) {
        if (raw == null) return Optional.empty();
        return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(raw.trim())).findFirst();
    }
}
