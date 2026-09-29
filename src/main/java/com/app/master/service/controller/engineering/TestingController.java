package com.app.master.service.controller.engineering;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.entity.DefectEntity;
import com.app.master.service.core.entity.TestInventoryEntity;
import com.app.master.service.core.entity.TestResultEntity;
import com.app.master.service.core.entity.TestRunEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.testing.DefectStatus;
import com.app.master.service.core.testing.TestType;
import com.app.master.service.repository.testing.DefectRepository;
import com.app.master.service.repository.testing.TestInventoryRepository;
import com.app.master.service.repository.testing.TestResultRepository;
import com.app.master.service.repository.testing.TestRunRepository;
import com.app.master.service.service.testing.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * The Testing Dashboard's API.
 *
 * <p>Same protections as the rest of Engineering: authenticated at the URL layer by
 * {@code GstSecurityConfig.ENGINEERING_PATHS} and authorized per method. Starting a
 * run needs {@code ENGINEERING_ANOMALY_RUN} — the same grant as running an anomaly
 * scan, because both spend real database resources; reading results needs only
 * {@code ENGINEERING_ANOMALY_VIEW}.
 *
 * <p>Nothing here accepts a command, a path, a filter or a shell fragment. The run
 * endpoint takes a test type, a module list and an environment, all validated
 * against closed vocabularies before anything executes.
 */
@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/engineering/testing")
@RequiredArgsConstructor
@Slf4j
public class TestingController extends AppController {

    private final TestRunnerService runner;
    private final DefectService defects;
    private final TestInventoryService inventory;
    private final TestCommandCatalog catalog;
    private final TestRunRepository runRepository;
    private final TestResultRepository resultRepository;
    private final DefectRepository defectRepository;
    private final TestInventoryRepository inventoryRepository;

    // ── runs ─────────────────────────────────────────────────────────────────

