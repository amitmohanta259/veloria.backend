package com.app.master.service.service.testing;

import com.app.master.service.core.entity.TestResultEntity;
import com.app.master.service.core.entity.TestRunEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.testing.RunStatus;
import com.app.master.service.core.testing.TestType;
import com.app.master.service.repository.testing.TestResultRepository;
import com.app.master.service.repository.testing.TestRunRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Runs a test suite because a person pressed a button.
 *
 * <h2>What makes this safe to expose</h2>
 * The request carries a {@link TestType}, a list of module keys and an environment
 * — three closed vocabularies. {@link TestCommandCatalog} turns those into a
 * command built from constants. {@link ProcessBuilder} is given an argument list,
 * so there is no shell to inject into: even if a module key somehow carried
 * {@code ; rm -rf /}, it would be rejected by the allowlist first and, failing
 * that, passed as one literal argument to Maven rather than interpreted.
 *
 * <p>The working directory is fixed by configuration. The browser cannot name a
 * path, a command, a flag or a test filter.
 *
 * <h2>One run at a time</h2>
 * Two suites against one database corrupt each other's fixtures, so a partial
 * unique index makes a second active run impossible and the second caller gets a
 * 409 rather than a race.
 */
@Service
@Slf4j
public class TestRunnerService {

    private static final DateTimeFormatter RUN_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TestRunRepository runRepository;
    private final TestResultRepository resultRepository;
    private final TestCommandCatalog catalog;
    private final TestReportParser parser;
    private final Executor executor;
    private final long timeoutMinutes;
    private final Path logDir;

    /** Live processes, so a cancel request can actually stop one. */
    private final Map<Long, Process> running = new ConcurrentHashMap<>();

    public TestRunnerService(TestRunRepository runRepository,
                             TestResultRepository resultRepository,
                             TestCommandCatalog catalog,
                             TestReportParser parser,
                             @Qualifier("testRunnerExecutor") Executor executor,
                             @Value("${testing.runner.timeout-minutes:60}") long timeoutMinutes,
                             @Value("${testing.runner.log-dir:}") String logDir) {
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.catalog = catalog;
        this.parser = parser;
        this.executor = executor;
        this.timeoutMinutes = timeoutMinutes;
        this.logDir = Paths.get(logDir == null || logDir.isBlank()
                ? System.getProperty("java.io.tmpdir") + "/veloria-test-runs" : logDir);
    }

    // ── request ──────────────────────────────────────────────────────────────

