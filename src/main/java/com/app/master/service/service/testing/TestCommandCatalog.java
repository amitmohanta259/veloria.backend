package com.app.master.service.service.testing;

import com.app.master.service.core.testing.TestType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The allowlist of commands the dashboard may run.
 *
 * <p><b>This class is the security boundary.</b> The browser sends a test type and
 * a module key — two closed vocabularies — and gets back a command assembled here
 * from constants. Nothing from the request is ever concatenated into a shell
 * string, no shell is involved at all, and a module name that is not in
 * {@link #MODULES} is rejected rather than passed through.
 *
 * <p>That is what separates "a button that runs the test suite" from remote code
 * execution. A design where the client names the command, the path, or a test
 * filter would be the latter however carefully the string was escaped.
 */
@Component
public class TestCommandCatalog {

    /**
     * Module keys the dashboard may select, mapped to the Maven test pattern they
     * stand for. Values are literals in this file — never user input.
     */
    static final Map<String, String> MODULES = Map.ofEntries(
            Map.entry("gst",          "com.app.master.service.gst.*Test"),
            Map.entry("accounting",   "com.app.master.service.accounting.*Test"),
            Map.entry("payment",      "com.app.master.service.payment.*Test"),
            Map.entry("order",        "com.app.master.service.order.*Test"),
            Map.entry("inventory",    "com.app.master.service.inventory.*Test"),
            Map.entry("engineering",  "com.app.master.service.engineering.*Test"),
            Map.entry("security",     "com.app.master.service.security.*Test"),
            Map.entry("client",       "com.app.master.service.client.*Test"),
            Map.entry("validation",   "com.app.master.service.validation.*Test")
    );

    /** Environments a run may target. Production is deliberately absent. */
    static final Set<String> ENVIRONMENTS = Set.of("local", "dev");

    private final String backendDir;
    private final String mavenBinary;
    private final String npxBinary;
    private final String nodeBinDir;

    public TestCommandCatalog(
            @Value("${testing.runner.backend-dir:}") String backendDir,
            @Value("${testing.runner.maven:mvn}") String mavenBinary,
            @Value("${testing.runner.npx:npx}") String npxBinary,
            @Value("${testing.runner.node-bin-dir:}") String nodeBinDir) {
        this.backendDir = backendDir == null || backendDir.isBlank()
                ? System.getProperty("user.dir") : backendDir.trim();
        this.mavenBinary = mavenBinary;
        this.npxBinary = npxBinary;
        this.nodeBinDir = nodeBinDir;
    }

    public String backendDir() { return backendDir; }
    public String nodeBinDir() { return nodeBinDir; }

    /** One command in a run: what to execute, where, and how to read its report. */
    public record Step(String suite, String label, List<String> command, String workingDir, String reportKind) {}

    public static final String REPORT_SUREFIRE  = "SUREFIRE";
    public static final String REPORT_CUCUMBER  = "CUCUMBER_JSON";
    public static final String REPORT_PLAYWRIGHT = "PLAYWRIGHT_JSON";

    /**
     * The steps for one requested scope.
     *
     * @param modules already validated against {@link #MODULES} by the caller
     */
    public List<Step> stepsFor(TestType type, List<String> modules) {
        List<Step> steps = new ArrayList<>();
        String automation = backendDir + "/automation-test";
        String playwright = automation + "/playwright";

        switch (type) {
            case UNIT -> steps.add(backendStep(modules));

            // Smoke is the fastest honest signal: the tagged browser journeys.
            case SMOKE -> steps.add(new Step("PLAYWRIGHT_CLIENT", "Playwright @smoke",
                    List.of(npxBinary, "playwright", "test", "--project=chromium",
                            "--grep", "@smoke", "--reporter=json"),
                    playwright, REPORT_PLAYWRIGHT));

            case SANITY -> {
                steps.add(backendStep(modules));
                steps.add(new Step("PLAYWRIGHT_CLIENT", "Playwright @smoke",
                        List.of(npxBinary, "playwright", "test", "--project=chromium",
                                "--grep", "@smoke", "--reporter=json"),
                        playwright, REPORT_PLAYWRIGHT));
            }

            case E2E -> {
                steps.add(playwrightStep("PLAYWRIGHT_CLIENT", "chromium"));
                steps.add(playwrightStep("PLAYWRIGHT_ADMIN", "chromium-admin"));
            }

            // The complete registered estate. Cucumber runs unfiltered — the
            // default `not @mutates` filter would quietly skip 26 scenarios and
            // report a smaller suite as a full regression.
            case REGRESSION -> {
                steps.add(backendStep(modules));
                steps.add(new Step("CUCUMBER", "Cucumber (unfiltered)",
                        List.of(mavenBinary, "-o", "test", "-Dcucumber.filter.tags="),
                        automation, REPORT_CUCUMBER));
                steps.add(playwrightStep("PLAYWRIGHT_CLIENT", "chromium"));
                steps.add(playwrightStep("PLAYWRIGHT_ADMIN", "chromium-admin"));
            }
        }
        return steps;
    }

    private Step backendStep(List<String> modules) {
        List<String> command = new ArrayList<>(List.of(mavenBinary, "-o", "test"));
        String label = "Backend JUnit (all)";
        if (modules != null && !modules.isEmpty()) {
            // Built from MODULES values, not from the request text.
            String pattern = String.join(",", modules.stream().map(MODULES::get).toList());
            command.add("-Dtest=" + pattern);
            command.add("-Dsurefire.failIfNoSpecifiedTests=false");
            label = "Backend JUnit (" + String.join(", ", modules) + ")";
        }
        return new Step("BACKEND", label, List.copyOf(command), backendDir, REPORT_SUREFIRE);
    }

    private Step playwrightStep(String suite, String project) {
        return new Step(suite, "Playwright " + project,
                List.of(npxBinary, "playwright", "test", "--project=" + project, "--reporter=json"),
                backendDir + "/automation-test/playwright", REPORT_PLAYWRIGHT);
    }

    /** Rejects anything not in the allowlist, rather than passing it through. */
    public List<String> validateModules(List<String> requested) {
        if (requested == null || requested.isEmpty()) return List.of();
        List<String> clean = new ArrayList<>();
        for (String m : requested) {
            if (m == null || m.isBlank()) continue;
            String key = m.trim().toLowerCase(Locale.ROOT);
            if (!MODULES.containsKey(key)) {
                throw new IllegalArgumentException("Unknown module '" + m + "'. Allowed: "
                        + String.join(", ", MODULES.keySet().stream().sorted().toList()));
            }
            if (!clean.contains(key)) clean.add(key);
        }
        return clean;
    }

    public String validateEnvironment(String requested) {
        String env = requested == null || requested.isBlank() ? "local" : requested.trim().toLowerCase(Locale.ROOT);
        if (!ENVIRONMENTS.contains(env)) {
            throw new IllegalArgumentException("Environment '" + requested + "' is not approved for test runs. "
                    + "Allowed: " + String.join(", ", ENVIRONMENTS.stream().sorted().toList()));
        }
        return env;
    }

    public List<String> moduleKeys() { return MODULES.keySet().stream().sorted().toList(); }
    public List<String> environments() { return ENVIRONMENTS.stream().sorted().toList(); }
}
