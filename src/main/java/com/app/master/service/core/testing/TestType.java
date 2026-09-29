package com.app.master.service.core.testing;

import java.util.Arrays;
import java.util.Optional;

/**
 * What a run covers.
 *
 * <p>A closed set on purpose. The dashboard sends one of these names and the
 * runner maps it to a fixed command — the browser never supplies a command, an
 * argument or a path, which is what keeps a "run tests" button from becoming
 * remote shell access.
 */
public enum TestType {
    /** The fastest meaningful signal: tagged smoke scenarios only. */
    SMOKE,
    /** A broader confidence check, still short of the full estate. */
    SANITY,
    /** Backend JUnit only — no browser, no HTTP. */
    UNIT,
    /** The complete registered estate: backend, Cucumber, both browser suites. */
    REGRESSION,
    /** Browser journeys end to end. */
    E2E;

    public static Optional<TestType> of(String raw) {
        if (raw == null) return Optional.empty();
        return Arrays.stream(values()).filter(t -> t.name().equalsIgnoreCase(raw.trim())).findFirst();
    }
}