    /** What the dashboard offers in its selectors — served, never hardcoded in the UI. */
    @GetMapping("/options")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_VIEW)")
    public ResponseEntity<Response> options() {
        return success(ResponseCode.OK, "Testing options fetched", Map.of(
                "testTypes", Arrays.stream(TestType.values()).map(Enum::name).toList(),
                "defaultTestType", TestType.REGRESSION.name(),
                "modules", catalog.moduleKeys(),
                "environments", catalog.environments()));
    }

    /**
     * Starts a run. Returns 202 with the run number; the dashboard then polls.
     *
     * <p>202 rather than 200 because the work is not finished — a full regression
     * takes tens of minutes and holding the request open would tie a browser to it.
     */
    @PostMapping("/runs")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_RUN)")
    public ResponseEntity<Response> startRun(@RequestBody(required = false) RunRequest request)
            throws VeloriaException {
        RunRequest r = request == null ? new RunRequest(null, null, null) : request;
        TestRunEntity run = runner.requestRun(currentUser(),
                r.testType() == null ? TestType.REGRESSION.name() : r.testType(),
                r.modules(), r.environment());
        return success(ResponseCode.ACCEPTED, "Test run queued", summary(run));
    }

    public record RunRequest(String testType, List<String> modules, String environment) {}

    @GetMapping("/runs/{runNumber}")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_VIEW)")
    public ResponseEntity<Response> run(@PathVariable String runNumber) throws VeloriaException {
        TestRunEntity run = runRepository.findByRunNumber(runNumber).orElseThrow(
                () -> new VeloriaException(ResponseCode.NOT_FOUND, "Run " + runNumber + " was not found"));
        return success(ResponseCode.OK, "Run fetched", summary(run));
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_VIEW)")
    public ResponseEntity<Response> runs(@RequestParam(defaultValue = "20") int limit) {
        var page = runRepository.findAllByOrderByRequestedAtDesc(PageRequest.of(0, Math.min(limit, 100)));
        return success(ResponseCode.OK, "Run history fetched", page.map(this::summary).getContent());
    }

    /** The individual outcomes, failures first — what an engineer actually reads. */
    @GetMapping("/runs/{runNumber}/results")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_VIEW)")
    public ResponseEntity<Response> results(@PathVariable String runNumber,
                                            @RequestParam(required = false) String status)
            throws VeloriaException {
        TestRunEntity run = runRepository.findByRunNumber(runNumber).orElseThrow(
                () -> new VeloriaException(ResponseCode.NOT_FOUND, "Run " + runNumber + " was not found"));

        List<TestResultEntity> rows = (status == null || status.isBlank())
                ? resultRepository.findByRunIdOrderByStatusAscIdAsc(run.getId())
                : resultRepository.findByRunIdAndStatusOrderByIdAsc(run.getId(), status.toUpperCase(Locale.ROOT));

        return success(ResponseCode.OK, "Run results fetched", rows.stream().map(r -> Map.of(
                "suite", nz(r.getSuite()), "module", nz(r.getModule()),
                "className", nz(r.getClassName()), "testName", nz(r.getTestName()),
                "status", nz(r.getStatus()), "durationMs", r.getDurationMs() == null ? 0 : r.getDurationMs(),
                "failureMessage", nz(r.getFailureMessage()))).toList());
    }

    @PostMapping("/runs/{runNumber}/cancel")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_RUN)")
    public ResponseEntity<Response> cancel(@PathVariable String runNumber) throws VeloriaException {
        return success(ResponseCode.OK, "Run cancelled", summary(runner.cancel(runNumber, currentUser())));
    }

    // ── defects ──────────────────────────────────────────────────────────────

    /**
     * Raises the run's failures as suspected defects.
     *
     * <p>Separate from the run on purpose: a failing test is not automatically a
     * product defect, and creating defects silently during every run would fill the
     * register with test and environment problems.
     */
    @PostMapping("/runs/{runNumber}/raise-defects")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_RUN)")
    public ResponseEntity<Response> raiseDefects(@PathVariable String runNumber) throws VeloriaException {
        var outcome = defects.raiseFromRun(runNumber, currentUser());
        return success(ResponseCode.OK, "Defects raised from " + runNumber, Map.of(
                "raised", outcome.raised(), "reopened", outcome.reopened(),
                "refreshed", outcome.refreshed(), "failuresSeen", outcome.failuresSeen()));
    }

    @GetMapping("/defects")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_VIEW)")
    public ResponseEntity<Response> defectList(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String module,
            // The default list shows what still needs attention. A closed defect
            // leaves this list and stays in the register.
            @RequestParam(defaultValue = "true") boolean openOnly,
            @RequestParam(defaultValue = "100") int limit) {

        var page = defectRepository.search(blank(status), blank(severity), blank(module),
                openOnly, PageRequest.of(0, Math.min(limit, 300)));
        return success(ResponseCode.OK, "Defects fetched", page.map(this::defectRow).getContent());
    }

    @GetMapping("/defects/{defectNumber}")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_VIEW)")
    public ResponseEntity<Response> defect(@PathVariable String defectNumber) throws VeloriaException {
        DefectEntity d = defectRepository.findByDefectNumber(defectNumber).orElseThrow(
                () -> new VeloriaException(ResponseCode.NOT_FOUND, "Defect " + defectNumber + " was not found"));
        return success(ResponseCode.OK, "Defect fetched", defectDetail(d));
    }

    @PostMapping("/defects/{defectNumber}/transition")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_RUN)")
    public ResponseEntity<Response> transition(@PathVariable String defectNumber,
                                               @RequestBody TransitionRequest request) throws VeloriaException {
        return success(ResponseCode.OK, "Defect updated",
                defectDetail(defects.transition(defectNumber, request.status(), currentUser(), request.note())));
    }

    public record TransitionRequest(String status, String note) {}

    @PostMapping("/defects/{defectNumber}/verify")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_RUN)")
    public ResponseEntity<Response> verify(@PathVariable String defectNumber,
                                           @RequestBody VerifyRequest request) throws VeloriaException {
        return success(ResponseCode.OK, "Defect verified",
                defectDetail(defects.verify(defectNumber, request.runNumber(), currentUser())));
    }

    public record VerifyRequest(String runNumber) {}

    @PostMapping("/defects/{defectNumber}/close")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_RUN)")
    public ResponseEntity<Response> close(@PathVariable String defectNumber) throws VeloriaException {
        return success(ResponseCode.OK, "Defect closed",
                defectDetail(defects.close(defectNumber, currentUser())));
    }

    @PostMapping("/defects/{defectNumber}/annotate")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_RUN)")
    public ResponseEntity<Response> annotate(@PathVariable String defectNumber,
                                             @RequestBody DefectService.DefectAnnotation body)
            throws VeloriaException {
        return success(ResponseCode.OK, "Defect annotated",
                defectDetail(defects.annotate(defectNumber, body, currentUser())));
    }

    // ── inventory and coverage ───────────────────────────────────────────────

    @PostMapping("/inventory/discover")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_RUN)")
    public ResponseEntity<Response> discover(
            @RequestParam(defaultValue = "true") boolean linkTestIds) {
        var outcome = inventory.discover(linkTestIds);
        return success(ResponseCode.OK, "Inventory discovered", Map.of(
                "routes", outcome.routes(), "uiElements", outcome.uiElements(),
                "apiEndpoints", outcome.apiEndpoints(), "entities", outcome.entities(),
                "created", outcome.created(), "kept", outcome.kept(),
                "skipped", outcome.skipped()));
    }

    @GetMapping("/inventory")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_VIEW)")
    public ResponseEntity<Response> inventoryList(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "200") int limit) {

        var page = inventoryRepository.search(blank(kind), blank(module), blank(status),
                PageRequest.of(0, Math.min(limit, 500)));
        return success(ResponseCode.OK, "Inventory fetched", page.map(this::inventoryRow).getContent());
    }

    /** The traceability numbers, computed from the inventory rather than asserted. */
    @GetMapping("/coverage")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_VIEW)")
    public ResponseEntity<Response> coverage() {
        return success(ResponseCode.OK, "Coverage fetched", Map.of(
                "byKind", inventory.coverageByKind().stream().map(this::coverageRow).toList(),
                "byModule", inventory.coverageByModule().stream().map(this::coverageRow).toList()));
    }

    // ── dashboard summary ────────────────────────────────────────────────────

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_VIEW)")
    public ResponseEntity<Response> dashboard() {
        TestRunEntity last = runRepository.findFirstByOrderByRequestedAtDesc().orElse(null);

        Map<String, Long> defectsByStatus = new LinkedHashMap<>();
        for (Object[] row : defectRepository.countByStatus()) {
            defectsByStatus.put((String) row[0], ((Number) row[1]).longValue());
        }
        Map<String, Long> openBySeverity = new LinkedHashMap<>();
        for (Object[] row : defectRepository.countOpenBySeverity()) {
            openBySeverity.put((String) row[0], ((Number) row[1]).longValue());
        }

        long openDefects = defectsByStatus.entrySet().stream()
                .filter(e -> DefectStatus.of(e.getKey()).map(DefectStatus::isOpen).orElse(false))
                .mapToLong(Map.Entry::getValue).sum();

        return success(ResponseCode.OK, "Testing dashboard fetched", Map.of(
                "lastRun", last == null ? null : summary(last),
                "runInProgress", !runRepository.findActive().isEmpty(),
                "history", runRepository.findAllByOrderByRequestedAtDesc(PageRequest.of(0, 10))
                        .map(this::summary).getContent(),
                "openDefects", openDefects,
                "defectsByStatus", defectsByStatus,
                "openDefectsBySeverity", openBySeverity,
                "coverageByKind", inventory.coverageByKind().stream().map(this::coverageRow).toList()));
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private Map<String, Object> summary(TestRunEntity r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runNumber", r.getRunNumber());
        m.put("status", r.getStatus());
        m.put("testType", r.getTestType());
        m.put("modules", nz(r.getModules()));
        m.put("environment", r.getEnvironment());
        m.put("triggeredBy", r.getTriggeredBy());
        m.put("gitCommit", nz(r.getGitCommit()));
        m.put("commandLabel", nz(r.getCommandLabel()));
        m.put("requestedAt", r.getRequestedAt());
        m.put("startedAt", r.getStartedAt());
        m.put("completedAt", r.getCompletedAt());
        m.put("durationMillis", (r.getStartedAt() != null && r.getCompletedAt() != null)
                ? Duration.between(r.getStartedAt(), r.getCompletedAt()).toMillis() : null);
        m.put("total", r.getTotalCount());
        m.put("passed", r.getPassedCount());
        m.put("failed", r.getFailedCount());
        m.put("skipped", r.getSkippedCount());
        m.put("blocked", r.getBlockedCount());
        m.put("failureReason", nz(r.getFailureReason()));
        return m;
    }

    private Map<String, Object> defectRow(DefectEntity d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("defectNumber", d.getDefectNumber());
        m.put("title", d.getTitle());
        m.put("module", nz(d.getModule()));
        m.put("category", d.getCategory());
        m.put("subcategory", nz(d.getSubcategory()));
        m.put("severity", d.getSeverity());
        m.put("status", d.getStatus());
        m.put("firstSeenRun", nz(d.getFirstSeenRun()));
        m.put("lastSeenRun", nz(d.getLastSeenRun()));
        m.put("reopenCount", d.getReopenCount());
        m.put("openedAt", d.getOpenedAt());
        m.put("closedAt", d.getClosedAt());
        return m;
    }

    private Map<String, Object> defectDetail(DefectEntity d) {
        Map<String, Object> m = new LinkedHashMap<>(defectRow(d));
        m.put("feature", nz(d.getFeature()));
        m.put("route", nz(d.getRoute()));
        m.put("uiElementId", nz(d.getUiElementId()));
        m.put("apiEndpoint", nz(d.getApiEndpoint()));
        m.put("entityName", nz(d.getEntityName()));
        m.put("description", nz(d.getDescription()));
        m.put("expectedBehavior", nz(d.getExpectedBehavior()));
        m.put("actualBehavior", nz(d.getActualBehavior()));
        m.put("preconditions", nz(d.getPreconditions()));
        m.put("reproductionSteps", nz(d.getReproductionSteps()));
        m.put("evidenceCurl", nz(d.getEvidenceCurl()));
        m.put("evidenceRequest", nz(d.getEvidenceRequest()));
        m.put("evidenceResponse", nz(d.getEvidenceResponse()));
        m.put("evidenceDbBefore", nz(d.getEvidenceDbBefore()));
        m.put("evidenceDbAfter", nz(d.getEvidenceDbAfter()));
        m.put("screenshotPath", nz(d.getScreenshotPath()));
        m.put("rootCause", nz(d.getRootCause()));
        m.put("proposedResolution", nz(d.getProposedResolution()));
        m.put("actualFix", nz(d.getActualFix()));
        m.put("changedFiles", nz(d.getChangedFiles()));
        m.put("regressionTest", nz(d.getRegressionTest()));
        m.put("retestRun", nz(d.getRetestRun()));
        m.put("verifiedAt", d.getVerifiedAt());
        m.put("fixedAt", d.getFixedAt());
        m.put("allowedTransitions", DefectStatus.of(d.getStatus())
                .map(s -> s.allowedNext().stream().map(Enum::name).sorted().toList())
                .orElse(List.of()));
        return m;
    }

    private Map<String, Object> inventoryRow(TestInventoryEntity i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("itemId", i.getItemId());
        m.put("itemKind", i.getItemKind());
        m.put("module", i.getModule());
        m.put("elementType", nz(i.getElementType()));
        m.put("label", nz(i.getLabel()));
        m.put("route", nz(i.getRoute()));
        m.put("testStatus", i.getTestStatus());
        m.put("coveringTest", nz(i.getCoveringTest()));
        m.put("blockedReason", nz(i.getBlockedReason()));
        m.put("sourceFile", nz(i.getSourceFile()));
        return m;
    }

    private Map<String, Object> coverageRow(TestInventoryService.CoverageLine c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", c.key());
        m.put("total", c.total());
        m.put("passed", c.passed());
        m.put("failed", c.failed());
        m.put("blocked", c.blocked());
        m.put("untested", c.untested());
        m.put("coveragePercent", Math.round(c.coveragePercent() * 10) / 10.0);
        return m;
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "unknown" : auth.getName();
    }
}