    /**
     * Validates the scope, records the run and starts it in the background.
     *
     * @throws VeloriaException BAD_REQUEST for a scope outside the allowlist,
     *                          CONFLICT when a run is already active
     */
    public TestRunEntity requestRun(String triggeredBy, String testTypeRaw,
                                    List<String> modulesRaw, String environmentRaw)
            throws VeloriaException {

        TestType type = TestType.of(testTypeRaw).orElseThrow(() -> new VeloriaException(
                ResponseCode.BAD_REQUEST, "Unknown test type '" + testTypeRaw + "'. Allowed: "
                + Arrays.toString(TestType.values())));

        List<String> modules;
        String environment;
        try {
            modules = catalog.validateModules(modulesRaw);
            environment = catalog.validateEnvironment(environmentRaw);
        } catch (IllegalArgumentException e) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, e.getMessage());
        }

        // Module selection only narrows the backend suite; saying "regression on
        // the gst module" and getting a full browser run would misreport the scope.
        if (!modules.isEmpty() && type != TestType.UNIT && type != TestType.SANITY && type != TestType.REGRESSION) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Module selection applies to UNIT, SANITY and REGRESSION runs only.");
        }

        List<TestRunEntity> active = runRepository.findActive();
        if (!active.isEmpty()) {
            throw alreadyRunning(active.get(0).getRunNumber(), active.get(0).getStatus());
        }

        TestRunEntity run = insertQueued(triggeredBy, type, modules, environment);
        log.info("AUDIT RUN_TESTS runNumber={} triggeredBy={} type={} modules={} environment={}",
                run.getRunNumber(), triggeredBy, type, modules, environment);

        executor.execute(() -> execute(run.getId()));
        return run;
    }

    private TestRunEntity insertQueued(String triggeredBy, TestType type,
                                       List<String> modules, String environment) throws VeloriaException {
        String moduleList = modules.isEmpty() ? null : String.join(",", modules);
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                return runRepository.saveAndFlush(TestRunEntity.builder()
                        .runNumber(nextRunNumber())
                        .triggeredBy(triggeredBy)
                        .testType(type.name())
                        .modules(moduleList)
                        .environment(environment)
                        .status(RunStatus.QUEUED.name())
                        .gitCommit(currentCommit())
                        .requestedAt(Instant.now())
                        .build());
            } catch (DataIntegrityViolationException e) {
                String cause = String.valueOf(e.getMostSpecificCause().getMessage());
                if (cause.contains("uq_eng_run_single_active")) throw alreadyRunning(null, null);
                if (!cause.contains("uq_eng_run_number") || attempt == 5) throw e;
            }
        }
        throw new VeloriaException(ResponseCode.CONFLICT, "Could not allocate a run number; try again.");
    }

    private VeloriaException alreadyRunning(String runNumber, String status) {
        String which = runNumber == null ? "A test run" : "Run " + runNumber;
        return new VeloriaException(ResponseCode.CONFLICT,
                "RUN_ALREADY_IN_PROGRESS: " + which + (status == null ? " is active" : " is " + status)
                + ". Only one test run may execute at a time — two suites against one database "
                + "corrupt each other's fixtures.");
    }

    private String nextRunNumber() {
        String prefix = "RUN-" + ZonedDateTime.now(ZoneOffset.UTC).format(RUN_DAY) + "-";
        return prefix + String.format("%04d", runRepository.maxSequenceForPrefix(prefix) + 1);
    }

    /** Records which build was tested. Best-effort — a missing git is not fatal. */
    private String currentCommit() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .directory(new java.io.File(catalog.backendDir()))
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ── cancellation ─────────────────────────────────────────────────────────

    public TestRunEntity cancel(String runNumber, String by) throws VeloriaException {
        TestRunEntity run = runRepository.findByRunNumber(runNumber).orElseThrow(
                () -> new VeloriaException(ResponseCode.NOT_FOUND, "Run " + runNumber + " was not found"));
        RunStatus current = RunStatus.of(run.getStatus()).orElseThrow();
        if (current.isTerminal()) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "Run " + runNumber + " has already finished (" + current + ").");
        }
        Process p = running.get(run.getId());
        if (p != null) p.destroy();
        log.info("AUDIT CANCEL_TEST_RUN runNumber={} by={}", runNumber, by);
        finish(run, RunStatus.CANCELLED, "Cancelled by " + by);
        return run;
    }

    // ── execution ────────────────────────────────────────────────────────────

    void execute(Long runId) {
        TestRunEntity run = runRepository.findById(runId).orElse(null);
        if (run == null) return;

        TestType type = TestType.of(run.getTestType()).orElse(TestType.UNIT);
        List<String> modules = run.getModules() == null || run.getModules().isBlank()
                ? List.of() : Arrays.asList(run.getModules().split(","));

        List<TestCommandCatalog.Step> steps;
        try {
            steps = catalog.stepsFor(type, modules);
        } catch (Exception e) {
            finish(run, RunStatus.FAILED, "Could not assemble the run: " + e.getMessage());
            return;
        }

        transition(run, RunStatus.RUNNING);
        run.setStartedAt(Instant.now());
        run.setCommandLabel(String.join(" · ", steps.stream().map(TestCommandCatalog.Step::label).toList()));
        runRepository.save(run);

        Path logFile;
        List<String> stepFailures = new ArrayList<>();
        boolean anyReport = false;

        try {
            Files.createDirectories(logDir);
            logFile = logDir.resolve(run.getRunNumber() + ".log");
            StringBuilder transcript = new StringBuilder();

            for (TestCommandCatalog.Step step : steps) {
                if (RunStatus.CANCELLED.name().equals(
                        runRepository.findById(runId).map(TestRunEntity::getStatus).orElse(""))) {
                    return;   // cancel() already wrote the terminal state
                }
                StepOutcome outcome = runStep(run, step, transcript);
                if (outcome.parsedAny) anyReport = true;
                if (outcome.failureNote != null) stepFailures.add(outcome.failureNote);
            }

            Files.writeString(logFile, transcript.toString());
            run.setLogPath(logFile.toString());
            tally(run);

            // A suite that reports failures is PARTIAL: it ran and did its job.
            // FAILED is reserved for a runner that could not produce a result —
            // conflating the two makes a real bug look like broken infrastructure.
            RunStatus outcome;
            if (!anyReport) {
                outcome = RunStatus.FAILED;
            } else if (run.getFailedCount() > 0) {
                outcome = RunStatus.PARTIAL;
            } else {
                outcome = RunStatus.COMPLETED;
            }
            finish(run, outcome, stepFailures.isEmpty() ? null : String.join(" | ", stepFailures));

        } catch (Exception e) {
            log.error("Test run {} failed", run.getRunNumber(), e);
            finish(run, RunStatus.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            running.remove(runId);
        }
    }

    private record StepOutcome(boolean parsedAny, String failureNote) {}

    private StepOutcome runStep(TestRunEntity run, TestCommandCatalog.Step step,
                                StringBuilder transcript) {
        transcript.append("\n=== ").append(step.label()).append(" ===\n")
                  .append("$ ").append(String.join(" ", step.command())).append('\n');
        // Captured before the process starts, so only reports this step wrote are read.
        Instant stepStartedAt = Instant.now();
        try {
            ProcessBuilder pb = new ProcessBuilder(step.command());
            pb.directory(new java.io.File(step.workingDir()));
            pb.redirectErrorStream(true);

            // Vite and Playwright need a modern Node; the JVM's PATH may not have
            // one. Prepending a configured bin directory is the whole extent of the
            // environment this runner controls.
            String nodeBin = catalog.nodeBinDir();
            if (nodeBin != null && !nodeBin.isBlank()) {
                pb.environment().merge("PATH", nodeBin, (existing, add) -> add + ":" + existing);
            }

            Process process = pb.start();
            running.put(run.getId(), process);

            StringBuilder stdout = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append('\n');
                }
            }

            boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                transcript.append("!! timed out after ").append(timeoutMinutes).append(" minutes\n");
                return new StepOutcome(false, step.label() + " timed out");
            }

            transcript.append(stdout);
            int exit = process.exitValue();

            List<TestResultEntity> results = parser.parse(
                    step.reportKind(), step.suite(), Paths.get(step.workingDir()),
                    stdout.toString(), stepStartedAt);
            results.forEach(r -> r.setRunId(run.getId()));
            if (!results.isEmpty()) resultRepository.saveAll(results);

            // A non-zero exit with parsed results is just "tests failed"; a
            // non-zero exit with no results means the command itself broke.
            String note = (exit != 0 && results.isEmpty())
                    ? step.label() + " exited " + exit + " with no parsable report" : null;
            return new StepOutcome(!results.isEmpty(), note);

        } catch (Exception e) {
            transcript.append("!! ").append(e).append('\n');
            return new StepOutcome(false, step.label() + ": " + e.getMessage());
        }
    }

    private void tally(TestRunEntity run) {
        long passed  = resultRepository.countByRunIdAndStatus(run.getId(), "PASSED");
        long failed  = resultRepository.countByRunIdAndStatus(run.getId(), "FAILED");
        long skipped = resultRepository.countByRunIdAndStatus(run.getId(), "SKIPPED");
        long blocked = resultRepository.countByRunIdAndStatus(run.getId(), "BLOCKED");
        run.setPassedCount((int) passed);
        run.setFailedCount((int) failed);
        run.setSkippedCount((int) skipped);
        run.setBlockedCount((int) blocked);
        run.setTotalCount((int) (passed + failed + skipped + blocked));
    }

    private void finish(TestRunEntity run, RunStatus status, String failureReason) {
        transition(run, status);
        run.setCompletedAt(Instant.now());
        if (failureReason != null) run.setFailureReason(failureReason);
        run.setModified(Instant.now());
        runRepository.save(run);
        log.info("Test run {} finished {} — {} passed, {} failed, {} skipped",
                run.getRunNumber(), status, run.getPassedCount(), run.getFailedCount(), run.getSkippedCount());
    }

    private void transition(TestRunEntity run, RunStatus next) {
        RunStatus current = RunStatus.of(run.getStatus()).orElseThrow(
                () -> new IllegalStateException("Run " + run.getRunNumber() + " has status " + run.getStatus()));
        if (current == next) return;
        if (!current.canMoveTo(next)) {
            throw new IllegalStateException("Run " + run.getRunNumber() + " cannot move from "
                    + current + " to " + next);
        }
        run.setStatus(next.name());
    }
}
