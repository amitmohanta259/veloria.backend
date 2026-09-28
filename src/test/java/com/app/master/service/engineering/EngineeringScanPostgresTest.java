package com.app.master.service.engineering;

import com.app.master.service.core.engineering.ScanStatus;
import com.app.master.service.core.entity.EngineeringAnomalyScanEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.engineering.EngineeringAnomalyFindingRepository;
import com.app.master.service.repository.engineering.EngineeringAnomalyScanRepository;
import com.app.master.service.service.engineering.AnomalyRule;
import com.app.master.service.service.engineering.AnomalyScanService;
import com.app.master.service.service.engineering.EngineeringQueryService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The scan itself, against the real database.
 *
 * <p>What these pin: a scan is manual, it runs once however many callers ask, it
 * writes nothing outside the monitoring tables, and the historical ledger is
 * untouched by running it. The last one is checked by comparing the journal
 * checksum before and after — the same figure every other financial phase uses.
 */
// The same property set as every other Postgres integration test, deliberately.
// Spring caches one context per distinct property set, and each context brings its
// own connection pool — a set of its own here meant a second context and a second
// pool, and building it from cold took minutes on this filesystem. Matching the
// existing set means this class joins the shared context.
@SpringBootTest(properties = {
        "AWS_ACCESS_KEY=test-placeholder-not-a-credential",
        "AWS_SECRET_KEY=test-placeholder-not-a-credential",
        "spring.datasource.hikari.maximum-pool-size=40"
})
class EngineeringScanPostgresTest {

    @Autowired private AnomalyScanService scanService;
    @Autowired private EngineeringQueryService queryService;
    @Autowired private EngineeringAnomalyScanRepository scanRepository;
    @Autowired private EngineeringAnomalyFindingRepository findingRepository;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> ownedScans = new ArrayList<>();

    private static final String CHECKSUM_SQL = """
            SELECT md5(string_agg(id||':'||journal_number||':'||source_type||':'
                   ||COALESCE(source_id::text,'-')||':'||status||':'
                   ||total_debit_paise, '|' ORDER BY id)) FROM journal_entry
            """;

    @AfterEach
    void cleanUp() {
        // Only monitoring rows, and only the ones these tests created. A teardown
        // that could reach a business table is exactly what must not exist here.
        for (Long scanId : ownedScans) {
            jdbc.update("DELETE FROM engineering_anomaly_evidence WHERE anomaly_id IN "
                      + "(SELECT id FROM engineering_anomaly_finding WHERE scan_id = ?)", scanId);
            jdbc.update("DELETE FROM engineering_anomaly_finding WHERE scan_id = ?", scanId);
            jdbc.update("DELETE FROM engineering_anomaly_scan WHERE id = ?", scanId);
        }
        ownedScans.clear();
    }

    private AnomalyRule.ScanScope everything() {
        return new AnomalyRule.ScanScope(null, null, null, null, List.of());
    }

    /** Runs a scan to completion and returns it. */
    private EngineeringAnomalyScanEntity runToCompletion() throws Exception {
        EngineeringAnomalyScanEntity scan = scanService.requestScan("test-engineer", everything());
        ownedScans.add(scan.getId());
        await(scan.getId());
        return scanRepository.findById(scan.getId()).orElseThrow();
    }

