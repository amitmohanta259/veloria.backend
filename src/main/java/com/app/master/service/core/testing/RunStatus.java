package com.app.master.service.core.testing;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * The life of one test run.
 *
 * <p>{@link #COMPLETED} means every test passed. A run with failures is
 * {@link #PARTIAL} — the suite finished and reported, which is a different fact
 * from {@link #FAILED}, where the runner could not produce a result at all. The
 * dashboard must not show a green tick for a run whose tests failed, and it must
 * not show "no results" for a run that genuinely found bugs.
 */
public enum RunStatus {
    QUEUED(false),
    RUNNING(false),
    /** Finished, everything passed. */
    COMPLETED(true),
    /** Finished, with failures. The tests did their job. */
    PARTIAL(true),
    /** The runner itself could not complete — timeout, missing tool, crash. */
    FAILED(true),
    CANCELLED(true);

    private final boolean terminal;
    RunStatus(boolean terminal) { this.terminal = terminal; }

    public boolean isTerminal() { return terminal; }
    public boolean isActive() { return this == QUEUED || this == RUNNING; }

    public Set<RunStatus> allowedNext() {
        return switch (this) {
            case QUEUED  -> EnumSet.of(RUNNING, CANCELLED, FAILED);
            case RUNNING -> EnumSet.of(COMPLETED, PARTIAL, FAILED, CANCELLED);
            default      -> EnumSet.noneOf(RunStatus.class);
        };
    }

    public boolean canMoveTo(RunStatus next) { return next != null && allowedNext().contains(next); }

    public static Optional<RunStatus> of(String raw) {
        if (raw == null) return Optional.empty();
        return Arrays.stream(values()).filter(s -> s.name().equalsIgnoreCase(raw.trim())).findFirst();
    }
}
