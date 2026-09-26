package com.app.master.service.accounting;

import com.app.master.service.core.entity.CustomerOrderEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.repository.admin.CustomerOrderRepository;
import com.app.master.service.service.admin.AccountingPostingService;
import com.app.master.service.service.admin.SalesOrderService;
import com.app.master.service.support.AccountingResidue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sale posting and backfill against real PostgreSQL.
 *
 * <p>Two defects are under test. A cancelled order could be booked as revenue,
 * because neither {@code backfill()} nor {@code postSale()} looked at the
 * status. And a sale could be posted more than once: the unique index
 * {@code ux_journal_source} only constrains {@code POSTED} rows, so once an
 * entry was reversed the source looked unposted and the next backfill wrote it
 * again. This database still carries the evidence — four SALE journals for every
 * one of the seven historical orders, twenty-one of them since reversed.
 *
 * <p>Real PostgreSQL, because the guarantees are PostgreSQL's: the partial
 * unique index, and {@code SELECT … FOR UPDATE} serialising concurrent posters.
 *
 * <p>Not {@code @Transactional}: worker threads must see each other's commits,
 * and {@code postSale} opens its own transaction anyway.
 */
@SpringBootTest(properties = {
        "AWS_ACCESS_KEY=test-placeholder-not-a-credential",
        "AWS_SECRET_KEY=test-placeholder-not-a-credential",
        // One shared pool size across every integration test, on purpose.
        //
        // Spring caches a context per distinct property set, and each context
        // brings its own connection pool. Five different sizes meant five pools
        // — 160 connections against a server that allows 100, so a suite run
        // died with "too many clients" while each test passed alone. Identical
        // properties mean the contexts are shared, and 40 is enough for the
        // largest concurrency test now that placing an order also books its sale.
        "spring.datasource.hikari.maximum-pool-size=40"
})
@WithMockUser(authorities = "ADMIN_GST")
class AccountingBackfillPostgresTest {

    @Autowired private AccountingPostingService accounting;
    @Autowired private SalesOrderService salesOrderService;
    @Autowired private CustomerOrderRepository orderRepo;
    @Autowired private JdbcTemplate jdbc;

    private String buyerUuid;
    private final List<String> orderCodes = new ArrayList<>();

    // ── fixture ──────────────────────────────────────────────────────────────