    private void await(Long scanId) throws Exception {
        for (int i = 0; i < 120; i++) {
            ScanStatus status = ScanStatus.of(
                    scanRepository.findById(scanId).orElseThrow().getStatus()).orElseThrow();
            if (status.isTerminal()) return;
            Thread.sleep(250);
        }
        fail("Scan " + scanId + " did not finish");
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a manual scan runs the rules and reaches a terminal state")
    void manualScanCompletes() throws Exception {
        EngineeringAnomalyScanEntity scan = runToCompletion();

        assertTrue(List.of("COMPLETED", "PARTIAL").contains(scan.getStatus()),
                "expected a finished scan, got " + scan.getStatus()
                        + " — " + scan.getFailureReason());
        assertEquals(0, scan.getRulesFailed(),
                "every rule must run cleanly against the real schema: " + scan.getFailureReason());
        assertTrue(scan.getRulesExecuted() > 0, "no rules ran");
        assertNotNull(scan.getStartedAt());
        assertNotNull(scan.getCompletedAt());
        assertEquals("test-engineer", scan.getTriggeredBy(), "every run is attributable");
        assertTrue(scan.getScanNumber().startsWith("SCAN-"));
    }

    @Test
    @DisplayName("the scan writes nothing outside the monitoring tables")
    void businessDataIsUntouched() throws Exception {
        String checksumBefore = jdbc.queryForObject(CHECKSUM_SQL, String.class);
        Long ordersBefore = jdbc.queryForObject("SELECT COUNT(*) FROM customer_order", Long.class);
        Long journalsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry", Long.class);
        Long linesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry_line", Long.class);
        Long arBefore = jdbc.queryForObject(
                "SELECT COALESCE(SUM(debit_paise - credit_paise),0) FROM journal_entry_line WHERE account_code='1100'",
                Long.class);
        Long paymentsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class);
        Long refundsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund", Long.class);
        Long invoicesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM sales_invoice", Long.class);

        runToCompletion();

        assertEquals(checksumBefore, jdbc.queryForObject(CHECKSUM_SQL, String.class),
                "the historical journal checksum moved during a read-only scan");
        assertEquals(ordersBefore, jdbc.queryForObject("SELECT COUNT(*) FROM customer_order", Long.class));
        assertEquals(journalsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry", Long.class));
        assertEquals(linesBefore, jdbc.queryForObject("SELECT COUNT(*) FROM journal_entry_line", Long.class));
        assertEquals(arBefore, jdbc.queryForObject(
                "SELECT COALESCE(SUM(debit_paise - credit_paise),0) FROM journal_entry_line WHERE account_code='1100'",
                Long.class));
        assertEquals(paymentsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt", Long.class));
        assertEquals(refundsBefore, jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund", Long.class));
        assertEquals(invoicesBefore, jdbc.queryForObject("SELECT COUNT(*) FROM sales_invoice", Long.class));
    }

    @Test
    @DisplayName("the scanner's datasource cannot write, whatever statement it is given")
    void readOnlyRoleRefusesEveryMutation() {
        // The guarantee is the PostgreSQL role, not application discipline: these
        // statements are issued deliberately and must be refused by the server.
        String checksumBefore = jdbc.queryForObject(CHECKSUM_SQL, String.class);

        org.springframework.jdbc.core.JdbcTemplate readOnly = readOnlyJdbc();
        assertNotNull(readOnly.queryForObject("SELECT COUNT(*) FROM customer_order", Long.class),
                "SELECT must work, or the scanner cannot read anything");

        assertMutationRefused(readOnly, "UPDATE customer_order SET status = 'X'");
        assertMutationRefused(readOnly, "DELETE FROM journal_entry_line");
        assertMutationRefused(readOnly, "INSERT INTO customer_order (uuid, order_code, status) "
                + "VALUES (gen_random_uuid(), 'X', 'X')");
        assertMutationRefused(readOnly, "TRUNCATE payment_attempt");

        assertEquals(checksumBefore, jdbc.queryForObject(CHECKSUM_SQL, String.class),
                "a refused mutation still changed something");
    }

    private void assertMutationRefused(org.springframework.jdbc.core.JdbcTemplate readOnly, String sql) {
        Exception e = assertThrows(Exception.class, () -> readOnly.execute(sql),
                "the read-only role executed: " + sql);

        // Spring wraps the driver's error, so the privilege refusal is in the cause
        // chain rather than the top-level message.
        StringBuilder chain = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) chain.append(t.getMessage()).append(' ');
        String message = chain.toString().toLowerCase();

        assertTrue(message.contains("permission denied") || message.contains("read-only")
                        || message.contains("must be owner"),
                "expected a privilege refusal, got: " + chain);
    }

    @Autowired private org.springframework.context.ApplicationContext context;

    private org.springframework.jdbc.core.JdbcTemplate readOnlyJdbc() {
        return (org.springframework.jdbc.core.JdbcTemplate)
                context.getBean("engineeringReadOnlyJdbc");
    }

    // ── single active scan ───────────────────────────────────────────────────

    @Test
    @DisplayName("eight simultaneous requests produce exactly one scan")
    void concurrentRequestsProduceOneScan() throws Exception {
        final int callers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(callers);
        List<Long> accepted = Collections.synchronizedList(new ArrayList<>());
        List<String> refused = Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        EngineeringAnomalyScanEntity s = scanService.requestScan("race-" + Thread.currentThread().getId(), everything());
                        accepted.add(s.getId());
                    } catch (VeloriaException e) {
                        refused.add(e.getMessage());
                    } catch (Exception e) {
                        refused.add("UNEXPECTED: " + e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(120, TimeUnit.SECONDS), "every caller must finish");
        } finally {
            pool.shutdownNow();
        }

        accepted.forEach(ownedScans::add);

        assertEquals(1, accepted.size(),
                "exactly one scan may be created: accepted=" + accepted.size() + " refused=" + refused.size());
        assertEquals(callers - 1, refused.size());
        refused.forEach(r -> assertTrue(r.contains("SCAN_ALREADY_RUNNING"),
                "a refusal must say a scan is already running, not fail obscurely: " + r));

        await(accepted.get(0));
    }

    @Test
    @DisplayName("a finished scan cannot be reopened")
    void terminalScansAreFinal() {
        assertFalse(ScanStatus.COMPLETED.canMoveTo(ScanStatus.RUNNING));
        assertFalse(ScanStatus.PARTIAL.canMoveTo(ScanStatus.RUNNING));
        assertFalse(ScanStatus.FAILED.canMoveTo(ScanStatus.RUNNING));
        assertFalse(ScanStatus.FAILED.canMoveTo(ScanStatus.COMPLETED));
        assertTrue(ScanStatus.QUEUED.canMoveTo(ScanStatus.RUNNING));
        assertTrue(ScanStatus.RUNNING.canMoveTo(ScanStatus.COMPLETED));
        assertTrue(ScanStatus.RUNNING.canMoveTo(ScanStatus.PARTIAL));
        assertTrue(ScanStatus.RUNNING.canMoveTo(ScanStatus.FAILED));
        // QUEUED must not jump the queue: a scan that never started cannot have
        // completed, and allowing it would let a lost executor look like success.
        assertFalse(ScanStatus.QUEUED.canMoveTo(ScanStatus.COMPLETED));
    }

    // ── scope, findings and deduplication ────────────────────────────────────

    @Test
    @DisplayName("the scan uses the scope it was created with, not one supplied later")
    void scopeIsImmutable() throws Exception {
        AnomalyRule.ScanScope narrow = new AnomalyRule.ScanScope(
                null, null, "VO-DOES-NOT-EXIST", null, List.of());

        EngineeringAnomalyScanEntity scan = scanService.requestScan("test-engineer", narrow);
        ownedScans.add(scan.getId());
        await(scan.getId());

        EngineeringAnomalyScanEntity finished = scanRepository.findById(scan.getId()).orElseThrow();
        assertEquals("VO-DOES-NOT-EXIST", finished.getScopeTransactionId(),
                "the captured scope must survive the run");
        assertEquals(0, finished.getTransactionsScanned(),
                "a scope naming no real transaction must scan nothing");
        assertEquals(0, finished.getAnomaliesFound());
    }

    @Test
    @DisplayName("repeating a scan does not multiply a persistent anomaly")
    void repeatedScansDeduplicate() throws Exception {
        EngineeringAnomalyScanEntity first = runToCompletion();
        long findingsAfterFirst = findingRepository.count();
        int reported = first.getAnomaliesFound();

        EngineeringAnomalyScanEntity second = runToCompletion();

        assertEquals(findingsAfterFirst, findingRepository.count(),
                "a second scan must update the existing findings, not insert duplicates");
        assertEquals(reported, second.getAnomaliesFound(),
                "and it must still report what it detected — counting only new rows "
                + "reported zero anomalies while several were open");

        if (reported > 0) {
            var anomalies = queryService.anomalies(null, null, null, null, null, 10);
            var detail = queryService.anomaly(anomalies.get(0).anomalyId(), true);
            assertTrue(detail.occurrenceCount() >= 2, "the occurrence count must advance");
            assertTrue(detail.lastDetectedAt().isAfter(detail.firstDetectedAt())
                       || detail.lastDetectedAt().equals(detail.firstDetectedAt()),
                    "last detected must not go backwards");
        }
    }

    @Test
    @DisplayName("findings are stored with the schema encrypted, and decrypt only for an authorized reader")
    void findingsCarryEncryptedSchema() throws Exception {
        EngineeringAnomalyScanEntity scan = runToCompletion();
        Assumptions.assumeTrue(scan.getAnomaliesFound() > 0,
                "this database currently has no anomalies to inspect");

        var rows = queryService.anomalies(null, null, null, null, null, 1);
        String anomalyId = rows.get(0).anomalyId();

        var authorized = queryService.anomaly(anomalyId, true);
        assertTrue(authorized.schemaVisible());
        assertNotEquals("ENCRYPTED", authorized.tableName());
        assertNotEquals("DECRYPTION_UNAVAILABLE", authorized.tableName(),
                "the key must be recoverable for a finding just written");
        assertTrue(authorized.tableName().matches("[a-z_]+"), "a real table name");
        assertTrue(authorized.columnName().matches("[a-z_]+"), "a real column name");
        assertEquals("LOCAL", authorized.keyProvider());

        var unauthorized = queryService.anomaly(anomalyId, false);
        assertFalse(unauthorized.schemaVisible());
        assertEquals("ENCRYPTED", unauthorized.tableName());
        assertEquals("ENCRYPTED", unauthorized.columnName());

        // And nothing readable is stored on the row itself.
        String storedTable = jdbc.queryForObject(
                "SELECT encrypted_table_name FROM engineering_anomaly_finding WHERE uuid = ?::uuid",
                String.class, anomalyId);
        assertNotEquals(authorized.tableName(), storedTable,
                "the plaintext table name reached the database");
        assertFalse(storedTable.contains(authorized.tableName()));
    }

    @Test
    @DisplayName("the scanner only ever creates OPEN findings")
    void scannerNeverResolvesAnything() throws Exception {
        runToCompletion();
        Long notOpen = jdbc.queryForObject(
                "SELECT COUNT(*) FROM engineering_anomaly_finding WHERE status <> 'OPEN'", Long.class);
        assertEquals(0L, notOpen,
                "a scanner that repairs nothing may never declare anything resolved");
    }
}
