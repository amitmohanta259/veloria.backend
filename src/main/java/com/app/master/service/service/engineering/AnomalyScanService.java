package com.app.master.service.service.engineering;

import com.app.master.service.core.engineering.*;
import com.app.master.service.core.entity.EngineeringAnomalyFindingEntity;
import com.app.master.service.core.entity.EngineeringAnomalyScanEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.engineering.EngineeringAnomalyFindingRepository;
import com.app.master.service.repository.engineering.EngineeringAnomalyScanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executor;

/**
 * The one anomaly execution engine.
 *
 * <p>Manual, asynchronous, single-active, read-only and audited. There is no
 * scheduled entry point, no startup hook and no synchronous variant — the only way
 * in is {@link #requestScan}, called by the run endpoint after it has authorized
 * the caller.
 *
 * <h2>Single active scan</h2>
 * A PostgreSQL <em>session-scoped</em> advisory lock, held on the write connection
 * for the life of the scan. Chosen over a JVM lock because it holds across
 * application instances, and over a row lock because PostgreSQL releases it
 * automatically if the instance dies — so a crashed scan cannot wedge the feature
 * permanently.
 *
 * <h2>Read-only</h2>
 * Every rule reads through {@link AnomalyRules}, which holds the
 * {@code veloria_scan_ro} datasource. This service writes only to the three
 * {@code engineering_anomaly_*} tables, through the primary datasource. No business
 * table is written, ever, and the scanner has no privilege to do so even by mistake.
 */
@Service
@Slf4j
public class AnomalyScanService {

    /**
     * The advisory lock key for the anomaly scan.
     *
     * <p>A fixed, feature-scoped constant. It must never collide with another
     * advisory lock in the application, so it is declared here rather than derived
     * from a hash that could change.
     */
    static final long SCAN_LOCK_KEY = 8_151_701L;

    private static final DateTimeFormatter SCAN_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final EngineeringAnomalyScanRepository scanRepository;
    private final EngineeringAnomalyFindingRepository findingRepository;
    private final AnomalyRules rules;
    private final SchemaCipherService cipher;
    private final JdbcTemplate jdbc;
    private final Executor scanExecutor;

    /**
     * Written out rather than generated.
     *
     * <p>Lombok drops field annotations when it generates a constructor, so a
     * {@code @Qualifier} on the field never reaches the parameter Spring injects —
     * and with several {@code Executor} beans in the context (three from the
     * WebSocket broker, plus the shared task executor) the injection is ambiguous.
     * The qualifier has to sit on the parameter.
     */
    public AnomalyScanService(EngineeringAnomalyScanRepository scanRepository,
                              EngineeringAnomalyFindingRepository findingRepository,
                              AnomalyRules rules,
                              SchemaCipherService cipher,
                              JdbcTemplate jdbc,
                              @Qualifier("engineeringScanExecutor") Executor scanExecutor) {
        this.scanRepository = scanRepository;
        this.findingRepository = findingRepository;
        this.rules = rules;
        this.cipher = cipher;
        this.jdbc = jdbc;
        this.scanExecutor = scanExecutor;
    }

    // ── request ──────────────────────────────────────────────────────────────

    /**
     * Creates a scan and starts it in the background.
     *
     * <p>Returns as soon as the scan is recorded QUEUED: the HTTP request must not
     * stay open for a multi-minute database read. The caller gets the scan number
     * and polls.
     *
     * @throws VeloriaException CONFLICT when a scan is already active
     */
    public EngineeringAnomalyScanEntity requestScan(String triggeredBy, AnomalyRule.ScanScope scope)
            throws VeloriaException {

        // Read first so the refusal can name the scan that is running. This is for
        // the message only — the guarantee is the partial unique index below, because
        // a read-then-insert loses every race: three simultaneous requests all see
        // "nothing active", all insert, and two fail on a constraint instead of
        // getting a clean 409.
        List<EngineeringAnomalyScanEntity> active = scanRepository.findActive();
        if (!active.isEmpty()) {
            throw alreadyRunning(active.get(0).getScanNumber(), active.get(0).getStatus());
        }

        EngineeringAnomalyScanEntity scan = insertQueued(triggeredBy, scope);
        log.info("Engineering anomaly scan {} requested by {}", scan.getScanNumber(), triggeredBy);
        scanExecutor.execute(() -> execute(scan.getId()));
        return scan;
    }

