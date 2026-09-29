package com.app.master.service.service.testing;

import com.app.master.service.core.entity.DefectEntity;
import com.app.master.service.core.entity.TestResultEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.testing.DefectSeverity;
import com.app.master.service.core.testing.DefectStatus;
import com.app.master.service.repository.testing.DefectRepository;
import com.app.master.service.repository.testing.TestResultRepository;
import com.app.master.service.repository.testing.TestRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The defect register and its lifecycle.
 *
 * <p>Two rules shape everything here.
 *
 * <p><b>A failure is not automatically a defect.</b> A test can fail because the
 * product is wrong, because the test is wrong, or because the environment is. So a
 * failure raises a defect in {@link DefectStatus#NEW} — a <em>suspected</em>
 * defect — and a person moves it to CONFIRMED. Nothing here decides a root cause.
 *
 * <p><b>Closing requires a retest that actually ran.</b> {@link DefectStatus} will
 * not let a defect reach CLOSED except through VERIFIED, and
 * {@link #verify} refuses unless the named run genuinely re-executed the failing
 * test and it passed. Changing code and asserting success is not closure.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DefectService {

    private final DefectRepository defectRepository;
    private final TestRunRepository runRepository;
    private final TestResultRepository resultRepository;

    // ── raising ──────────────────────────────────────────────────────────────

    /**
     * Records the failures of a run as suspected defects.
     *
     * <p>A failure whose fingerprint matches a CLOSED defect reopens it rather than
     * creating a duplicate — the history, the original evidence and the number all
     * survive. One that matches an open defect just refreshes its last-seen run.
     *
     * @return how many defects were newly raised, and how many were reopened
     */
    @Transactional
    public RaiseOutcome raiseFromRun(String runNumber, String by) throws VeloriaException {
        var run = runRepository.findByRunNumber(runNumber).orElseThrow(
                () -> new VeloriaException(ResponseCode.NOT_FOUND, "Run " + runNumber + " was not found"));

        List<TestResultEntity> failures =
                resultRepository.findByRunIdAndStatusOrderByIdAsc(run.getId(), "FAILED");

        int raised = 0, reopened = 0, updated = 0;
        for (TestResultEntity failure : failures) {
            String fingerprint = fingerprint(failure);
            Optional<DefectEntity> existing = defectRepository.findByFingerprint(fingerprint);

            if (existing.isPresent()) {
                DefectEntity d = existing.get();
                d.setLastSeenRun(runNumber);
                d.setActualBehavior(redact(failure.getFailureMessage()));
                d.setModified(Instant.now());
                if (DefectStatus.CLOSED.name().equals(d.getStatus())) {
                    d.setStatus(DefectStatus.REOPENED.name());
                    d.setReopenCount(d.getReopenCount() + 1);
                    d.setClosedAt(null);
                    d.setVerifiedAt(null);
                    reopened++;
                    log.info("AUDIT DEFECT_REOPENED defect={} run={} by={}", d.getDefectNumber(), runNumber, by);
                } else {
                    updated++;
                }
                defectRepository.save(d);
                continue;
            }

            DefectEntity d = defectRepository.save(DefectEntity.builder()
                    .defectNumber(nextDefectNumber())
                    .fingerprint(fingerprint)
                    .title(title(failure))
                    .module(failure.getModule())
                    .feature(failure.getClassName())
                    .category("BUG")
                    .subcategory(subcategory(failure))
                    .severity(severity(failure).name())
                    .priority(severity(failure).name())
                    // NEW, not CONFIRMED: this is a suspected defect until someone
                    // establishes whether the product, the test or the environment
                    // is at fault.
                    .status(DefectStatus.NEW.name())
                    .description("Raised automatically from test run " + runNumber + ".")
                    .expectedBehavior("The test asserts the expected behaviour; see the test source.")
                    .actualBehavior(redact(failure.getFailureMessage()))
                    .reproductionSteps("Run " + failure.getSuite() + " test: "
                            + failure.getClassName() + " › " + failure.getTestName())
                    .firstSeenRun(runNumber)
                    .lastSeenRun(runNumber)
                    .openedAt(Instant.now())
                    .openedBy(by)
                    .build());
            raised++;
            log.info("AUDIT DEFECT_RAISED defect={} run={} test={}",
                    d.getDefectNumber(), runNumber, failure.getTestName());
        }
        return new RaiseOutcome(raised, reopened, updated, failures.size());
    }

    public record RaiseOutcome(int raised, int reopened, int refreshed, int failuresSeen) {}

    // ── lifecycle ────────────────────────────────────────────────────────────

    @Transactional
    public DefectEntity transition(String defectNumber, String toStatus, String by, String note)
            throws VeloriaException {

        DefectEntity d = require(defectNumber);
        DefectStatus current = DefectStatus.of(d.getStatus()).orElseThrow();
        DefectStatus next = DefectStatus.of(toStatus).orElseThrow(() -> new VeloriaException(
                ResponseCode.BAD_REQUEST, "Unknown defect status '" + toStatus + "'"));

        if (next == DefectStatus.CLOSED) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "A defect closes through the retest flow, not a status change. "
                    + "Verify it against a run first.");
        }
        if (!current.canMoveTo(next)) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Defect " + defectNumber + " cannot move from " + current + " to " + next
                    + ". Allowed: " + current.allowedNext());
        }

        d.setStatus(next.name());
        if (next == DefectStatus.FIXED) d.setFixedAt(Instant.now());
        if (note != null && !note.isBlank()) {
            d.setActualFix(append(d.getActualFix(), by + ": " + note));
        }
        d.setModified(Instant.now());
        log.info("AUDIT DEFECT_TRANSITION defect={} {} → {} by={}", defectNumber, current, next, by);
        return defectRepository.save(d);
    }

    /**
     * Verifies a defect against a run that actually re-executed its failing test.
     *
     * <p>This is the gate. The run must contain the test named in the defect's
     * fingerprint, and that test must have passed. A run that never executed the
     * test proves nothing, and is refused with that reason rather than accepted.
     */
    @Transactional
    public DefectEntity verify(String defectNumber, String runNumber, String by) throws VeloriaException {
        DefectEntity d = require(defectNumber);
        var run = runRepository.findByRunNumber(runNumber).orElseThrow(
                () -> new VeloriaException(ResponseCode.NOT_FOUND, "Run " + runNumber + " was not found"));

        List<TestResultEntity> inRun = resultRepository.findByRunIdOrderByStatusAscIdAsc(run.getId());
        Optional<TestResultEntity> match = inRun.stream()
                .filter(r -> fingerprint(r).equals(d.getFingerprint()))
                .findFirst();

        if (match.isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Run " + runNumber + " did not execute the test this defect was raised from, "
                    + "so it cannot verify it. Re-run the suite that covers "
                    + d.getFeature() + ".");
        }
        if (!"PASSED".equals(match.get().getStatus())) {
            throw new VeloriaException(ResponseCode.CONFLICT,
                    "The test still reports " + match.get().getStatus() + " in run " + runNumber
                    + ". The defect stays open.");
        }

        d.setStatus(DefectStatus.VERIFIED.name());
        d.setRetestRun(runNumber);
        d.setVerifiedAt(Instant.now());
        d.setModified(Instant.now());
        log.info("AUDIT DEFECT_VERIFIED defect={} retestRun={} by={}", defectNumber, runNumber, by);
        return defectRepository.save(d);
    }

    /** Closes a verified defect. It leaves the open list and stays in the register. */
    @Transactional
    public DefectEntity close(String defectNumber, String by) throws VeloriaException {
        DefectEntity d = require(defectNumber);
        DefectStatus current = DefectStatus.of(d.getStatus()).orElseThrow();
        if (current != DefectStatus.VERIFIED) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Defect " + defectNumber + " is " + current + ". Only a VERIFIED defect may close — "
                    + "verify it against the run whose retest passed.");
        }
        if (d.getRetestRun() == null) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "Defect " + defectNumber + " has no retest run recorded.");
        }
        d.setStatus(DefectStatus.CLOSED.name());
        d.setClosedAt(Instant.now());
        d.setClosedBy(by);
        d.setModified(Instant.now());
        log.info("AUDIT DEFECT_CLOSED defect={} retestRun={} by={}", defectNumber, d.getRetestRun(), by);
        return defectRepository.save(d);
    }

    /** Attaches investigation detail — evidence, cause, proposed fix. */
    @Transactional
    public DefectEntity annotate(String defectNumber, DefectAnnotation a, String by) throws VeloriaException {
        DefectEntity d = require(defectNumber);
        if (a.title() != null)              d.setTitle(a.title());
        if (a.severity() != null)           d.setSeverity(DefectSeverity.of(a.severity())
                                                .orElseThrow(() -> bad("severity", a.severity())).name());
        if (a.priority() != null)           d.setPriority(a.priority());
        if (a.route() != null)              d.setRoute(a.route());
        if (a.uiElementId() != null)        d.setUiElementId(a.uiElementId());
        if (a.apiEndpoint() != null)        d.setApiEndpoint(a.apiEndpoint());
        if (a.entityName() != null)         d.setEntityName(a.entityName());
        if (a.description() != null)        d.setDescription(a.description());
        if (a.expectedBehavior() != null)   d.setExpectedBehavior(a.expectedBehavior());
        if (a.preconditions() != null)      d.setPreconditions(a.preconditions());
        if (a.reproductionSteps() != null)  d.setReproductionSteps(a.reproductionSteps());
        if (a.evidenceCurl() != null)       d.setEvidenceCurl(redact(a.evidenceCurl()));
        if (a.evidenceRequest() != null)    d.setEvidenceRequest(redact(a.evidenceRequest()));
        if (a.evidenceResponse() != null)   d.setEvidenceResponse(redact(a.evidenceResponse()));
        if (a.evidenceDbBefore() != null)   d.setEvidenceDbBefore(a.evidenceDbBefore());
        if (a.evidenceDbAfter() != null)    d.setEvidenceDbAfter(a.evidenceDbAfter());
        if (a.screenshotPath() != null)     d.setScreenshotPath(a.screenshotPath());
        if (a.rootCause() != null)          d.setRootCause(a.rootCause());
        if (a.proposedResolution() != null) d.setProposedResolution(a.proposedResolution());
        if (a.actualFix() != null)          d.setActualFix(a.actualFix());
        if (a.changedFiles() != null)       d.setChangedFiles(a.changedFiles());
        if (a.regressionTest() != null)     d.setRegressionTest(a.regressionTest());
        d.setModified(Instant.now());
        log.info("AUDIT DEFECT_ANNOTATED defect={} by={}", defectNumber, by);
        return defectRepository.save(d);
    }

    public record DefectAnnotation(
            String title, String severity, String priority, String route, String uiElementId,
            String apiEndpoint, String entityName, String description, String expectedBehavior,
            String preconditions, String reproductionSteps, String evidenceCurl, String evidenceRequest,
            String evidenceResponse, String evidenceDbBefore, String evidenceDbAfter,
            String screenshotPath, String rootCause, String proposedResolution,
            String actualFix, String changedFiles, String regressionTest) {}

    // ── helpers ──────────────────────────────────────────────────────────────

    private DefectEntity require(String defectNumber) throws VeloriaException {
        return defectRepository.findByDefectNumber(defectNumber).orElseThrow(
                () -> new VeloriaException(ResponseCode.NOT_FOUND, "Defect " + defectNumber + " was not found"));
    }

    private VeloriaException bad(String field, String value) {
        return new VeloriaException(ResponseCode.BAD_REQUEST, "Unknown " + field + " '" + value + "'");
    }

    private String nextDefectNumber() {
        return "DEF-" + String.format("%04d", defectRepository.maxDefectSequence() + 1);
    }

    /**
     * Identity for "the same failure".
     *
     * <p>Over the suite, class and test name — not the failure message, which
     * carries run-specific values like ids and timestamps and would make every
     * occurrence look like a new defect.
     */
    static String fingerprint(TestResultEntity r) {
        String material = String.join("|", String.valueOf(r.getSuite()),
                String.valueOf(r.getClassName()), String.valueOf(r.getTestName()));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 48);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String title(TestResultEntity r) {
        String name = r.getTestName() == null ? "unnamed test" : r.getTestName();
        String where = r.getModule() == null ? r.getSuite() : r.getModule();
        String t = "[" + where + "] " + name;
        return t.length() <= 380 ? t : t.substring(0, 380);
    }

    /** A filtering hint only — never a reason to delay recording the failure. */
    private String subcategory(TestResultEntity r) {
        String all = ((r.getClassName() == null ? "" : r.getClassName()) + " "
                + (r.getTestName() == null ? "" : r.getTestName()) + " "
                + (r.getFailureMessage() == null ? "" : r.getFailureMessage())).toLowerCase();
        if (all.contains("permission") || all.contains("unauthor") || all.contains("403") || all.contains("401")) return "SECURITY";
        if (all.contains("valid") || all.contains("required") || all.contains("mandatory")) return "VALIDATION";
        if (all.contains("gst") || all.contains("tax") || all.contains("paise") || all.contains("amount")) return "CALCULATION";
        if ("PLAYWRIGHT_CLIENT".equals(r.getSuite()) || "PLAYWRIGHT_ADMIN".equals(r.getSuite())) return "UI";
        if ("CUCUMBER".equals(r.getSuite())) return "API";
        return "CRUD";
    }

    /**
     * Severity from what the failure touches.
     *
     * <p>Deliberately coarse and conservative. It is a starting classification a
     * person can correct, not a judgement — and it never downgrades anything
     * touching the ledger.
     */
    private DefectSeverity severity(TestResultEntity r) {
        String all = ((r.getClassName() == null ? "" : r.getClassName()) + " "
                + (r.getTestName() == null ? "" : r.getTestName())).toLowerCase();
        if (all.contains("journal") || all.contains("checksum") || all.contains("ledger")
                || all.contains("accounting") || all.contains("balance")) return DefectSeverity.CRITICAL;
        if (all.contains("payment") || all.contains("refund") || all.contains("gst")
                || all.contains("tax") || all.contains("auth") || all.contains("permission")
                || all.contains("inventory")) return DefectSeverity.HIGH;
        return DefectSeverity.MEDIUM;
    }

    private static String append(String existing, String line) {
        return existing == null || existing.isBlank() ? line : existing + "\n" + line;
    }

    // Bearer tokens, API keys and passwords must not be stored in a defect record
    // that people will read and export.
    private static final List<Pattern> SECRETS = List.of(
            Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{12,}"),
            Pattern.compile("(?i)(\"?(password|secret|token|api[_-]?key|authorization)\"?\\s*[:=]\\s*\"?)([^\"',\\s]{4,})"),
            Pattern.compile("(?i)(AKIA[0-9A-Z]{16})"),
            Pattern.compile("(?i)(rzp_(live|test)_[A-Za-z0-9]+)")
    );

    /** Redacts credential-shaped text before it reaches a stored evidence field. */
    static String redact(String raw) {
        if (raw == null) return null;
        String out = raw;
        out = SECRETS.get(0).matcher(out).replaceAll("$1<REDACTED>");
        out = SECRETS.get(1).matcher(out).replaceAll("$1<REDACTED>");
        out = SECRETS.get(2).matcher(out).replaceAll("<REDACTED_AWS_KEY>");
        out = SECRETS.get(3).matcher(out).replaceAll("<REDACTED_GATEWAY_KEY>");
        return out;
    }
}