    @BeforeEach
    void seedBuyer() {
        buyerUuid = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (uuid, first_name, last_name, email, phone, active, archive, created)
                VALUES (?::uuid, 'Automation', 'Buyer', ?, '9000000000', true, false, now())
                """, buyerUuid, "acct-" + buyerUuid.substring(0, 8) + "@automation.veloria.test");
    }

    /**
     * An order with GST amounts that make a balanced sale, in the given status.
     *
     * Dated today so it lands in the current, open accounting period.
     */
    private CustomerOrderEntity seedOrder(String status) {
        String code = "VO-ACC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("""
                INSERT INTO customer_order (uuid, order_code, customer_id, customer_name, customer_email,
                                            delivery_location, currency, total_value, taxable_value,
                                            cgst_amount, sgst_amount, igst_amount, total_tax_amount,
                                            status, order_placed_at, active, archive)
                VALUES (gen_random_uuid(), ?, ?, 'Automation Buyer', ?, '12 MG Road, Bengaluru', 'INR',
                        118000, 100000, 9000, 9000, 0, 18000, ?, timezone('UTC', now()), true, false)
                """, code, buyerUuid, "acct-" + buyerUuid.substring(0, 8) + "@automation.veloria.test", status);
        orderCodes.add(code);
        return orderRepo.findByOrderCodeAndArchiveFalse(code).orElseThrow();
    }

    @AfterEach
    void cleanUp() {
        for (String code : orderCodes) {
            Long id = jdbc.query("SELECT id FROM customer_order WHERE order_code = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, code);
            if (id == null) continue;
            // Reversals first: they reference the sale they reverse.
            jdbc.update("""
                    DELETE FROM journal_entry_line WHERE journal_entry_id IN (
                        SELECT r.id FROM journal_entry r
                         WHERE r.reverses_journal_id IN (
                            SELECT je.id FROM journal_entry je WHERE je.source_type='SALE' AND je.source_id=?))
                    """, id);
            jdbc.update("""
                    DELETE FROM journal_entry WHERE reverses_journal_id IN (
                        SELECT je.id FROM journal_entry je WHERE je.source_type='SALE' AND je.source_id=?)
                    """, id);
            jdbc.update("""
                    DELETE FROM journal_entry_line WHERE journal_entry_id IN (
                        SELECT id FROM journal_entry WHERE source_type='SALE' AND source_id=?)
                    """, id);
            jdbc.update("DELETE FROM journal_entry WHERE source_type='SALE' AND source_id=?", id);
            jdbc.update("DELETE FROM customer_order_item WHERE customer_order_id = ?", id);
            jdbc.update("DELETE FROM customer_order WHERE id = ?", id);
        }
        orderCodes.clear();
        jdbc.update("DELETE FROM users WHERE uuid = ?::uuid", buyerUuid);

        // Fail here rather than let residue move the historical checksum.
        AccountingResidue.assertNone(jdbc);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private long saleJournals(Long orderId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM journal_entry WHERE source_type='SALE' AND source_id=?",
                Long.class, orderId);
    }

    private long postedSaleJournals(Long orderId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM journal_entry WHERE source_type='SALE' AND source_id=? AND status='POSTED'",
                Long.class, orderId);
    }

    private long reversalsOf(Long orderId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM journal_entry r
                 WHERE r.reverses_journal_id IN (
                    SELECT je.id FROM journal_entry je WHERE je.source_type='SALE' AND je.source_id=?)
                """, Long.class, orderId);
    }

    /** Every journal for this order, checked line by line against its header. */
    private void assertJournalsBalanced(Long orderId) {
        List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
                SELECT je.journal_number, je.total_debit_paise, je.total_credit_paise,
                       COALESCE(SUM(l.debit_paise), 0)  AS line_debits,
                       COALESCE(SUM(l.credit_paise), 0) AS line_credits,
                       count(l.id) AS line_count
                  FROM journal_entry je
                  LEFT JOIN journal_entry_line l ON l.journal_entry_id = je.id
                 WHERE je.source_type='SALE' AND je.source_id = ?
                    OR je.reverses_journal_id IN (
                        SELECT x.id FROM journal_entry x WHERE x.source_type='SALE' AND x.source_id = ?)
                 GROUP BY je.id, je.journal_number, je.total_debit_paise, je.total_credit_paise
                """, orderId, orderId);

        assertFalse(rows.isEmpty(), "expected at least one journal to check");
        for (var r : rows) {
            String jv = String.valueOf(r.get("journal_number"));
            long lineDebits = ((Number) r.get("line_debits")).longValue();
            long lineCredits = ((Number) r.get("line_credits")).longValue();
            long headerDebits = ((Number) r.get("total_debit_paise")).longValue();
            long lineCount = ((Number) r.get("line_count")).longValue();

            assertTrue(lineCount > 0, jv + " has a header but no lines");
            assertEquals(lineDebits, lineCredits, jv + " is not balanced");
            assertEquals(headerDebits, lineDebits, jv + " header disagrees with its lines");
        }
    }

    private void runAsAdmin(ThrowingRunnable work) throws Exception {
        var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "automation-admin", "n/a",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ADMIN_GST"))));
        org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);
        try { work.run(); } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }

    // ── Test 1: a valid sale posts once ──────────────────────────────────────

    @Test
    @DisplayName("1 — an eligible order produces exactly one balanced SALE journal")
    void eligibleOrderPostsOnce() throws Exception {
        CustomerOrderEntity order = seedOrder("ORDER_PLACED");

        assertNotNull(accounting.postSale(order), "an eligible order should be posted");

        assertEquals(1, saleJournals(order.getId()));
        assertEquals(1, postedSaleJournals(order.getId()));
        assertJournalsBalanced(order.getId());

        // Dr AR 118000 / Cr Sales 100000 / Cr CGST 9000 / Cr SGST 9000
        assertEquals(118000L, jdbc.queryForObject("""
                SELECT SUM(l.debit_paise) FROM journal_entry_line l
                  JOIN journal_entry je ON je.id = l.journal_entry_id
                 WHERE je.source_type='SALE' AND je.source_id=? AND l.account_code='1100'
                """, Long.class, order.getId()), "receivable is the invoice total");
    }

    // ── Test 2: repeated backfill is idempotent ──────────────────────────────

    @Test
    @DisplayName("2 — posting the same order repeatedly still leaves one SALE journal")
    void repeatedPostingIsIdempotent() throws Exception {
        CustomerOrderEntity order = seedOrder("ORDER_PLACED");

        accounting.postSale(order);
        for (int i = 0; i < 5; i++) {
            assertNull(accounting.postSale(order), "a repeat must write nothing");
        }
        assertEquals(1, saleJournals(order.getId()));
    }

    @Test
    @DisplayName("2b — running the real backfill three times posts the order once")
    void repeatedBackfillIsIdempotent() throws Exception {
        CustomerOrderEntity order = seedOrder("ORDER_PLACED");

        accounting.backfill();
        assertEquals(1, saleJournals(order.getId()), "first run posts it");
        accounting.backfill();
        accounting.backfill();
        assertEquals(1, saleJournals(order.getId()), "further runs must not duplicate it");
        assertJournalsBalanced(order.getId());
    }

    @Test
    @DisplayName("2c — a reversed sale is not resurrected by the next backfill")
    void backfillDoesNotResurrectAReversedSale() throws Exception {
        // This is how the historical database came to hold four SALE journals
        // per order: reversing one freed the source from the POSTED-only index.
        CustomerOrderEntity order = seedOrder("ORDER_PLACED");
        accounting.postSale(order);
        assertEquals(1, saleJournals(order.getId()));

        runAsAdmin(() -> salesOrderService.cancelOrder(order.getOrderCode(), "changed their mind"));
        assertEquals(0, postedSaleJournals(order.getId()), "the sale should now be reversed");

        accounting.backfill();
        accounting.backfill();

        assertEquals(1, saleJournals(order.getId()), "backfill must not post it again");
        assertEquals(0, postedSaleJournals(order.getId()), "and must not make it POSTED again");
    }

    // ── Test 3: concurrency ──────────────────────────────────────────────────

    @Test
    @DisplayName("3 — 20 concurrent posting attempts produce exactly one SALE journal")
    void concurrentPostingProducesOneJournal() throws Exception {
        final int attempts = 20;
        CustomerOrderEntity order = seedOrder("ORDER_PLACED");

        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(attempts);
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    try {
                        startLine.await();
                        runAsAdmin(() -> accounting.postSale(order));
                    } catch (Exception e) {
                        failures.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startLine.countDown();
            assertTrue(finished.await(120, TimeUnit.SECONDS), "all attempts should finish");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, saleJournals(order.getId()),
                "one order, one sale — got " + saleJournals(order.getId()));
        assertEquals(1, postedSaleJournals(order.getId()));
        assertJournalsBalanced(order.getId());
        assertTrue(failures.isEmpty(),
                "the per-order lock should serialise these rather than collide: " + failures);
    }

    @Test
    @DisplayName("4b — 20 concurrent attempts on a cancelled order produce none")
    void concurrentPostingOfCancelledOrderProducesNothing() throws Exception {
        final int attempts = 20;
        CustomerOrderEntity order = seedOrder("CANCELLED");

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(attempts);
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    try {
                        startLine.await();
                        runAsAdmin(() -> accounting.postSale(order));
                    } catch (Exception ignored) {
                        // a failure is acceptable; a journal is not
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startLine.countDown();
            assertTrue(finished.await(120, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, saleJournals(order.getId()), "a cancelled order is not revenue");
    }

    // ── Test 4: cancelled orders ─────────────────────────────────────────────

    @Test
    @DisplayName("4 — a cancelled order is never posted as revenue")
    void cancelledOrderIsNotPosted() throws Exception {
        CustomerOrderEntity order = seedOrder("CANCELLED");

        assertNull(accounting.postSale(order), "postSale must refuse it");
        accounting.backfill();
        accounting.backfill();

        assertEquals(0, saleJournals(order.getId()), "no SALE journal, however often backfill runs");
    }

    @Test
    @DisplayName("4c — a failed checkout is never posted as revenue either")
    void paymentFailedOrderIsNotPosted() throws Exception {
        CustomerOrderEntity order = seedOrder("PAYMENT_FAILED");

        assertNull(accounting.postSale(order));
        accounting.backfill();
        assertEquals(0, saleJournals(order.getId()));
    }

    @Test
    @DisplayName("4d — an order cancelled after the backfill read it is still refused")
    void statusIsRereadUnderTheLock() throws Exception {
        // A backfill loads every order, then posts them one at a time. This is
        // the stale copy that loop would be holding.
        CustomerOrderEntity staleCopy = seedOrder("ORDER_PLACED");

        runAsAdmin(() -> salesOrderService.cancelOrder(staleCopy.getOrderCode(), "cancelled mid-run"));
        assertEquals("ORDER_PLACED", staleCopy.getStatus(), "the in-memory copy is deliberately stale");

        assertNull(accounting.postSale(staleCopy),
                "eligibility must be judged on the committed status, not the caller's copy");
        assertEquals(0, saleJournals(staleCopy.getId()));
    }

    // ── Tests 5-7: cancellation and reversal ─────────────────────────────────

    @Test
    @DisplayName("5 — cancelling an order with a posted sale reverses it, leaving both entries")
    void cancellationReversesAnExistingSale() throws Exception {
        CustomerOrderEntity order = seedOrder("ORDER_PLACED");
        accounting.postSale(order);
        assertEquals(1, postedSaleJournals(order.getId()));

        runAsAdmin(() -> salesOrderService.cancelOrder(order.getOrderCode(), "customer changed their mind"));

        assertEquals(1, saleJournals(order.getId()), "the original is kept, not deleted");
        assertEquals(0, postedSaleJournals(order.getId()), "and is now marked reversed");
        assertEquals(1, reversalsOf(order.getId()), "exactly one reversal entry");
        assertJournalsBalanced(order.getId());

        // Net effect on the books: nothing left standing for this order.
        assertEquals(0L, netForOrder(order.getId(), "1100"), "no receivable remains");
        assertEquals(0L, netForOrder(order.getId(), "4010"), "no revenue remains");
        assertEquals(0L, netForOrder(order.getId(), "2100"), "no output CGST remains");
    }

    @Test
    @DisplayName("5b — Case A: cancelling an order that was never posted reverses nothing")
    void cancellingAnUnpostedOrderDoesNothingToTheBooks() throws Exception {
        CustomerOrderEntity order = seedOrder("ORDER_PLACED");

        runAsAdmin(() -> salesOrderService.cancelOrder(order.getOrderCode(), "never posted"));

        assertEquals(0, saleJournals(order.getId()), "no sale");
        assertEquals(0, reversalsOf(order.getId()), "and nothing to reverse");
    }

    @Test
    @DisplayName("6 — Case C: repeating the cancellation does not post a second reversal")
    void repeatedCancellationDoesNotDuplicateTheReversal() throws Exception {
        CustomerOrderEntity order = seedOrder("ORDER_PLACED");
        accounting.postSale(order);

        runAsAdmin(() -> salesOrderService.cancelOrder(order.getOrderCode(), "first"));
        for (int i = 0; i < 4; i++) {
            runAsAdmin(() -> salesOrderService.cancelOrder(order.getOrderCode(), "again"));
        }
        // And directly, bypassing the order service entirely.
        assertNull(accounting.reverseSaleOfCancelledOrder(order, "direct repeat"));

        assertEquals(1, reversalsOf(order.getId()), "one cancellation, one reversal");
        assertJournalsBalanced(order.getId());
    }

    @Test
    @DisplayName("7 — concurrent cancellations produce one reversal and a balanced ledger")
    void concurrentCancellationsProduceOneReversal() throws Exception {
        final int attempts = 12;
        CustomerOrderEntity order = seedOrder("ORDER_PLACED");
        accounting.postSale(order);

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(attempts);
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    try {
                        startLine.await();
                        runAsAdmin(() -> salesOrderService.cancelOrder(order.getOrderCode(), "concurrent"));
                    } catch (Exception e) {
                        failures.add(e.getMessage());
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startLine.countDown();
            assertTrue(finished.await(120, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertTrue(failures.isEmpty(), "a repeated cancellation is not an error: " + failures);
        assertEquals(1, reversalsOf(order.getId()), "exactly one reversal");
        assertEquals(1, saleJournals(order.getId()));
        assertJournalsBalanced(order.getId());
        assertEquals(0L, netForOrder(order.getId(), "1100"));
    }

    // ── Test 8: balance ──────────────────────────────────────────────────────

    @Test
    @DisplayName("8 — every journal this phase writes balances, header and lines alike")
    void everyJournalBalances() throws Exception {
        CustomerOrderEntity order = seedOrder("ORDER_PLACED");
        accounting.postSale(order);
        runAsAdmin(() -> salesOrderService.cancelOrder(order.getOrderCode(), "to produce a reversal too"));

        assertJournalsBalanced(order.getId());

        // And the whole ledger still balances.
        assertEquals(0L, (long) jdbc.queryForObject(
                "SELECT COALESCE(SUM(debit_paise) - SUM(credit_paise), 0) FROM journal_entry_line", Long.class),
                "the ledger as a whole must balance");
    }

    // ── Test 9: period lock ──────────────────────────────────────────────────

    /**
     * Rewritten for the approved P0-11 closed-period policy (CP-1).
     *
     * <p>Until P0-11 this asserted that {@code postSale} threw and wrote nothing.
     * That was the right assertion for the policy of the time, which had no
     * deferral: the only date a sale had was the one the closed period rejected.
     * The approved policy is now that the order stands and its accounting moves to
     * the next open period, so the expectation changes with the decision.
     *
     * <p>What has <em>not</em> changed, and is still asserted here, is that
     * nothing lands in the closed period. The lock is not bypassed; the entry
     * moves, and it keeps the order's own date as its transaction date.
     */
    @Test
    @DisplayName("9 — a closed period is not posted into: the sale moves to the next open period")
    void closedPeriodIsNotBypassed() throws Exception {
        CustomerOrderEntity order = seedOrder("ORDER_PLACED");
        String period = jdbc.queryForObject(
                "SELECT to_char(order_placed_at, 'YYYY-MM') FROM customer_order WHERE id = ?",
                String.class, order.getId());

        Integer existing = jdbc.query("SELECT id FROM accounting_period WHERE period = ?",
                rs -> rs.next() ? rs.getInt(1) : null, period);
        String previousStatus = existing == null ? null : jdbc.queryForObject(
                "SELECT status FROM accounting_period WHERE period = ?", String.class, period);
        try {
            if (existing == null) {
                jdbc.update("""
                        INSERT INTO accounting_period (period, period_start, period_end, status)
                        VALUES (?, to_date(? || '-01', 'YYYY-MM-DD'),
                                   (to_date(? || '-01', 'YYYY-MM-DD') + interval '1 month - 1 day')::date,
                                'CLOSED')
                        """, period, period, period);
            } else {
                jdbc.update("UPDATE accounting_period SET status='CLOSED' WHERE period = ?", period);
            }

            accounting.postSale(order);

            assertEquals(1, saleJournals(order.getId()), "the order's sale is still recognised");

            Map<String, Object> posted = jdbc.queryForMap("""
                    SELECT period, journal_date, transaction_date FROM journal_entry
                     WHERE source_type = 'SALE' AND source_id = ?
                    """, order.getId());

            assertNotEquals(period, posted.get("period"),
                    "nothing may be posted into the closed period");
            assertTrue(((String) posted.get("period")).compareTo(period) > 0,
                    "and the period it moved to must be a later one, not an earlier one: "
                    + posted.get("period"));
            assertEquals("OPEN", jdbc.queryForObject(
                    "SELECT status FROM accounting_period WHERE period = ?",
                    String.class, posted.get("period")), "and that period must be open");

            assertEquals(period, jdbc.queryForObject("""
                    SELECT to_char(transaction_date, 'YYYY-MM') FROM journal_entry
                     WHERE source_type = 'SALE' AND source_id = ?
                    """, String.class, order.getId()),
                    "the original transaction date is kept, not overwritten");

            assertEquals("CLOSED", jdbc.queryForObject(
                    "SELECT status FROM accounting_period WHERE period = ?", String.class, period),
                    "and the closed period is still closed — the entry moved, not the lock");

            assertEquals(0, (long) jdbc.queryForObject("""
                    SELECT count(*) FROM journal_entry_line l
                      LEFT JOIN journal_entry je ON je.id = l.journal_entry_id
                     WHERE je.id IS NULL
                    """, Long.class), "and no orphaned lines");
        } finally {
            if (existing == null) {
                jdbc.update("DELETE FROM accounting_period WHERE period = ?", period);
            } else {
                jdbc.update("UPDATE accounting_period SET status = ? WHERE period = ?", previousStatus, period);
            }
        }
    }

    // ── Test 10: failure rollback ────────────────────────────────────────────

    @Test
    @DisplayName("10 — a posting that fails leaves no header, no lines, nothing partial")
    void failedPostingRollsBackCompletely() {
        CustomerOrderEntity order = seedOrder("ORDER_PLACED");

        long journalsBefore = jdbc.queryForObject("SELECT count(*) FROM journal_entry", Long.class);
        long linesBefore = jdbc.queryForObject("SELECT count(*) FROM journal_entry_line", Long.class);

        // An account that is not in the chart of accounts makes validation fail
        // partway through building the entry.
        order.setTaxableValue(100000L);
        order.setCgstAmount(-1L);   // a negative posting is refused by validate()

        assertThrows(VeloriaException.class, () -> accounting.postSale(order));

        assertEquals(journalsBefore, (long) jdbc.queryForObject(
                "SELECT count(*) FROM journal_entry", Long.class), "no journal header may survive");
        assertEquals(linesBefore, (long) jdbc.queryForObject(
                "SELECT count(*) FROM journal_entry_line", Long.class), "and no lines");
        assertEquals(0, saleJournals(order.getId()));
    }

    /** Net movement on one account for this order, across the sale and any reversal. */
    private long netForOrder(Long orderId, String accountCode) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.debit_paise) - SUM(l.credit_paise), 0)
                  FROM journal_entry_line l
                  JOIN journal_entry je ON je.id = l.journal_entry_id
                 WHERE l.account_code = ?
                   AND (   (je.source_type='SALE' AND je.source_id = ?)
                        OR je.reverses_journal_id IN (
                             SELECT x.id FROM journal_entry x
                              WHERE x.source_type='SALE' AND x.source_id = ?))
                """, Long.class, accountCode, orderId, orderId);
    }
}