    /**
     * Inserts the QUEUED row, letting the database arbitrate.
     *
     * <p>Two unique indexes can reject this insert and they mean different things.
     * {@code uq_eng_scan_single_active} means another scan is active — a 409, and the
     * caller must not retry. {@code uq_eng_scan_number} means two requests picked the
     * same sequence number in the same instant — harmless, and worth retrying with
     * the next one.
     */
    private EngineeringAnomalyScanEntity insertQueued(String triggeredBy, AnomalyRule.ScanScope scope)
            throws VeloriaException {

        String domains = scope.domains() == null || scope.domains().isEmpty() ? null
                : String.join(",", scope.domains().stream().map(Enum::name).toList());

        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                return scanRepository.saveAndFlush(EngineeringAnomalyScanEntity.builder()
                        .scanNumber(nextScanNumber())
                        .triggeredBy(triggeredBy)
                        .requestedAt(Instant.now())
                        .status(ScanStatus.QUEUED.name())
                        .scopeFrom(scope.from())
                        .scopeTo(scope.to())
                        .scopeTransactionId(scope.transactionId())
                        .scopeProductId(scope.productId())
                        .domains(domains)
                        .build());
            } catch (DataIntegrityViolationException e) {
                String cause = String.valueOf(e.getMostSpecificCause().getMessage());
                if (cause.contains("uq_eng_scan_single_active")) {
                    throw alreadyRunning(null, null);
                }
                if (!cause.contains("uq_eng_scan_number") || attempt == 5) {
                    throw e;
                }
                log.debug("Scan number collided on attempt {}; retrying", attempt);
            }
        }
        throw new VeloriaException(ResponseCode.CONFLICT,
                "Could not allocate a scan number; please try again.");
    }

    private VeloriaException alreadyRunning(String scanNumber, String status) {
        String which = scanNumber == null ? "A scan" : "Scan " + scanNumber;
        String state = status == null ? "already active" : "in state " + status;
        return new VeloriaException(ResponseCode.CONFLICT,
                "SCAN_ALREADY_RUNNING: " + which + " is " + state
                + ". Only one anomaly scan may run at a time.");
    }

    /** SCAN-YYYYMMDD-NNNN, sequential within the day. */
    private String nextScanNumber() {
        String prefix = "SCAN-" + ZonedDateTime.now(ZoneOffset.UTC).format(SCAN_DAY) + "-";
        int next = scanRepository.maxSequenceForPrefix(prefix) + 1;
        return prefix + String.format("%04d", next);
    }

    // ── execution ────────────────────────────────────────────────────────────

    /**
     * Runs one scan. Called only from the executor.
     *
     * <p>Not {@code @Transactional}: a scan is long, and holding one transaction
     * open for its duration would pin a connection and make every finding invisible
     * until the end. Each finding is persisted as it is produced, which is also what
     * lets the dashboard show progress that is real rather than invented.
     */
    void execute(Long scanId) {
        EngineeringAnomalyScanEntity scan = scanRepository.findById(scanId).orElse(null);
        if (scan == null) {
            log.error("Engineering anomaly scan {} vanished before execution", scanId);
            return;
        }

        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_lock(?)", Boolean.class, SCAN_LOCK_KEY);
        if (!Boolean.TRUE.equals(locked)) {
            // Another instance won. This scan never ran, so it is FAILED rather than
            // left QUEUED forever — a queued scan would block every later one.
            finish(scan, ScanStatus.FAILED, "SCAN_ALREADY_RUNNING: another instance holds the execution lock");
            return;
        }

        int rulesExecuted = 0;
        int rulesFailed = 0;
        List<String> failures = new ArrayList<>();
        List<EngineeringAnomalyFindingEntity> detected = new ArrayList<>();
        try {
            transition(scan, ScanStatus.RUNNING);
            scan.setStartedAt(Instant.now());
            scanRepository.save(scan);

            AnomalyRule.ScanScope scope = scopeOf(scan);
            scan.setTransactionsScanned(rules.countTransactionsInScope(scope));
            scanRepository.save(scan);

            for (AnomalyRule rule : rules.all()) {
                if (!scope.includes(rule.domain())) continue;
                try {
                    for (AnomalyRule.Candidate candidate : rule.evaluate(scope)) {
                        detected.add(record(scan, rule, candidate));
                    }
                    rulesExecuted++;
                } catch (Exception e) {
                    // One rule failing must not lose the others' findings (§54).
                    rulesFailed++;
                    failures.add(rule.ruleId() + ": " + e.getClass().getSimpleName());
                    log.warn("Engineering anomaly rule {} failed during scan {}",
                            rule.ruleId(), scan.getScanNumber(), e);
                }
            }

            scan.setRulesExecuted(rulesExecuted);
            scan.setRulesFailed(rulesFailed);
            refreshCounts(scan, detected);
            finish(scan, rulesFailed > 0 ? ScanStatus.PARTIAL : ScanStatus.COMPLETED,
                    failures.isEmpty() ? null : String.join("; ", failures));
        } catch (Exception e) {
            log.error("Engineering anomaly scan {} failed", scan.getScanNumber(), e);
            scan.setRulesExecuted(rulesExecuted);
            scan.setRulesFailed(rulesFailed);
            finish(scan, ScanStatus.FAILED, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            jdbc.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, SCAN_LOCK_KEY);
        }
    }

    private AnomalyRule.ScanScope scopeOf(EngineeringAnomalyScanEntity scan) {
        List<AnomalyDomain> domains = new ArrayList<>();
        if (scan.getDomains() != null && !scan.getDomains().isBlank()) {
            for (String d : scan.getDomains().split(",")) {
                AnomalyDomain.of(d).ifPresent(domains::add);
            }
        }
        // Read from the persisted scan, never from a request: this is what makes the
        // scope immutable while the scan runs.
        return new AnomalyRule.ScanScope(scan.getScopeFrom(), scan.getScopeTo(),
                scan.getScopeTransactionId(), scan.getScopeProductId(), domains);
    }

    /**
     * Persists one finding, encrypting its table and column.
     *
     * <p>Deduplication happens here: an anomaly that persists across scans keeps its
     * original {@code firstDetectedAt} and one row, with {@code occurrenceCount}
     * incremented. The unique index on the fingerprint is the real guarantee — this
     * lookup only decides which branch to take.
     */
    @Transactional
    EngineeringAnomalyFindingEntity record(EngineeringAnomalyScanEntity scan, AnomalyRule rule,
                                          AnomalyRule.Candidate candidate) {
        String fingerprint = fingerprint(rule, candidate);
        Instant now = Instant.now();

        Optional<EngineeringAnomalyFindingEntity> existing =
                findingRepository.findByAnomalyFingerprint(fingerprint);

        if (existing.isPresent()) {
            EngineeringAnomalyFindingEntity found = existing.get();
            found.setLastDetectedAt(now);
            found.setOccurrenceCount(found.getOccurrenceCount() + 1);
            found.setActualValue(candidate.actualValue());
            found.setModified(now);
            // Deliberately not reattributed to this scan: the finding belongs to the
            // scan that first saw it, so earlier scans stay readable as what they
            // actually reported (§11 — previous results are immutable).
            return findingRepository.save(found);
        }

        // One data key for both identifiers, so they share a version — but two
        // encrypt calls, so they never share a nonce.
        EngineeringKeyProvider.DataKey dataKey = cipher.currentDataKey();
        SchemaCipher table = cipher.encrypt(candidate.tableName(), dataKey);
        SchemaCipher column = cipher.encrypt(candidate.columnName(), dataKey);

        return findingRepository.save(EngineeringAnomalyFindingEntity.builder()
                .scanId(scan.getId())
                .anomalyFingerprint(fingerprint)
                .severity(rule.severity().name())
                .domain(rule.domain().name())
                .ruleId(rule.ruleId())
                .entityType(candidate.entityType())
                .entityId(candidate.entityId())
                .transactionId(candidate.transactionId())
                .orderId(candidate.orderId())
                .productId(candidate.productId())
                .actualValue(candidate.actualValue())
                .expectedValue(candidate.expectedValue())
                .description(candidate.detail() == null ? rule.description() : candidate.detail())
                .encryptedTableName(table.ciphertext())
                .encryptedColumnName(column.ciphertext())
                .encryptedDataKey(dataKey.encryptedDataKey())
                .keyProvider(table.keyProvider())
                .keyVersion(table.keyVersion())
                .algorithm(table.algorithm())
                .tableNonce(table.nonce())
                .tableAuthTag(table.authTag())
                .columnNonce(column.nonce())
                .columnAuthTag(column.authTag())
                .detectedAt(now)
                .firstDetectedAt(now)
                .lastDetectedAt(now)
                .occurrenceCount(1)
                // OPEN and only OPEN. The scanner repairs nothing, so it may never
                // declare anything resolved (§49).
                .status(AnomalyStatus.OPEN.name())
                .build());
    }

    /**
     * A stable identity for one logical anomaly.
     *
     * <p>Over non-sensitive identifiers only — rule, entity type, entity id,
     * transaction. Deliberately <b>not</b> over the table or column name: a
     * fingerprint derived from an encrypted value would let anyone holding the
     * fingerprint confirm a guess about the schema, which is the thing the
     * encryption exists to prevent.
     */
    static String fingerprint(AnomalyRule rule, AnomalyRule.Candidate candidate) {
        String material = String.join("|",
                rule.ruleId(),
                String.valueOf(candidate.entityType()),
                String.valueOf(candidate.entityId()),
                String.valueOf(candidate.transactionId()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 48);
        } catch (Exception e) {
            throw new IllegalStateException("Could not fingerprint an anomaly", e);
        }
    }

    /**
     * Counts what this scan detected.
     *
     * <p>Deliberately driven by the list the scan produced, not by
     * {@code WHERE scan_id = this scan}. A persistent anomaly keeps the scan that
     * first saw it, so a query by scan_id reported zero anomalies for every scan
     * after the first — the dashboard would have said "no anomalies" while three
     * were still open.
     */
    private void refreshCounts(EngineeringAnomalyScanEntity scan,
                               List<EngineeringAnomalyFindingEntity> findings) {
        scan.setAnomaliesFound(findings.size());
        scan.setCriticalCount((int) findings.stream().filter(f -> "CRITICAL".equals(f.getSeverity())).count());
        scan.setHighCount((int) findings.stream().filter(f -> "HIGH".equals(f.getSeverity())).count());
        scan.setMediumCount((int) findings.stream().filter(f -> "MEDIUM".equals(f.getSeverity())).count());
        scan.setLowCount((int) findings.stream().filter(f -> "LOW".equals(f.getSeverity())).count());
        scan.setInfoCount((int) findings.stream().filter(f -> "INFO".equals(f.getSeverity())).count());
    }

    private void finish(EngineeringAnomalyScanEntity scan, ScanStatus status, String failureReason) {
        transition(scan, status);
        scan.setCompletedAt(Instant.now());
        scan.setFailureReason(failureReason);
        scan.setModified(Instant.now());
        scanRepository.save(scan);
        log.info("Engineering anomaly scan {} finished {} — {} anomalies, {} rules, {} failed",
                scan.getScanNumber(), status, scan.getAnomaliesFound(),
                scan.getRulesExecuted(), scan.getRulesFailed());
    }

    /** Enforced, not documented: a finished scan can never reopen. */
    private void transition(EngineeringAnomalyScanEntity scan, ScanStatus next) {
        ScanStatus current = ScanStatus.of(scan.getStatus())
                .orElseThrow(() -> new IllegalStateException(
                        "Scan " + scan.getScanNumber() + " has an unknown status " + scan.getStatus()));
        if (current == next) return;
        if (!current.canMoveTo(next)) {
            throw new IllegalStateException("Scan " + scan.getScanNumber() + " cannot move from "
                    + current + " to " + next + "; allowed: " + current.allowedNext());
        }
        scan.setStatus(next.name());
    }
}
