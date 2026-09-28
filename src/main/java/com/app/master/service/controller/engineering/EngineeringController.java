package com.app.master.service.controller.engineering;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.engineering.AnomalyDomain;
import com.app.master.service.core.engineering.AnomalySeverity;
import com.app.master.service.core.engineering.AnomalyStatus;
import com.app.master.service.core.entity.EngineeringAnomalyScanEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.engineering.EngineeringDtos;
import com.app.master.service.core.security.GstPermission;
import com.app.master.service.service.engineering.AnomalyRule;
import com.app.master.service.service.engineering.AnomalyScanService;
import com.app.master.service.service.engineering.EngineeringQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The Engineering API.
 *
 * <p>Every endpoint requires authentication at the URL layer (see
 * {@code GstSecurityConfig.ENGINEERING_PATHS}) <em>and</em> carries its own
 * {@code @PreAuthorize}. The duplication is deliberate: the filter chain means a
 * new endpoint cannot be reached anonymously even if someone forgets the
 * annotation, and the annotation is what distinguishes running a scan from reading
 * one. No {@code permitAll()} anywhere.
 *
 * <p>There is no decrypt endpoint. Schema identifiers are decrypted only inside
 * {@link #anomaly}, for a caller who holds {@code ENGINEERING_VIEW}, and the
 * decryption is audited.
 */
@RestController
@CrossOrigin(origins = {"*"})
@RequestMapping("/api/master/engineering")
@RequiredArgsConstructor
@Slf4j
public class EngineeringController extends AppController {

    private final AnomalyScanService scanService;
    private final EngineeringQueryService queryService;

    // ── scans ────────────────────────────────────────────────────────────────

    /**
     * Starts a scan. The only way one ever runs.
     *
     * <p>Returns 202 with the scan id as soon as the scan is queued and the
     * execution lock is held; the scan itself runs on the Engineering executor. A
     * second caller while one is active gets 409 {@code SCAN_ALREADY_RUNNING} and no
     * new scan is created.
     */
    @PostMapping("/anomaly-scans/run")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_RUN)")
    public ResponseEntity<Response> runScan(@RequestBody(required = false) ScanRequest request)
            throws VeloriaException {

        ScanRequest safe = request == null ? new ScanRequest(null, null, null, null, null) : request;
        AnomalyRule.ScanScope scope = safe.toScope();
        String user = currentUser();

        EngineeringAnomalyScanEntity scan = scanService.requestScan(user, scope);
        // The audit record for the run (§12, §53): who, when, what. No secret.
        log.info("AUDIT RUN_ANOMALY_SCAN scanId={} triggeredBy={} requestedAt={} scope=[{} .. {}] txn={} product={}",
                scan.getScanNumber(), user, scan.getRequestedAt(),
                scope.from(), scope.to(), scope.transactionId(), scope.productId());

        return success(ResponseCode.ACCEPTED, "Anomaly scan queued",
                new EngineeringDtos.ScanAccepted(scan.getScanNumber(), scan.getStatus()));
    }

    @GetMapping("/anomaly-scans/{scanId}")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_VIEW)")
    public ResponseEntity<Response> scan(@PathVariable String scanId) throws VeloriaException {
        return success(ResponseCode.OK, "Scan fetched", queryService.scanByNumber(scanId));
    }

    @GetMapping("/anomaly-scans")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_VIEW)")
    public ResponseEntity<Response> scans(@RequestParam(defaultValue = "20") int limit) {
        return success(ResponseCode.OK, "Scan history fetched", queryService.history(limit));
    }

    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_VIEW)")
    public ResponseEntity<Response> dashboard() {
        return success(ResponseCode.OK, "Engineering dashboard fetched", queryService.dashboard());
    }

    // ── anomalies ────────────────────────────────────────────────────────────

    /**
     * The anomaly list.
     *
     * <p>Every filter is a typed, validated parameter. There is no table or column
     * filter: those values are encrypted, and accepting one as a query parameter is
     * forbidden (§33, §36). A caller cannot submit SQL, a table name, a column name
     * or a WHERE clause through any parameter here.
     */
    @GetMapping("/anomalies")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_VIEW)")
    public ResponseEntity<Response> anomalies(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String transactionId,
            @RequestParam(required = false) String scanId,
            @RequestParam(defaultValue = "100") int limit) throws VeloriaException {

        validateEnum(severity, AnomalySeverity::of, "severity");
        validateEnum(domain, AnomalyDomain::of, "domain");
        validateEnum(status, AnomalyStatus::of, "status");

        return success(ResponseCode.OK, "Anomalies fetched",
                queryService.anomalies(severity, domain, status, transactionId, scanId, limit));
    }

    /**
     * One anomaly, with the table and column decrypted server-side.
     *
     * <p>{@code ENGINEERING_ANOMALY_VIEW} gets the finding; the schema identifiers
     * additionally require {@code ENGINEERING_VIEW}. A reader without it sees
     * {@code ENCRYPTED} and no ciphertext — the API never hands out the encrypted
     * form for a client to work on.
     */
    @GetMapping("/anomalies/{anomalyId}")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_VIEW)")
    public ResponseEntity<Response> anomaly(@PathVariable String anomalyId) throws VeloriaException {
        boolean schemaAuthorized = hasAuthority(GstPermission.ENGINEERING_VIEW);
        EngineeringDtos.AnomalyDetail detail = queryService.anomaly(anomalyId, schemaAuthorized);

        if (schemaAuthorized) {
            // The decryption audit (§17 of P0-15, §12 here). The identifiers
            // themselves are not logged — logging the decrypted schema name would
            // defeat encrypting it at rest.
            log.info("AUDIT ENGINEERING_SCHEMA_DECRYPT adminUser={} anomalyId={} scanId={} operation=DECRYPT_SCHEMA",
                    currentUser(), detail.anomalyId(), detail.scanId());
        }
        return success(ResponseCode.OK, "Anomaly fetched", detail);
    }

    @GetMapping("/transaction/{transactionId}/anomalies")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_ANOMALY_VIEW)")
    public ResponseEntity<Response> transactionAnomalies(@PathVariable String transactionId) {
        return success(ResponseCode.OK, "Transaction anomalies fetched",
                queryService.anomalies(null, null, null, transactionId, null, 200));
    }

    // ── other Engineering pages ──────────────────────────────────────────────

    @GetMapping("/fluctuation")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_VIEW)")
    public ResponseEntity<Response> fluctuation() {
        return success(ResponseCode.OK, "Database fluctuation fetched", queryService.fluctuation());
    }

    @GetMapping("/transaction-360/{transactionId}")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_TRANSACTION_AUDIT)")
    public ResponseEntity<Response> transaction360(@PathVariable String transactionId) throws VeloriaException {
        return success(ResponseCode.OK, "Transaction 360 fetched",
                queryService.transaction360(transactionId));
    }

    @GetMapping("/compliance-gaps")
    @PreAuthorize("hasAuthority(T(com.app.master.service.core.security.GstPermission).ENGINEERING_COMPLIANCE_VIEW)")
    public ResponseEntity<Response> complianceGaps() {
        return success(ResponseCode.OK, "Compliance gaps fetched",
                queryService.anomalies(null, AnomalyDomain.COMPLIANCE.name(), null, null, null, 200));
    }

    // ── request shape ────────────────────────────────────────────────────────

    /**
     * The scan filters.
     *
     * <p>Typed fields only — dates, an order code, a product id, a list of domain
     * names. There is deliberately no free-text field that reaches SQL: the dates
     * become bound timestamp parameters, the identifiers become bound strings, and
     * the domains are parsed into an enum or rejected.
     */
    public record ScanRequest(String dateFrom, String dateTo, String transactionId,
                              String productId, List<String> domains) {

        AnomalyRule.ScanScope toScope() throws VeloriaException {
            Instant from = parseDate(dateFrom, "dateFrom", false);
            Instant to = parseDate(dateTo, "dateTo", true);
            if (from != null && to != null && to.isBefore(from)) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST, "dateTo is before dateFrom");
            }
            List<AnomalyDomain> parsed = new ArrayList<>();
            if (domains != null) {
                for (String d : domains) {
                    if (d == null || d.isBlank()) continue;
                    parsed.add(AnomalyDomain.of(d).orElseThrow(() -> new VeloriaException(
                            ResponseCode.BAD_REQUEST, "Unknown domain '" + d + "'")));
                }
            }
            return new AnomalyRule.ScanScope(from, to,
                    blankToNull(transactionId), blankToNull(productId), parsed);
        }

        private static Instant parseDate(String raw, String field, boolean endOfDay) throws VeloriaException {
            if (raw == null || raw.isBlank()) return null;
            try {
                LocalDate date = LocalDate.parse(raw.trim());
                return endOfDay
                        ? date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusMillis(1)
                        : date.atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (Exception e) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        field + " must be an ISO date such as 2026-09-28");
            }
        }

        private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private <T> void validateEnum(String raw, java.util.function.Function<String, java.util.Optional<T>> parser,
                                  String field) throws VeloriaException {
        if (raw == null || raw.isBlank()) return;
        if (parser.apply(raw).isEmpty()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "Unknown " + field + " '" + raw + "'");
        }
    }

    private static boolean hasAuthority(String authority) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    /** Who is acting. Never anonymous on these endpoints — the chain requires a token. */
    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "unknown" : auth.getName();
    }
}
