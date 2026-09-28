package com.app.master.service.accounting;

import com.app.master.service.core.entity.PaymentAttemptEntity;
import com.app.master.service.core.entity.PaymentRefundEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.payment.CancellationActor;
import com.app.master.service.core.payment.CancellationReason;
import com.app.master.service.core.request.client.PlaceOrderRequest;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.payment.PaymentAttemptRepository;
import com.app.master.service.repository.payment.PaymentRefundRepository;
import com.app.master.service.service.admin.AccountingPostingService;
import com.app.master.service.service.admin.GstConfigurationService;
import com.app.master.service.service.admin.ReturnProcessingService;
import com.app.master.service.service.admin.impl.SalesOrderServiceImpl;
import com.app.master.service.service.client.ClientBagService;
import com.app.master.service.service.client.ClientOrderService;
import com.app.master.service.service.payment.CodFeeTaxResolver;
import com.app.master.service.service.payment.PaymentService;
import com.app.master.service.service.payment.RazorpayGateway;
import com.app.master.service.service.payment.RefundService;
import com.app.master.service.support.AccountingResidue;
import com.app.master.service.support.CodTestConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The P0-11 financial decisions, end to end, against real PostgreSQL.
 *
 * <p>Four approved decisions are under test, and each has a failure mode that only
 * a real database shows:
 *
 * <ul>
 *   <li><b>The COD fee</b> is income of its own, and it must raise a receivable.
 *       P0-10 left the customer paying ₹50 that nothing stood behind, which is
 *       visible only by adding up the ledger.</li>
 *   <li><b>The closed period</b> must not be posted into, and the entry must still
 *       exist. Both halves need the real period table and the real trigger.</li>
 *   <li><b>The refund</b> must be impossible to issue twice. The guarantee is a
 *       partial unique index plus the gateway's own idempotency, so it is only
 *       meaningful under genuine concurrency.</li>
 *   <li><b>Gateway fee facts</b> must be persisted without being posted.</li>
 * </ul>
 *
 * <p>Not {@code @Transactional}: the sale is posted after the order's transaction
 * commits, and worker threads must see each other's commits.
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
        //
        // The COD service code is deliberately NOT set here: leaving it unset is
        // the production state, and a test that needs a resolved rate sets it on
        // the bean for the duration of that test instead. Adding a property would
        // fork a third context and a third pool for no gain.
        "spring.datasource.hikari.maximum-pool-size=40"
})
class FinancialDecisionsPostgresTest {

    /** A service code that exists only for these tests. */
    private static final String TEST_SAC = "999799";

    @MockBean private RazorpayGateway razorpay;

    @Autowired private ClientOrderService orderService;
    @Autowired private ClientBagService bagService;
    @Autowired private PaymentService paymentService;
    @Autowired private SalesOrderServiceImpl salesOrderService;
    @Autowired private ReturnProcessingService returnProcessing;
    @Autowired private RefundService refundService;
    @Autowired private AccountingPostingService accounting;
    @Autowired private CodFeeTaxResolver codFeeTaxResolver;
    @Autowired private PaymentAttemptRepository attemptRepo;
    @Autowired private PaymentRefundRepository refundRepo;
    @Autowired private JdbcTemplate jdbc;

    private String buyerUuid;
    private String buyerEmail;
    private String buyerToken;
    private UUID productUuid;
    private Long productId;
    private final List<String> orderCodes = new ArrayList<>();
    private final List<String> closedPeriods = new ArrayList<>();
    private boolean taxRuleSeeded;
    private boolean codConfigured;

    // ── fixture ──────────────────────────────────────────────────────────────

    @BeforeEach
    void seed() {
        buyerUuid = UUID.randomUUID().toString();
        String email = "fin-" + buyerUuid.substring(0, 8) + "@automation.veloria.test";
        buyerEmail = email;
        jdbc.update("""
                INSERT INTO users (uuid, first_name, last_name, email, phone, active, archive, created)
                VALUES (?::uuid, 'Automation', 'Buyer', ?, '9000000000', true, false, now())
                """, buyerUuid, email);
        buyerToken = "fin-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO client_session (token, user_id, name, email, phone, expiry, access_expiry)
                VALUES (?, ?, 'Automation Buyer', ?, '9000000000',
                        timezone('UTC', now()) + interval '1 hour',
                        timezone('UTC', now()) + interval '1 hour')
                """, buyerToken, buyerUuid, email);
        jdbc.update("""
                INSERT INTO user_address (uuid, user_id, receiver_name, phone, address, city,
                                          state_code, pincode, is_default, active, archive, created_at)
                VALUES (gen_random_uuid(), ?, 'Automation Buyer', '9000000000',
                        '12 MG Road', 'Bengaluru', '29', '560001', true, true, false, now())
                """, buyerUuid);

        productUuid = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_product (uuid, name, sku_id, price, selling_price, price_currency,
                                               initial_stock, hsn_code, archive, active, draft, created)
                VALUES (?::uuid, 'Automation Financial Product', ?, 1000000, 1000000, 'INR', 40, '6211',
                        false, true, false, now())
                """, productUuid.toString(), "AUTO-FIN-" + productUuid.toString().substring(0, 8));
        productId = jdbc.queryForObject(
                "SELECT id FROM inventory_product WHERE uuid = ?::uuid", Long.class, productUuid.toString());
    }

    @AfterEach
    void cleanUp() {
        // Reopen periods FIRST, and unconditionally.
        //
        // A period this test closed is shared state: leave it closed and every
        // later test in the class silently gets deferred postings instead of the
        // ones it asked for. Doing it after the row cleanup meant one bad DELETE
        // took the period restore down with it, and the next four tests failed for
        // a reason that had nothing to do with them.
        for (String period : closedPeriods) {
            jdbc.update("UPDATE accounting_period SET status='OPEN', closed_at=NULL, closed_by=NULL "
                      + "WHERE period = ?", period);
        }
        closedPeriods.clear();
        // Restore the shipped configuration: the two tax decisions unset, which is
        // the production state and what the next test must start from.
        // One restore, shared with every other COD-using test. Doing it by hand here
        // once missed COD_FEE_REFUNDABLE, which then leaked into the next test and
        // made a non-refundable-charge assertion fail for no visible reason.
        if (codConfigured) {
            CodTestConfig.restore(jdbc);
            codConfigured = false;
        }

        // Every statement below is attempted, and a failure in one does not stop
        // the rest.
        //
        // Fail-fast is wrong for a teardown. One bad DELETE here — a mistyped
        // column name, as it happens — aborted the whole loop and left 29 orders
        // and 39 journals behind, which moved the historical journal checksum. The
        // residue then had to be identified and removed by hand. A cleanup that
        // gives up half way is worse than one that reports what it could not do.
        List<String> failures = new ArrayList<>();
        for (String code : orderCodes) {
            Long id = jdbc.query("SELECT id FROM customer_order WHERE order_code = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, code);
            if (id == null) continue;
            // In dependency order. Reversals first, because they reference the
            // entry they reverse; then everything this order caused — its sale, its
            // COD fee, its collections, its refunds. Journals before the payment
            // and refund rows they are keyed on, or the subqueries match nothing
            // and the journals are left orphaned.
            attempt(failures, """
                    DELETE FROM journal_entry_line WHERE journal_entry_id IN (
                        SELECT r.id FROM journal_entry r WHERE r.reverses_journal_id IN (
                            SELECT je.id FROM journal_entry je
                             WHERE je.source_type IN ('SALE','COD_FEE') AND je.source_id=?))
                    """, id);
            attempt(failures, """
                    DELETE FROM journal_entry WHERE reverses_journal_id IN (
                        SELECT je.id FROM journal_entry je
                         WHERE je.source_type IN ('SALE','COD_FEE') AND je.source_id=?)
                    """, id);
            attempt(failures, """
                    DELETE FROM journal_entry_line WHERE journal_entry_id IN (
                        SELECT id FROM journal_entry
                         WHERE (source_type IN ('SALE','COD_FEE') AND source_id=?)
                            OR (source_type='PAYMENT_COLLECTION' AND source_id IN
                                (SELECT pa.id FROM payment_attempt pa WHERE pa.customer_order_id=?))
                            OR (source_type='REFUND' AND source_id IN
                                (SELECT pr.id FROM payment_refund pr WHERE pr.customer_order_id=?)))
                    """, id, id, id);
            attempt(failures, """
                    DELETE FROM journal_entry
                     WHERE (source_type IN ('SALE','COD_FEE') AND source_id=?)
                        OR (source_type='PAYMENT_COLLECTION' AND source_id IN
                            (SELECT pa.id FROM payment_attempt pa WHERE pa.customer_order_id=?))
                        OR (source_type='REFUND' AND source_id IN
                            (SELECT pr.id FROM payment_refund pr WHERE pr.customer_order_id=?))
                    """, id, id, id);
            attempt(failures, "DELETE FROM payment_refund WHERE customer_order_id = ?", id);
            attempt(failures, "DELETE FROM payment_attempt WHERE customer_order_id = ?", id);
            attempt(failures, """
                    DELETE FROM order_return_item WHERE return_request_id IN
                        (SELECT id FROM order_return_request WHERE order_code = ?)
                    """, code);
            attempt(failures, "DELETE FROM order_return_request WHERE order_code = ?", code);
            attempt(failures, """
                    DELETE FROM gst_credit_note_item WHERE credit_note_id IN
                        (SELECT id FROM gst_credit_note WHERE original_order_code = ?)
                    """, code);
            attempt(failures, "DELETE FROM gst_credit_note WHERE original_order_code = ?", code);
            attempt(failures, "DELETE FROM gst_movement_ledger WHERE CAST(source_id AS TEXT) = ?", code);
            attempt(failures, """
                    DELETE FROM sales_invoice_item WHERE sales_invoice_id IN
                        (SELECT id FROM sales_invoice WHERE order_code = ?)
                    """, code);
            attempt(failures, "DELETE FROM sales_invoice WHERE order_code = ?", code);
            attempt(failures, "DELETE FROM gst_output_tax WHERE order_code = ?", code);
            attempt(failures, "DELETE FROM gst_accounting_exception WHERE CAST(source_id AS TEXT) = ?", code);
            attempt(failures, "DELETE FROM sales_order WHERE order_code = ?", code);
            attempt(failures, "DELETE FROM customer_order_item WHERE customer_order_id = ?", id);
            attempt(failures, "DELETE FROM customer_order WHERE id = ?", id);
        }
        orderCodes.clear();

        if (taxRuleSeeded) {
            attempt(failures, "DELETE FROM gst_tax_rules WHERE hsn_code = ?", TEST_SAC);
            taxRuleSeeded = false;
        }

        attempt(failures, "DELETE FROM inventory_product WHERE id = ?", productId);
        attempt(failures, "DELETE FROM customer_bag WHERE user_id = ?", buyerUuid);
        attempt(failures, "DELETE FROM user_address WHERE user_id = ?", buyerUuid);
        attempt(failures, "DELETE FROM client_session WHERE user_id = ?", buyerUuid);
        attempt(failures, "DELETE FROM users WHERE uuid = ?::uuid", buyerUuid);

        // Say so loudly. Residue that nobody is told about is how a checksum moves
        // without anyone knowing which run moved it.
        assertTrue(failures.isEmpty(), "teardown left rows behind: " + failures);

        // And nothing survived that refers to an order which no longer exists.
        // Per-statement error handling only catches the statements this test
        // remembered to write; this checks the outcome instead.
        AccountingResidue.assertNone(jdbc);
    }

    /** Runs one teardown statement, recording rather than throwing on failure. */
    private void attempt(List<String> failures, String sql, Object... args) {
        try {
            jdbc.update(sql, args);
        } catch (RuntimeException e) {
            failures.add(sql.strip().split("\n")[0] + " → " + e.getMessage());
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Configures the COD charge's tax the way an approved decision would: a
     * service code, and a rate in the tax master against it.
     *
     * <p>Seeded here and removed afterwards. It proves the mechanism works; it is
     * not a claim about what the rate should be, which is a tax decision the
     * application does not hold.
     */
    private void configureCodFeeTax(int cgstBp, int sgstBp, int igstBp) {
        // The shared helper owns this: it seeds the rule as a SAC-typed rule and sets
        // the basis, and having one place to do that is why the code type and the
        // basis cannot drift apart between test classes.
        //
        // EXCLUSIVE here, not the production INCLUSIVE, because the assertions in this
        // class were written against a ₹50 taxable value plus ₹9 tax. The inclusive
        // arithmetic has its own dedicated coverage in CodServiceTaxPostgresTest.
        CodTestConfig.configure(jdbc, 5000L, cgstBp, sgstBp, igstBp,
                GstConfigurationService.TAX_EXCLUSIVE);
        taxRuleSeeded = true;
        codConfigured = true;
    }

    /**
     * Sets one configuration value.
     *
     * <p>Updates in place rather than deleting and reinserting, so the row keeps the
     * declared {@code value_type} and description the migration shipped. An earlier
     * delete-and-insert version silently rewrote every type to STRING.
     */
    private void setCodConfig(String key, String value) {
        int updated = jdbc.update("UPDATE gst_configuration SET config_value = ? "
                               + "WHERE config_key = ? AND organization_id = 1", value, key);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO gst_configuration (organization_id, config_key, config_value, value_type,
                                                   effective_from, active, created_at)
                    VALUES (1, ?, ?, 'STRING', DATE '2017-07-01', true, now())
                    """, key, value);
        }
    }

    private String placeOrder() throws Exception {
        return placeOrder(1);
    }

    private String placeOrder(int quantity) throws Exception {
        PlaceOrderRequest r = new PlaceOrderRequest();
        r.setDeliveryLocation("12 MG Road, Bengaluru, Karnataka 560001");
        r.setCurrency("INR");
        PlaceOrderRequest.OrderItemRequest item = new PlaceOrderRequest.OrderItemRequest();
        item.setProductUuid(productUuid);
        item.setQuantity(quantity);
        r.setItems(List.of(item));

        String code = orderService.placeOrder(buyerToken, r).getOrderCode();
        orderCodes.add(code);
        waitForSale(orderIdOf(code));
        return code;
    }

    private void waitForSale(Long orderId) throws Exception {
        for (int i = 0; i < 50 && journals("SALE", orderId) == 0; i++) Thread.sleep(100);
    }

    private Long orderIdOf(String code) {
        return jdbc.queryForObject("SELECT id FROM customer_order WHERE order_code = ?", Long.class, code);
    }

    private long journals(String sourceType, Long sourceId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM journal_entry WHERE source_type=? AND source_id=?",
                Long.class, sourceType, sourceId);
    }

    private long collectionJournals(Long orderId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM journal_entry
                 WHERE source_type='PAYMENT_COLLECTION'
                   AND source_id IN (SELECT id FROM payment_attempt WHERE customer_order_id=?)
                """, Long.class, orderId);
    }

    private long refundJournals(Long orderId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM journal_entry
                 WHERE source_type='REFUND'
                   AND source_id IN (SELECT id FROM payment_refund WHERE customer_order_id=?)
                """, Long.class, orderId);
    }

    /** The receivable still standing for this order, read from the ledger. */
    private long outstandingAr(Long orderId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.debit_paise) - SUM(l.credit_paise), 0)
                  FROM journal_entry_line l
                  JOIN journal_entry je ON je.id = l.journal_entry_id
                 WHERE l.account_code = '1100'
                   AND ( (je.source_type IN ('SALE','COD_FEE') AND je.source_id=?)
                      OR (je.source_type='PAYMENT_COLLECTION' AND je.source_id IN
                          (SELECT id FROM payment_attempt WHERE customer_order_id=?))
                      OR je.reverses_journal_id IN
                          (SELECT x.id FROM journal_entry x
                            WHERE x.source_type IN ('SALE','COD_FEE') AND x.source_id=?))
                """, Long.class, orderId, orderId, orderId);
    }

    /** The movement on one account caused by one of this order's journals. */
    private long accountMovement(Long orderId, String sourceType, String accountCode) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.debit_paise) - SUM(l.credit_paise), 0)
                  FROM journal_entry_line l JOIN journal_entry je ON je.id = l.journal_entry_id
                 WHERE l.account_code = ?
                   AND je.source_type = ?
                   AND (je.source_id = ?
                     OR je.source_id IN (SELECT id FROM payment_attempt WHERE customer_order_id=?)
                     OR je.source_id IN (SELECT id FROM payment_refund WHERE customer_order_id=?))
                """, Long.class, accountCode, sourceType, orderId, orderId, orderId);
    }

    /** The whole-ledger balance on one account — debits less credits. */
    private long accountBalance(String accountCode) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(debit_paise) - SUM(credit_paise), 0)
                  FROM journal_entry_line WHERE account_code = ?
                """, Long.class, accountCode);
    }

    private Map<String, Object> orderRow(String code) {
        return jdbc.queryForMap("SELECT * FROM customer_order WHERE order_code = ?", code);
    }

    private long invoiceTotal(Long orderId) {
        return jdbc.queryForObject("""
                -- The COD gross is taxable + tax, which is right under both tax bases.
                -- Adding the FEE and the tax would double-count an inclusive charge,
                -- overstating a ₹50 charge as ₹57.63 — the defect P0-13 fixed.
                SELECT COALESCE(total_value,0) + COALESCE(shipping_value,0)
                     + COALESCE(NULLIF(cod_fee_taxable_paise,0), cod_fee_paise, 0)
                     + COALESCE(cod_fee_tax_paise,0)
                  FROM customer_order WHERE id = ?
                """, Long.class, orderId);
    }

    private void stubGateway() throws Exception {
        when(razorpay.createOrder(anyLong(), anyString(), anyString()))
                .thenAnswer(i -> "order_" + UUID.randomUUID().toString().substring(0, 12));
        when(razorpay.createRefund(anyString(), anyLong(), anyString()))
                .thenAnswer(i -> "rfnd_" + UUID.randomUUID().toString().substring(0, 12));
    }

    private PaymentAttemptEntity captureOnline(String orderCode, String mode, Long feePaise)
            throws Exception {
        String key = "k-" + UUID.randomUUID();
        paymentService.initiate(buyerToken, orderCode, mode, key);
        PaymentAttemptEntity a = attemptRepo.findByIdempotencyKey(key).orElseThrow();
        String paymentId = "pay_" + UUID.randomUUID().toString().substring(0, 12);
        when(razorpay.fetchPayment(anyString())).thenReturn(new RazorpayGateway.PaymentView(
                paymentId, a.getRazorpayOrderId(), "captured", a.getAmountPaise(),
                "INR", "card", null, null, null, feePaise));
        paymentService.applyGatewayTruth(a, paymentId, "test");
        return attemptRepo.findById(a.getId()).orElseThrow();
    }

    /** Closes the period the order was placed in, and remembers to reopen it. */
    private String closePeriodOf(Long orderId) {
        String period = jdbc.queryForObject(
                "SELECT to_char(order_placed_at, 'YYYY-MM') FROM customer_order WHERE id = ?",
                String.class, orderId);
        jdbc.update("UPDATE accounting_period SET status='CLOSED' WHERE period = ?", period);
        closedPeriods.add(period);
        return period;
    }

    /** Verifies the return of everything on an order — the warehouse step. */
    private Long verifyReturn(String orderCode) throws Exception {
        runAs(() -> returnProcessing.verify(orderCode, List.of(), "Automation verification",
                false, null, "automation-admin"), "ADMIN_GST", "CREATE_CREDIT_NOTE");
        return latestReturnId(orderCode);
    }

    /** Verifies a return of exactly {@code units}, leaving the rest returnable. */
    private Long verifyReturn(String orderCode, int units) throws Exception {
        Long orderItemId = jdbc.queryForObject("""
                SELECT i.id FROM customer_order_item i
                  JOIN customer_order o ON o.id = i.customer_order_id
                 WHERE o.order_code = ? AND i.archive = false ORDER BY i.id LIMIT 1
                """, Long.class, orderCode);
        runAs(() -> returnProcessing.verify(orderCode,
                        List.of(new ReturnProcessingService.ReturnLine(orderItemId, units, "PRODUCT_OK")),
                        "Automation partial verification", false, null, "automation-admin"),
                "ADMIN_GST", "CREATE_CREDIT_NOTE");
        return latestReturnId(orderCode);
    }

    private Long latestReturnId(String orderCode) {
        return jdbc.queryForObject(
                "SELECT id FROM order_return_request WHERE order_code = ? ORDER BY id DESC LIMIT 1",
                Long.class, orderCode);
    }

    // ── COD fee: ₹50, Other Income, taxable ──────────────────────────────────

    @Test
    @DisplayName("choosing COD adds the approved ₹50 charge and freezes the rate that priced it")
    void codFeeIsChargedAndItsTaxSnapshotted() throws Exception {
        configureCodFeeTax(900, 900, 1800);
        String code = placeOrder();

        paymentService.initiate(buyerToken, code, "COD", "cod-" + UUID.randomUUID());

        Map<String, Object> order = orderRow(code);
        assertEquals(5000L, ((Number) order.get("cod_fee_paise")).longValue(), "the approved ₹50");
        assertEquals(5000L, ((Number) order.get("cod_fee_taxable_paise")).longValue(),
                "and ₹50 is the value the tax was charged on");
        assertEquals(TEST_SAC, order.get("cod_fee_sac_code"), "priced by service code, not by a literal");
        assertEquals("RULE_APPLIED", order.get("cod_fee_tax_resolution"));

        // 18% of ₹50, which the master states as 9+9 intra-state or 18 inter-state.
        // Which of those applies is the GST engine's decision, made from the place
        // of supply — so this asserts the tax, and that the heads are consistent
        // with one supply type or the other. Pinning a specific head here would be
        // asserting a place-of-supply rule that belongs to the engine, not here.
        assertEquals(1800, ((Number) order.get("cod_fee_tax_rate_bp")).intValue(), "18%");
        assertEquals(900L, ((Number) order.get("cod_fee_tax_paise")).longValue(), "18% of ₹50");

        long cgst = ((Number) order.get("cod_fee_cgst_paise")).longValue();
        long sgst = ((Number) order.get("cod_fee_sgst_paise")).longValue();
        long igst = ((Number) order.get("cod_fee_igst_paise")).longValue();
        assertEquals(900L, cgst + sgst + igst, "the heads must add up to the tax");
        if (igst > 0) {
            assertEquals(900L, igst, "inter-state: all of it is IGST");
            assertEquals(0L, cgst + sgst, "and none of it is CGST or SGST");
        } else {
            assertEquals(450L, cgst, "intra-state: half is CGST");
            assertEquals(450L, sgst, "and half is SGST");
        }
    }

    @Test
    @DisplayName("the COD charge is credited to Other Income, never to Sales")
    void codFeeIsOtherIncomeNotSales() throws Exception {
        configureCodFeeTax(900, 900, 1800);
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        long salesFromTheSale = accountMovement(orderId, "SALE", "4010");

        paymentService.initiate(buyerToken, code, "COD", "cod-" + UUID.randomUUID());

        assertEquals(1, journals("COD_FEE", orderId), "the charge is its own accounting event");
        assertEquals(-5000L, accountMovement(orderId, "COD_FEE", "4090"),
                "₹50 credited to Other Income");
        assertEquals(0L, accountMovement(orderId, "COD_FEE", "4010"),
                "and nothing to Sales — the charge is not a sale of goods");
        assertEquals(salesFromTheSale, accountMovement(orderId, "SALE", "4010"),
                "the product revenue is untouched by the charge");

        // The tax lands on whichever output heads the GST engine chose, and only
        // on output-tax heads.
        long outputTax = accountMovement(orderId, "COD_FEE", "2100")
                + accountMovement(orderId, "COD_FEE", "2110")
                + accountMovement(orderId, "COD_FEE", "2120");
        assertEquals(-900L, outputTax, "18% of ₹50, credited to output tax");
        assertEquals(5900L, accountMovement(orderId, "COD_FEE", "1100"),
                "and the customer owes the charge plus its tax");
    }

    /** The gap P0-10 reported: ₹50 paid, nothing behind it. */
    @Test
    @DisplayName("the COD charge raises its own receivable, so the invoice and the ledger agree")
    void codFeeRaisesAReceivable() throws Exception {
        configureCodFeeTax(900, 900, 1800);
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        long saleOnly = outstandingAr(orderId);

        paymentService.initiate(buyerToken, code, "COD", "cod-" + UUID.randomUUID());

        assertEquals(saleOnly + 5900L, outstandingAr(orderId),
                "the receivable grows by the charge and its tax, not by nothing");
        assertEquals(invoiceTotal(orderId), outstandingAr(orderId),
                "invoice total = product + GST + transport + other + COD fee + its tax");
    }

    @Test
    @DisplayName("a COD order is collected in full at the door, leaving nothing outstanding")
    void codCollectionSettlesTheWholeInvoice() throws Exception {
        configureCodFeeTax(900, 900, 1800);
        String code = placeOrder();
        Long orderId = orderIdOf(code);

        paymentService.initiate(buyerToken, code, "COD", "cod-" + UUID.randomUUID());
        long payable = attemptRepo.findByCustomerOrderIdOrderBySequenceNoAscIdAsc(orderId)
                .get(0).getAmountPaise();
        assertEquals(invoiceTotal(orderId), payable, "the customer is asked for the whole invoice");

        runAs(() -> {
            salesOrderService.updateOrderStatus(code, "PACKED");
            salesOrderService.updateOrderStatus(code, "IN_TRANSIT");
            salesOrderService.updateOrderStatus(code, "OUT_FOR_DELIVERY");
            salesOrderService.updateOrderStatus(code, "DELIVERED");
        }, "ADMIN_GST");

        assertEquals(1, collectionJournals(orderId));
        assertEquals(0, outstandingAr(orderId),
                "settled exactly — no cap was needed and the receivable did not go negative");
        assertEquals(-payable, accountMovement(orderId, "PAYMENT_COLLECTION", "1100"),
                "and the whole amount was applied against the receivable");
    }

    @Test
    @DisplayName("the COD charge is recognised once, however many times COD is chosen")
    void codFeeIsRecognisedOnce() throws Exception {
        configureCodFeeTax(900, 900, 1800);
        String code = placeOrder();
        Long orderId = orderIdOf(code);

        paymentService.initiate(buyerToken, code, "COD", "cod-" + UUID.randomUUID());
        long arAfterFirst = outstandingAr(orderId);

        // A second COD initiation is refused as a duplicate payment, which is the
        // P0-7 behaviour; what matters here is that neither it nor the backfill
        // adds a second charge.
        try {
            paymentService.initiate(buyerToken, code, "COD", "cod-" + UUID.randomUUID());
        } catch (VeloriaException expected) {
            // an order may not have two live payments
        }
        accounting.backfill();
        accounting.backfill();

        assertEquals(1, journals("COD_FEE", orderId), "one charge, one journal");
        assertEquals(arAfterFirst, outstandingAr(orderId), "and the receivable did not move again");
    }

    @Test
    @DisplayName("with no service code configured the charge still stands but is not recognised")
    void unresolvedCodFeeTaxIsNotPosted() throws Exception {
        // Explicitly unconfigured. Since P0-13 the shipped configuration DOES carry a
        // SAC and a basis, so a test about the unconfigured state has to create it
        // rather than rely on the default.
        jdbc.update("UPDATE gst_configuration SET config_value = NULL "
                  + "WHERE config_key = 'COD_FEE_SAC' AND organization_id = 1");
        codConfigured = true;   // so the teardown restores the shipped values
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        long saleOnly = outstandingAr(orderId);

        // P0-14 changed this from "charge it untaxed and record why" to a refusal.
        // Charging a customer for a taxable service while declaring no tax on it is
        // a misdeclaration; being able to explain it afterwards does not make it
        // one the business may make. So COD is withdrawn until it is configured.
        VeloriaException refused = assertThrows(VeloriaException.class,
                () -> paymentService.initiate(buyerToken, code, "COD", "cod-" + UUID.randomUUID()));
        assertTrue(refused.getMessage().contains("tax configuration is incomplete"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains("service accounting code"),
                "the message must say which piece is missing: " + refused.getMessage());

        Map<String, Object> order = orderRow(code);
        assertEquals(0L, ((Number) order.get("cod_fee_paise")).longValue(),
                "no charge is written when it cannot be taxed");
        assertNull(order.get("cod_fee_tax_rate_bp"),
                "a null rate is not a 0% rate — that distinction is the point");

        assertEquals(0, journals("COD_FEE", orderId),
                "nothing is posted at a rate nobody approved");
        assertEquals(saleOnly, outstandingAr(orderId), "and the receivable is unchanged");
    }

    @Test
    @DisplayName("a cancelled COD order's charge is not income either")
    void cancelledOrderHasNoCodFeeIncome() throws Exception {
        configureCodFeeTax(900, 900, 1800);
        String code = placeOrder();
        Long orderId = orderIdOf(code);

        // Through the cancellation API, which records who cancelled and why —
        // updateOrderStatus is not the way an order is cancelled since P0-7 made
        // the actor and the reason mandatory.
        runAs(() -> salesOrderService.cancelAs(code, CancellationActor.ADMIN,
                CancellationReason.ADMIN_REQUEST, "no longer wanted", null), "ADMIN_GST");
        // The charge is written on the order, but the accounting refuses it.
        jdbc.update("UPDATE customer_order SET cod_fee_paise=5000, cod_fee_taxable_paise=5000, "
                  + "cod_fee_cgst_paise=450, cod_fee_sgst_paise=450, cod_fee_tax_paise=900, "
                  + "cod_fee_tax_rate_bp=1800, cod_fee_sac_code=?, cod_fee_tax_resolution='RULE_APPLIED' "
                  + "WHERE id = ?", TEST_SAC, orderId);
        accounting.backfill();

        assertEquals(0, journals("COD_FEE", orderId), "a cancelled order earns no handling income");
    }

    /**
     * The production configuration, all the way from charge to cleared receivable.
     *
     * <p>Every other COD test in this class runs on the EXCLUSIVE basis, because their
     * assertions were written against ₹50 taxable plus ₹9 tax. This one runs on
     * <b>INCLUSIVE</b>, which is what production is configured to, and it is the case
     * where the arithmetic is easiest to get wrong: the tax is inside the charge, so
     * anything that adds the fee and the tax together overstates the bill.
     *
     * <p>All three figures are worked out here rather than read back:
     * 5000 × 10000 / 11800 = 4237.29 → 4237, and 5000 − 4237 = 763.
     */
    @Test
    @DisplayName("an inclusive ₹50 charge is billed as ₹50, and clears the receivable exactly")
    void inclusiveCodChargeBillsAndClearsExactly() throws Exception {
        CodTestConfig.configure(jdbc, 5000L, 900, 900, 1800,
                GstConfigurationService.TAX_INCLUSIVE);
        taxRuleSeeded = true;
        codConfigured = true;

        String code = placeOrder();
        Long orderId = orderIdOf(code);
        long saleOnlyAr = outstandingAr(orderId);

        paymentService.initiate(buyerToken, code, "COD", "cod-" + UUID.randomUUID());

        // The snapshot carries the carve-out, and the two parts sum to the charge.
        Map<String, Object> order = orderRow(code);
        assertEquals(5000L, ((Number) order.get("cod_fee_paise")).longValue(), "₹50 charged");
        assertEquals(4237L, ((Number) order.get("cod_fee_taxable_paise")).longValue(), "₹42.37 taxable");
        assertEquals(763L, ((Number) order.get("cod_fee_tax_paise")).longValue(), "₹7.63 GST");
        assertEquals(5000L,
                ((Number) order.get("cod_fee_taxable_paise")).longValue()
                        + ((Number) order.get("cod_fee_tax_paise")).longValue(),
                "and they sum to the charge, to the paisa");

        // The receivable grows by ₹50 — the charge — not by ₹57.63.
        assertEquals(saleOnlyAr + 5000L, outstandingAr(orderId),
                "an inclusive charge raises a receivable of the charge, tax included");
        assertEquals(invoiceTotal(orderId), outstandingAr(orderId),
                "AR before delivery equals the invoice total");

        // The customer is asked for exactly that.
        long payable = attemptRepo.findByCustomerOrderIdOrderBySequenceNoAscIdAsc(orderId)
                .get(0).getAmountPaise();
        assertEquals(invoiceTotal(orderId), payable, "and is asked for the invoice, no more");

        // Income and output tax are separated: ₹42.37 to Other Income, ₹7.63 to tax.
        assertEquals(-4237L, accountMovement(orderId, "COD_FEE", "4090"),
                "only the taxable value is income");
        long outputTax = accountMovement(orderId, "COD_FEE", "2100")
                + accountMovement(orderId, "COD_FEE", "2110")
                + accountMovement(orderId, "COD_FEE", "2120");
        assertEquals(-763L, outputTax, "and the rest is output tax, not income");
        assertEquals(5000L, accountMovement(orderId, "COD_FEE", "1100"),
                "with the whole charge receivable");

        // Collected at the door, in full, with no capping.
        runAs(() -> {
            salesOrderService.updateOrderStatus(code, "PACKED");
            salesOrderService.updateOrderStatus(code, "IN_TRANSIT");
            salesOrderService.updateOrderStatus(code, "OUT_FOR_DELIVERY");
            salesOrderService.updateOrderStatus(code, "DELIVERED");
        }, "ADMIN_GST");

        assertEquals(1, collectionJournals(orderId));
        assertEquals(-payable, accountMovement(orderId, "PAYMENT_COLLECTION", "1100"),
                "the whole outstanding amount is applied against the receivable");
        assertEquals(0, outstandingAr(orderId), "AR after delivery is zero");
    }

    // ── gateway fee: recorded, not posted ────────────────────────────────────

    @Test
    @DisplayName("the gateway's fee is split 50/50 and all five figures are stored")
    void gatewayFeeIsSplitAndStored() throws Exception {
        stubGateway();
        String code = placeOrder();

        PaymentAttemptEntity paid = captureOnline(code, "ONLINE_FULL", 10_000L);

        assertEquals(10_000L, paid.getGatewayFeePaise(), "the fee the gateway reported");
        assertEquals(5_000L, paid.getGatewayFeeBusinessPaise(), "half is the business's expense");
        assertEquals(5_000L, paid.getGatewayFeeCustomerPaise(), "half is passed to the customer");
        assertEquals(paid.getAmountPaise() - 10_000L, paid.getNetSettlementPaise(),
                "and the settlement is gross less the fee");
    }

    @Test
    @DisplayName("an unknown fee stays unknown: no percentage is substituted for it")
    void unknownGatewayFeeIsNotGuessed() throws Exception {
        stubGateway();
        String code = placeOrder();

        PaymentAttemptEntity paid = captureOnline(code, "ONLINE_FULL", null);

        assertNull(paid.getGatewayFeePaise(), "the gateway did not say, so neither do we");
        assertNull(paid.getGatewayFeeBusinessPaise(), "half of an unknown number is not zero");
        assertNull(paid.getGatewayFeeCustomerPaise());
        assertNull(paid.getNetSettlementPaise());
    }

    @Test
    @DisplayName("no gateway fee reaches the ledger while its treatment is undecided")
    void gatewayFeeIsNotPosted() throws Exception {
        stubGateway();
        String code = placeOrder();
        Long orderId = orderIdOf(code);

        PaymentAttemptEntity paid = captureOnline(code, "ONLINE_FULL", 10_000L);

        // The collection posts the gross, because the gross is what the customer
        // paid. The fee is a separate event with unresolved accounts.
        assertEquals(paid.getAmountPaise(),
                accountMovement(orderId, "PAYMENT_COLLECTION", "1020"),
                "the bank is credited with what the customer paid, not with a net figure");
        assertEquals(0, (long) jdbc.queryForObject(
                "SELECT count(*) FROM journal_entry WHERE source_type = 'GATEWAY_FEE'", Long.class),
                "and no gateway-fee journal exists — the accounts for it are not approved");
    }

    // ── closed period (CP-1) ────────────────────────────────────────────────

    @Test
    @DisplayName("an open period posts the sale on the order's own date")
    void openPeriodPostsOnTheOrderDate() throws Exception {
        String code = placeOrder();
        Long orderId = orderIdOf(code);

        Map<String, Object> journal = jdbc.queryForMap(
                "SELECT journal_date, transaction_date, period FROM journal_entry "
                + "WHERE source_type='SALE' AND source_id=?", orderId);

        assertEquals(journal.get("transaction_date"), journal.get("journal_date"),
                "nothing was deferred, so the two dates are the same day");
    }

    @Test
    @DisplayName("an order in a closed period still stands, and its sale moves to the next open one")
    void closedPeriodDefersTheSale() throws Exception {
        // Place the order first, then close its period, so the order itself was
        // created normally — which is the approved behaviour: the order stands.
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        String closed = closePeriodOf(orderId);

        // Take the sale back out and let it be posted again, now that the period
        // is closed. Deleting a journal is not something production does; it is
        // how this test reaches the state where a sale has yet to be posted into
        // a period that has since closed.
        jdbc.update("DELETE FROM journal_entry_line WHERE journal_entry_id IN "
                  + "(SELECT id FROM journal_entry WHERE source_type='SALE' AND source_id=?)", orderId);
        jdbc.update("DELETE FROM journal_entry WHERE source_type='SALE' AND source_id=?", orderId);

        accounting.backfill();

        assertEquals(1, journals("SALE", orderId), "the sale is recognised, not lost");

        Map<String, Object> journal = jdbc.queryForMap(
                "SELECT period, journal_date, transaction_date FROM journal_entry "
                + "WHERE source_type='SALE' AND source_id=?", orderId);

        assertNotEquals(closed, journal.get("period"), "and not into the closed period");
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT status FROM accounting_period WHERE period = ?",
                String.class, journal.get("period")), "the period it landed in is open");
        assertEquals(LocalDate.parse(closed + "-01").plusMonths(1),
                ((java.sql.Date) journal.get("journal_date")).toLocalDate(),
                "posted on the first day of the next open period");
        assertEquals(closed, ((java.sql.Date) journal.get("transaction_date")).toLocalDate()
                        .withDayOfMonth(1).toString().substring(0, 7),
                "and the original transaction date is kept, not overwritten");

        assertEquals("CLOSED", jdbc.queryForObject(
                "SELECT status FROM accounting_period WHERE period = ?", String.class, closed),
                "the closed period was never reopened — the entry moved, not the lock");
    }

    @Test
    @DisplayName("a payment still settles the receivable when the sale was deferred")
    void deferredSaleStillSettles() throws Exception {
        stubGateway();
        String code = placeOrder();
        Long orderId = orderIdOf(code);

        String closed = closePeriodOf(orderId);
        assertNotNull(closed);

        // The receivable exists from the sale posted before the period closed; the
        // collection must land in an open period rather than failing.
        PaymentAttemptEntity paid = captureOnline(code, "ONLINE_FULL", 10_000L);

        assertEquals("CAPTURED", paid.getStatus(), "a closed period must not fail a payment");
        assertEquals(1, collectionJournals(orderId), "and the collection is still recorded");
        assertEquals(0, outstandingAr(orderId), "settling the receivable");
        assertNotEquals(closed, jdbc.queryForObject("""
                SELECT period FROM journal_entry WHERE source_type='PAYMENT_COLLECTION'
                   AND source_id IN (SELECT id FROM payment_attempt WHERE customer_order_id=?)
                """, String.class, orderId), "in an open period, not the closed one");
    }

    @Test
    @DisplayName("COD does not bypass the period lock either")
    void codDoesNotBypassThePeriodLock() throws Exception {
        configureCodFeeTax(900, 900, 1800);
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        String closed = closePeriodOf(orderId);

        paymentService.initiate(buyerToken, code, "COD", "cod-" + UUID.randomUUID());

        assertEquals(1, journals("COD_FEE", orderId), "the charge is still recognised");
        assertNotEquals(closed, jdbc.queryForObject(
                "SELECT period FROM journal_entry WHERE source_type='COD_FEE' AND source_id=?",
                String.class, orderId), "but not in the closed period");

        runAs(() -> {
            salesOrderService.updateOrderStatus(code, "PACKED");
            salesOrderService.updateOrderStatus(code, "IN_TRANSIT");
            salesOrderService.updateOrderStatus(code, "OUT_FOR_DELIVERY");
            salesOrderService.updateOrderStatus(code, "DELIVERED");
        }, "ADMIN_GST");

        assertEquals(1, collectionJournals(orderId), "and the cash is still collected");
        assertEquals(0, outstandingAr(orderId));

        // Nothing posted AFTER the period closed landed in it.
        //
        // Scoped twice over, and both narrowings are the point. Scoped to this
        // order, because the period is the current month and already holds the
        // historical journals and whatever else the suite has written. And scoped
        // to the charge and the collection, because the sale was posted when the
        // order was placed — while the period was still open — so the sale being
        // in that period is correct history, not a breach of the lock.
        assertEquals(0, (long) jdbc.queryForObject("""
                SELECT count(*) FROM journal_entry
                 WHERE period = ?
                   AND ( (source_type = 'COD_FEE' AND source_id = ?)
                      OR (source_type = 'PAYMENT_COLLECTION' AND source_id IN
                          (SELECT id FROM payment_attempt WHERE customer_order_id = ?)))
                """, Long.class, closed, orderId, orderId),
                "nothing posted after the period closed was written into it");

        // And the sale really is still there, in the period it was posted in.
        assertEquals(1, journals("SALE", orderId), "the sale posted before the close is untouched");
    }

    // ── refund workflow ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a return request alone earns no refund")
    void returnRequestAloneIsNotEnough() throws Exception {
        stubGateway();
        String code = placeOrder();
        captureOnline(code, "ONLINE_FULL", null);

        // The customer's request, unverified.
        jdbc.update("""
                INSERT INTO order_return_request (uuid, order_code, customer_id, return_type, reason,
                                                  status, archive, created_at)
                VALUES (?, ?, ?, 'RETURN', 'Did not fit', 'REQUESTED', false, now())
                """, UUID.randomUUID().toString(), code, buyerUuid);
        Long returnId = jdbc.queryForObject(
                "SELECT id FROM order_return_request WHERE order_code = ?", Long.class, code);

        RefundService.Eligibility e = refundService.eligibilityFor(returnId);

        assertFalse(e.eligible(), "a request is not a verification");
        assertTrue(e.reason().contains("warehouse verification"), e.reason());

        VeloriaException refused = assertThrows(VeloriaException.class,
                () -> runAs(() -> refundService.issue(returnId, "r-" + UUID.randomUUID(), "admin"),
                        "ADMIN_GST"));
        assertTrue(refused.getMessage().contains("warehouse verification"), refused.getMessage());
        assertEquals(0, refundJournals(orderIdOf(code)), "and nothing was posted");
    }

    @Test
    @DisplayName("a verified return of a paid order is refundable for exactly what was collected")
    void verifiedReturnIsRefundableForWhatWasPaid() throws Exception {
        stubGateway();
        String code = placeOrder();
        PaymentAttemptEntity paid = captureOnline(code, "ONLINE_FULL", null);
        Long returnId = verifyReturn(code);

        RefundService.Eligibility e = refundService.eligibilityFor(returnId);

        assertTrue(e.eligible(), e.reason());
        assertEquals(paid.getProductAllocatedPaise() + paid.getGstAllocatedPaise(), e.amountPaise(),
                "the refund is product and its GST, from the allocation stored at capture");
        assertEquals(paid.getProductAllocatedPaise(), e.productPaise());
        assertEquals(paid.getGstAllocatedPaise(), e.gstPaise());
        assertTrue(e.amountPaise() <= paid.getAmountPaise(),
                "and never more than the customer actually paid");
        assertEquals(RefundService.RAZORPAY_SOURCE, e.destination(),
                "back to whatever instrument paid");
    }

    @Test
    @DisplayName("issuing a refund returns the money and posts the adjustment")
    void refundIsIssuedAndPosted() throws Exception {
        stubGateway();
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        PaymentAttemptEntity paid = captureOnline(code, "ONLINE_FULL", null);
        Long returnId = verifyReturn(code);

        PaymentRefundEntity refund = runAsReturning(
                () -> refundService.issue(returnId, "r-" + UUID.randomUUID(), "automation-admin"),
                "ADMIN_GST");

        assertEquals(RefundService.REFUNDED, refund.getStatus());
        assertNotNull(refund.getRazorpayRefundId(), "the gateway confirmed it");
        assertEquals(returnId, refund.getReturnRequestId(), "and it is tied to the return that earned it");
        assertEquals(RefundService.RAZORPAY_SOURCE, refund.getRefundDestination());

        assertEquals(1, refundJournals(orderId), "one refund, one adjustment");
        assertEquals(refund.getProductRefundedPaise(), accountMovement(orderId, "REFUND", "4010"),
                "revenue comes back out");
        assertEquals(refund.getGstRefundedPaise(),
                accountMovement(orderId, "REFUND", "2100") + accountMovement(orderId, "REFUND", "2110")
                        + accountMovement(orderId, "REFUND", "2120"),
                "and so does the output tax that was charged");
        assertEquals(-refund.getAmountPaise(), accountMovement(orderId, "REFUND", "1020"),
                "the bank pays it out");

        // Neither the sale nor the collection is touched, and the receivable the
        // collection cleared is not re-created.
        assertEquals(1, journals("SALE", orderId), "the original sale is kept");
        assertEquals(1, collectionJournals(orderId), "so is the collection");
        assertEquals(0, outstandingAr(orderId), "and the receivable stays settled");
        assertTrue(paid.getAmountPaise() >= refund.getAmountPaise());
    }

    @Test
    @DisplayName("the COD handling charge is not refunded — the approved policy, in the arithmetic")
    void codFeeIsNotRefunded() throws Exception {
        configureCodFeeTax(900, 900, 1800);
        stubGateway();
        String code = placeOrder();
        Long orderId = orderIdOf(code);

        // An online payment of an invoice that carries the handling charge.
        jdbc.update("UPDATE customer_order SET cod_fee_paise=5000, cod_fee_taxable_paise=5000, "
                  + "cod_fee_cgst_paise=450, cod_fee_sgst_paise=450, cod_fee_tax_paise=900, "
                  + "cod_fee_tax_rate_bp=1800, cod_fee_sac_code=?, cod_fee_tax_resolution='RULE_APPLIED' "
                  + "WHERE id = ?", TEST_SAC, orderId);
        accounting.backfill();
        PaymentAttemptEntity paid = captureOnline(code, "ONLINE_FULL", null);
        Long returnId = verifyReturn(code);

        RefundService.Eligibility e = refundService.eligibilityFor(returnId);

        assertEquals(5900L, paid.getOtherAllocatedPaise(),
                "the charge and its tax were allocated to the other head");
        assertEquals(paid.getAmountPaise() - 5900L, e.amountPaise(),
                "and are excluded from the refund: the charge is non-refundable");
        assertEquals(0L, e.codFeePaise(), "no charge is given back");
        assertEquals(0L, e.codTaxPaise(), "and no tax on it is reversed");
        assertEquals(0L, paid.getTransportAllocatedPaise(), "nothing was charged for transport");
    }

    @Test
    @DisplayName("configured refundable, the charge and its output tax both come back")
    void codFeeIsRefundedWhenConfiguredRefundable() throws Exception {
        configureCodFeeTax(900, 900, 1800);
        setCodConfig(GstConfigurationService.COD_FEE_REFUNDABLE, "true");
        stubGateway();
        String code = placeOrder();
        Long orderId = orderIdOf(code);

        jdbc.update("UPDATE customer_order SET cod_fee_paise=5000, cod_fee_taxable_paise=5000, "
                  + "cod_fee_cgst_paise=450, cod_fee_sgst_paise=450, cod_fee_tax_paise=900, "
                  + "cod_fee_tax_rate_bp=1800, cod_fee_sac_code=?, cod_fee_tax_resolution='RULE_APPLIED' "
                  + "WHERE id = ?", TEST_SAC, orderId);
        accounting.backfill();
        PaymentAttemptEntity paid = captureOnline(code, "ONLINE_FULL", null);
        Long returnId = verifyReturn(code);

        RefundService.Eligibility e = refundService.eligibilityFor(returnId);

        // The charge (5000) and its tax (900) are now part of the refund, on top of
        // the product and product GST the non-refundable case gave back.
        assertEquals(5000L, e.codFeePaise(), "the charge comes back");
        assertEquals(900L, e.codTaxPaise(), "and so does the output tax collected on it");
        assertEquals(paid.getAmountPaise(), e.amountPaise(),
                "so the whole captured amount is refundable");

        PaymentRefundEntity refund = runAsReturning(
                () -> refundService.issue(returnId, "r-" + UUID.randomUUID(), "admin"), "ADMIN_GST");
        assertEquals(RefundService.REFUNDED, refund.getStatus());
        assertEquals(5000L, refund.getCodFeeRefundedPaise(), "recorded on its own head");
        assertEquals(900L, refund.getCodTaxRefundedPaise());

        // The charge reverses out of Other Income, not out of Sales — they are
        // different supplies and a credit note has to distinguish them.
        assertEquals(5000L, accountMovement(orderId, "REFUND", "4090"),
                "Other Income is debited by the charge");
        assertEquals(900L,
                accountMovement(orderId, "REFUND", "2100") + accountMovement(orderId, "REFUND", "2110"),
                "and the output tax heads by the tax on it");
        assertEquals(-refund.getAmountPaise(), accountMovement(orderId, "REFUND", "1020"),
                "the bank pays out the whole refund");
    }

    @Test
    @DisplayName("a refundable charge is given back once, not once per payment")
    void codFeeIsRefundedOnlyOnce() throws Exception {
        configureCodFeeTax(900, 900, 1800);
        setCodConfig(GstConfigurationService.COD_FEE_REFUNDABLE, "true");
        stubGateway();
        String code = placeOrder(2);
        Long orderId = orderIdOf(code);

        jdbc.update("UPDATE customer_order SET cod_fee_paise=5000, cod_fee_taxable_paise=5000, "
                  + "cod_fee_cgst_paise=450, cod_fee_sgst_paise=450, cod_fee_tax_paise=900, "
                  + "cod_fee_tax_rate_bp=1800, cod_fee_sac_code=?, cod_fee_tax_resolution='RULE_APPLIED' "
                  + "WHERE id = ?", TEST_SAC, orderId);
        accounting.backfill();
        captureOnline(code, "ONLINE_PARTIAL", null);
        // One of the two units, so the order still has something returnable.
        Long firstReturn = verifyReturn(code, 1);

        RefundService.Eligibility first = refundService.eligibilityFor(firstReturn);
        assertEquals(5000L, first.codFeePaise(), "the first refund carries the charge");
        runAsReturning(() -> refundService.issue(firstReturn, "r-" + UUID.randomUUID(), "admin"),
                "ADMIN_GST");

        // A second payment and the remaining unit returned.
        captureOnline(code, "ONLINE_PARTIAL", null);
        Long secondReturn = verifyReturn(code, 1);
        RefundService.Eligibility second = refundService.eligibilityFor(secondReturn);

        assertEquals(0L, second.codFeePaise(),
                "the charge belongs to the order and has already been returned");
        assertEquals(0L, second.codTaxPaise(), "and so has its tax");
    }

    @Test
    @DisplayName("a partial payment is refunded only up to what was actually collected")
    void partialPaymentLimitsTheRefund() throws Exception {
        stubGateway();
        String code = placeOrder();
        PaymentAttemptEntity half = captureOnline(code, "ONLINE_PARTIAL", null);
        Long returnId = verifyReturn(code);

        RefundService.Eligibility e = refundService.eligibilityFor(returnId);

        assertTrue(e.eligible(), e.reason());
        assertTrue(e.amountPaise() <= half.getAmountPaise(),
                "a half-paid order cannot be refunded in full: " + e.amountPaise()
                + " against " + half.getAmountPaise() + " collected");
        assertEquals(half.getProductAllocatedPaise() + half.getGstAllocatedPaise(), e.amountPaise(),
                "it is the refundable part of what was collected, from the stored allocation");
    }

    @Test
    @DisplayName("an unpaid order has nothing to refund")
    void unpaidOrderHasNothingToRefund() throws Exception {
        String code = placeOrder();
        Long returnId = verifyReturn(code);

        RefundService.Eligibility e = refundService.eligibilityFor(returnId);

        assertFalse(e.eligible());
        assertTrue(e.reason().contains("Nothing has been collected"), e.reason());
    }

    @Test
    @DisplayName("a cash-on-delivery order is refused a refund rather than sent one nowhere")
    void codRefundIsRefusedNotInvented() throws Exception {
        configureCodFeeTax(900, 900, 1800);
        String code = placeOrder();
        paymentService.initiate(buyerToken, code, "COD", "cod-" + UUID.randomUUID());
        runAs(() -> {
            salesOrderService.updateOrderStatus(code, "PACKED");
            salesOrderService.updateOrderStatus(code, "IN_TRANSIT");
            salesOrderService.updateOrderStatus(code, "OUT_FOR_DELIVERY");
            salesOrderService.updateOrderStatus(code, "DELIVERED");
        }, "ADMIN_GST");
        Long returnId = verifyReturn(code);

        RefundService.Eligibility e = refundService.eligibilityFor(returnId);

        assertFalse(e.eligible(), "cash has no electronic destination");
        // The reason must name the missing DECISION and must not claim one has been
        // made. The register records D-COD-REFUND as UNDECIDED, so a message saying
        // store credit is "the approved refund destination" tells the customer — and
        // the next developer — something untrue.
        assertTrue(e.reason().contains("no refund destination has been approved"),
                "the open decision must be named: " + e.reason());
        assertFalse(e.reason().toLowerCase().contains("approved refund destination is"),
                "no destination may be presented as approved: " + e.reason());
        assertTrue(e.reason().contains("amount owed stands"),
                "and the debt must not be denied along with the payout: " + e.reason());
        assertEquals(0, refundJournals(orderIdOf(code)));
    }

    @Test
    @DisplayName("the same refund request twice returns the money once")
    void refundIsIdempotentOnItsKey() throws Exception {
        stubGateway();
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        captureOnline(code, "ONLINE_FULL", null);
        Long returnId = verifyReturn(code);
        String key = "r-" + UUID.randomUUID();

        PaymentRefundEntity first = runAsReturning(
                () -> refundService.issue(returnId, key, "admin"), "ADMIN_GST");
        PaymentRefundEntity again = runAsReturning(
                () -> refundService.issue(returnId, key, "admin"), "ADMIN_GST");

        assertEquals(first.getUuid(), again.getUuid(), "the same refund, replayed");
        assertEquals(1, refundRepo.findByCustomerOrderIdOrderByIdAsc(orderId).size(),
                "one refund row");
        assertEquals(1, refundJournals(orderId), "and one accounting effect");
    }

    @Test
    @DisplayName("a second, differently-keyed refund for the same return is refused")
    void oneRefundPerReturn() throws Exception {
        stubGateway();
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        captureOnline(code, "ONLINE_FULL", null);
        Long returnId = verifyReturn(code);

        runAsReturning(() -> refundService.issue(returnId, "r-" + UUID.randomUUID(), "admin"),
                "ADMIN_GST");

        VeloriaException refused = assertThrows(VeloriaException.class,
                () -> runAs(() -> refundService.issue(returnId, "r-" + UUID.randomUUID(), "admin"),
                        "ADMIN_GST"));

        assertTrue(refused.getMessage().contains("already been refunded"), refused.getMessage());
        assertEquals(1, refundRepo.findByCustomerOrderIdOrderByIdAsc(orderId).size());
        assertEquals(1, refundJournals(orderId));
    }

    @Test
    @DisplayName("12 concurrent refund requests return the money exactly once")
    void concurrentRefundsProduceOne() throws Exception {
        stubGateway();
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        captureOnline(code, "ONLINE_FULL", null);
        Long returnId = verifyReturn(code);

        final int threads = 12;
        SecurityContext admin = contextFor("ADMIN_GST");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        // Each worker either refuses, or returns a refund. Both are correct
        // answers; what would not be correct is two different refunds.
        List<String> refusals = Collections.synchronizedList(new ArrayList<>());
        List<UUID> returned = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    SecurityContextHolder.setContext(admin);
                    try {
                        start.await();
                        returned.add(refundService.issue(
                                returnId, "r-" + UUID.randomUUID(), "admin").getUuid());
                    } catch (Exception e) {
                        refusals.add(String.valueOf(e.getMessage()));
                    } finally {
                        SecurityContextHolder.clearContext();
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(180, TimeUnit.SECONDS), "every worker must finish");
        } finally {
            pool.shutdownNow();
        }

        List<PaymentRefundEntity> refunds = refundRepo.findByCustomerOrderIdOrderByIdAsc(orderId);
        long notFailed = refunds.stream().filter(r -> !RefundService.FAILED.equals(r.getStatus())).count();

        assertEquals(1, notFailed, "exactly one refund stands: " + refunds.stream()
                .map(r -> r.getUuid() + "=" + r.getStatus()).toList());
        assertEquals(1, refundJournals(orderId), "and exactly one accounting effect");
        assertEquals(refunds.stream().filter(r -> RefundService.REFUNDED.equals(r.getStatus()))
                        .mapToLong(PaymentRefundEntity::getAmountPaise).sum(),
                -accountMovement(orderId, "REFUND", "1020"),
                "the bank paid out exactly what the refunds say");

        assertEquals(threads, refusals.size() + returned.size(), "every worker accounted for");
        assertEquals(1, Set.copyOf(returned).size(),
                "every worker that got a refund got the same one: " + returned);
        assertFalse(returned.isEmpty(), "somebody has to win");
        // The losers converge on the winner rather than erroring — a second caller
        // asking for a refund that is already on its way should be told about that
        // refund, not handed a failure. Only the ones that lost after the
        // eligibility read are refused, and they are told why.
        refusals.forEach(r -> assertTrue(
                r.contains("already been refunded") || r.contains("already being processed"),
                "a refusal must say the refund already exists: " + r));
    }

    /**
     * The same twelve-thread pressure on a refund that has nowhere to go.
     *
     * <p>{@link #concurrentRefundsProduceOne} covers the online case, where exactly
     * one refund must win. A cash-on-delivery refund has no approved destination, so
     * the correct answer is that nobody wins — and the thing to prove is that
     * concurrency cannot turn twelve refusals into one accidental payout. A race that
     * slipped past the destination check would send cash somewhere nobody chose.
     */
    @Test
    @DisplayName("12 concurrent COD refund attempts all refuse, and post nothing")
    void concurrentCodRefundAttemptsAllRefuse() throws Exception {
        configureCodFeeTax(900, 900, 1800);
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        paymentService.initiate(buyerToken, code, "COD", "cod-" + UUID.randomUUID());
        runAs(() -> {
            salesOrderService.updateOrderStatus(code, "PACKED");
            salesOrderService.updateOrderStatus(code, "IN_TRANSIT");
            salesOrderService.updateOrderStatus(code, "OUT_FOR_DELIVERY");
            salesOrderService.updateOrderStatus(code, "DELIVERED");
        }, "ADMIN_GST");
        Long returnId = verifyReturn(code);
        long arBefore = accountBalance("1100");

        final int threads = 12;
        SecurityContext admin = contextFor("ADMIN_GST");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> refusals = Collections.synchronizedList(new ArrayList<>());
        List<UUID> issued = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    SecurityContextHolder.setContext(admin);
                    try {
                        start.await();
                        issued.add(refundService.issue(
                                returnId, "r-" + UUID.randomUUID(), "admin").getUuid());
                    } catch (Exception e) {
                        refusals.add(String.valueOf(e.getMessage()));
                    } finally {
                        SecurityContextHolder.clearContext();
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(180, TimeUnit.SECONDS), "every worker must finish");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads, refusals.size(), "all twelve must be refused: issued=" + issued);
        assertTrue(issued.isEmpty(), "no refund may be issued to an unapproved destination");
        refusals.forEach(r -> assertTrue(r.contains("no refund destination has been approved"),
                "and each refusal names the open decision: " + r));

        assertEquals(0, refundRepo.findByCustomerOrderIdOrderByIdAsc(orderId).size(),
                "not even a refund row, since none could be sent");
        assertEquals(0, refundJournals(orderId), "and no accounting effect at all");
        assertEquals(arBefore, accountBalance("1100"),
                "the receivable is untouched by twelve refused attempts");
    }

    /**
     * Twelve simultaneous quotes must agree with each other.
     *
     * <p>The COD charge and the product tax are both read from configuration and the
     * tax master on every call. Concurrent readers must not see a torn view — half a
     * configuration change, or a rate from one row against a basis from another —
     * because the figure a shopper is quoted becomes the figure they are billed.
     */
    @Test
    @DisplayName("12 concurrent cart quotes all return identical figures")
    void concurrentQuotesAgree() throws Exception {
        // The production basis, deliberately: the figures twelve readers must agree on
        // are the ones a real shopper is quoted. The rest of this class uses EXCLUSIVE
        // for its ₹50 + ₹9 arithmetic (see configureCodFeeTax).
        CodTestConfig.configure(jdbc, 5000L, 900, 900, 1800,
                GstConfigurationService.TAX_INCLUSIVE);
        taxRuleSeeded = true;
        codConfigured = true;
        jdbc.update("""
                INSERT INTO customer_bag (uuid, user_id, product_uuid, quantity, added_at, archive)
                VALUES (gen_random_uuid(), ?, ?::uuid, 1, now(), false)
                """, buyerUuid, productUuid.toString());

        final int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> quotes = Collections.synchronizedList(new ArrayList<>());
        List<String> errors = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        var p = bagService.getGstPreview(buyerToken);
                        // One string per answer: comparing whole quotes catches a
                        // mismatch between any two fields, not just a wrong total.
                        quotes.add(p.gstResolved() + "|" + p.supplyType() + "|" + p.subtotalPaise()
                                + "|" + p.totalGst() + "|" + p.grandTotal()
                                + "|" + p.codCharge().available() + "|" + p.codCharge().taxablePaise()
                                + "|" + p.codCharge().taxPaise() + "|" + p.codCharge().taxRateBp()
                                + "|" + p.codCharge().totalPaise() + "|" + p.codCharge().taxResolution());
                    } catch (Exception e) {
                        errors.add(String.valueOf(e));
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(120, TimeUnit.SECONDS), "every worker must finish");
        } finally {
            pool.shutdownNow();
        }

        assertTrue(errors.isEmpty(), "a quote must never fail under concurrency: " + errors);
        assertEquals(threads, quotes.size());
        assertEquals(1, Set.copyOf(quotes).size(),
                "every concurrent quote must be the same quote: " + Set.copyOf(quotes));
        // And it is the approved one, so the test fails if they agree on nonsense.
        assertTrue(quotes.get(0).contains("|true|4237|763|1800|"),
                "the agreed quote must be the approved ₹42.37 + ₹7.63: " + quotes.get(0));
    }

    @Test
    @DisplayName("a gateway refund failure posts nothing: the books say no money moved")
    void gatewayFailureIsNotAccountedFor() throws Exception {
        stubGateway();
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        captureOnline(code, "ONLINE_FULL", null);
        Long returnId = verifyReturn(code);

        // doThrow, not when(...).thenThrow: when() has to CALL the mock to record
        // the stubbing, and calling a method already stubbed to throw throws at the
        // stubbing line instead of inside the service under test.
        org.mockito.Mockito.doThrow(new VeloriaException(ResponseCode.INTERNAL_ERROR,
                        "Could not process the refund with the payment provider."))
                .when(razorpay).createRefund(anyString(), anyLong(), anyString());

        PaymentRefundEntity refund = runAsReturning(
                () -> refundService.issue(returnId, "r-" + UUID.randomUUID(), "admin"), "ADMIN_GST");

        assertEquals(RefundService.FAILED, refund.getStatus(), "the gateway said no");
        assertNull(refund.getRazorpayRefundId(), "so there is no gateway refund to point at");
        assertEquals("GATEWAY_REFUND_FAILED", refund.getFailureCode());
        assertEquals(0, refundJournals(orderId),
                "and nothing is posted — the ledger must match what actually happened");
        assertEquals(0, outstandingAr(orderId), "the receivable is untouched");

        // A failed refund does not block a later attempt.
        org.mockito.Mockito.doReturn("rfnd_retry")
                .when(razorpay).createRefund(anyString(), anyLong(), anyString());
        PaymentRefundEntity retry = runAsReturning(
                () -> refundService.issue(returnId, "r-" + UUID.randomUUID(), "admin"), "ADMIN_GST");
        assertEquals(RefundService.REFUNDED, retry.getStatus(), "a retry may succeed");
        assertEquals(1, refundJournals(orderId), "and only then is it accounted for");
    }

    @Test
    @DisplayName("the backfill posts a completed refund whose accounting did not land")
    void backfillPostsAMissedRefund() throws Exception {
        stubGateway();
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        captureOnline(code, "ONLINE_FULL", null);
        Long returnId = verifyReturn(code);

        PaymentRefundEntity refund = runAsReturning(
                () -> refundService.issue(returnId, "r-" + UUID.randomUUID(), "admin"), "ADMIN_GST");

        // The shape of an accounting failure at the time of the refund.
        jdbc.update("DELETE FROM journal_entry_line WHERE journal_entry_id IN "
                  + "(SELECT id FROM journal_entry WHERE source_type='REFUND' AND source_id=?)",
                refund.getId());
        jdbc.update("DELETE FROM journal_entry WHERE source_type='REFUND' AND source_id=?",
                refund.getId());
        assertEquals(0, refundJournals(orderId));

        accounting.backfill();
        accounting.backfill();

        assertEquals(1, refundJournals(orderId), "posted once, and not twice");
    }

    // ── the server owns the COD figure the customer is shown (P0-12) ────────

    /**
     * The checkout screen used to add ₹50 of its own. These pin the replacement:
     * the same resolver prices the quote and the order, so the figure the customer
     * is shown is the figure they are charged.
     */
    @Test
    @DisplayName("the cart quote's COD total is exactly what the order goes on to charge")
    void codQuoteMatchesWhatIsCharged() throws Exception {
        configureCodFeeTax(900, 900, 1800);
        jdbc.update("""
                INSERT INTO customer_bag (uuid, user_id, product_uuid, quantity, added_at, archive)
                VALUES (gen_random_uuid(), ?, ?::uuid, 1, now(), false)
                """, buyerUuid, productUuid.toString());

        var quote = bagService.getGstPreview(buyerToken).codCharge();

        assertNotNull(quote, "the server must quote the charge rather than leaving it to the browser");
        assertEquals(5000L, quote.feePaise(), "the approved ₹50, from the server");
        assertEquals(900L, quote.taxPaise(), "and its tax, priced by the tax master");
        assertEquals(1800, quote.taxRateBp());
        assertEquals("RULE_APPLIED", quote.taxResolution());

        // What the order actually asks for, through the real payment path.
        String code = placeOrder();
        paymentService.initiate(buyerToken, code, "COD", "cod-" + UUID.randomUUID());
        long charged = attemptRepo
                .findByCustomerOrderIdOrderBySequenceNoAscIdAsc(orderIdOf(code)).get(0).getAmountPaise();

        assertEquals(quote.totalPaise(), charged,
                "the quote the customer saw must equal the amount they are asked for");
        assertEquals(invoiceTotal(orderIdOf(code)), charged);
    }

    @Test
    @DisplayName("with the rate unconfigured the quote says so, rather than reporting zero tax")
    void codQuoteReportsAnUnresolvedRate() throws Exception {
        // Explicitly unconfigured; see unresolvedCodFeeTaxIsNotPosted.
        jdbc.update("UPDATE gst_configuration SET config_value = NULL "
                  + "WHERE config_key = 'COD_FEE_SAC' AND organization_id = 1");
        codConfigured = true;
        jdbc.update("""
                INSERT INTO customer_bag (uuid, user_id, product_uuid, quantity, added_at, archive)
                VALUES (gen_random_uuid(), ?, ?::uuid, 1, now(), false)
                """, buyerUuid, productUuid.toString());

        var quote = bagService.getGstPreview(buyerToken).codCharge();

        // The quote does not throw — a checkout screen must be able to say COD is
        // unavailable without the whole cart preview failing — but it reports the
        // charge as unavailable, and the client keys off that rather than off the
        // amounts.
        assertFalse(quote.available(),
                "an unpriceable charge must not be presented as a priceable one");
        assertEquals("NO_SAC_CONFIGURED", quote.taxResolution(),
                "and the reason is diagnosable, not a bare refusal");
        assertNull(quote.taxRateBp(), "a null rate is not a 0% rate");
        assertEquals(0L, quote.taxPaise());
    }

    // ── the addressless product GST preview ──

    /**
     * A shopper who has not given a delivery address yet gets an answer, not a 500.
     *
     * <p>This is the product GST path, not the COD one. The engine refuses to choose
     * between CGST+SGST and IGST without a place of supply, and rightly so — but it
     * refuses by throwing {@link IllegalStateException}, which the preview passed
     * straight to the shopper as an internal error. Opening the bag before saving an
     * address is the ordinary first visit, not an exceptional case.
     *
     * <p>What it must NOT become is a zero. "The tax is not determinable" and "this
     * supply is taxed at 0%" are different statements, and a client that cannot tell
     * them apart will show a tax-free total on a taxable order. So the amounts come
     * back null and {@code gstResolved} is false.
     */
    @Test
    @DisplayName("a shopper with no delivery address gets an unresolved GST preview, not a 500")
    void addresslessProductGstPreviewDoesNot500() throws Exception {
        jdbc.update("DELETE FROM user_address WHERE user_id = ?", buyerUuid);
        jdbc.update("""
                INSERT INTO customer_bag (uuid, user_id, product_uuid, quantity, added_at, archive)
                VALUES (gen_random_uuid(), ?, ?::uuid, 2, now(), false)
                """, buyerUuid, productUuid.toString());

        // No throw is the first assertion: this is what used to produce the 500. The
        // call itself failing is the regression, so it is not wrapped.
        var preview = bagService.getGstPreview(buyerToken);

        assertFalse(preview.gstResolved(), "with no address the tax cannot be determined");
        assertEquals("NO_PLACE_OF_SUPPLY", preview.taxResolution(),
                "and the reason must be diagnosable, not a bare absence");
        assertNull(preview.supplyType(), "neither intra- nor inter-state is known");
        assertNull(preview.buyerStateCode());

        // Null, not zero. Each of these as 0 would read as a genuine 0% rate.
        assertNull(preview.cgstAmount(), "an undetermined tax is not ₹0 of CGST");
        assertNull(preview.sgstAmount(), "an undetermined tax is not ₹0 of SGST");
        assertNull(preview.igstAmount(), "an undetermined tax is not ₹0 of IGST");
        assertNull(preview.totalGst(), "an undetermined tax is not ₹0 of tax");
        assertNull(preview.grandTotal(), "and a total that includes it is not known either");

        // The money that does not depend on tax is still reported, because the
        // shopper's bag has to render.
        assertEquals(2_000_000L, preview.subtotalPaise(), "2 × ₹10,000 is price data, not tax");
        assertEquals(1, preview.items().size());
        var line = preview.items().get(0);
        assertEquals(2, line.quantity());
        assertEquals(2_000_000L, line.taxableValuePaise());
        assertEquals("6211", line.hsnCode(), "the product is classified; its tax is not determinable");
        assertNull(line.cgstRateBp(), "a null rate is not a 0% rate");
        assertNull(line.igstRateBp());
        assertNull(line.totalTax());

        // COD is quoted as unavailable for the same reason, through its own resolver.
        assertNotNull(preview.codCharge());
        assertFalse(preview.codCharge().available(),
                "the charge cannot be priced without a place of supply either");
        assertEquals("NO_PLACE_OF_SUPPLY", preview.codCharge().taxResolution());

        // And nothing was created by asking.
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer_order WHERE customer_email = ?", Integer.class, buyerEmail),
                "a preview must not create an order");
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM payment_attempt pa
                 JOIN customer_order o ON o.id = pa.customer_order_id
                WHERE o.customer_email = ?
                """, Integer.class, buyerEmail), "nor a payment attempt");
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM journal_entry
                WHERE source_type IN ('SALE', 'COD_FEE') AND created_at > now() - interval '5 minutes'
                """, Integer.class), "nor a journal");
    }

    /**
     * The preview's tolerance must not have leaked into placing an order.
     *
     * <p>A preview may say "not yet determinable". A checkout may not quietly do the
     * same for a COD charge it is about to bill, and this is the guarantee P0-13
     * established — asserted here again from the other side, so a future change that
     * relaxes the order path to make a preview simpler fails.
     */
    @Test
    @DisplayName("the preview tolerating a missing address does not make checkout tolerate one")
    void checkoutStillRequiresAPlaceOfSupply() throws Exception {
        configureCodFeeTax(900, 900, 1800);
        String code = placeOrder();
        jdbc.update("UPDATE customer_order SET buyer_state_code = NULL, place_of_supply = NULL "
                  + "WHERE order_code = ?", code);

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> paymentService.initiate(buyerToken, code, "COD", "cod-" + UUID.randomUUID()),
                "a COD charge may not be billed without a place of supply");
        assertEquals(ResponseCode.BAD_REQUEST, e.getErrorCode(),
                "and it stays a validation error, never a 500: " + e.getMessage());
        assertEquals(0, jdbc.queryForObject("""
                SELECT COUNT(*) FROM journal_entry
                WHERE source_type = 'COD_FEE' AND CAST(source_id AS TEXT) = ?
                """, Integer.class, String.valueOf(orderIdOf(code))),
                "and nothing is posted for a charge that was refused");
    }

    // ── transportation: a non-zero snapshot flows through, with no pricing ──

    /**
     * Transportation pricing is undecided, and this does not decide it.
     *
     * <p>What it proves is that the financial model already carries a
     * transportation amount wherever one is put, and that the approved refund rule
     * excludes it. The amount is written straight onto the order's snapshot — no
     * algorithm computes it, because none exists and none may be invented. When a
     * pricing rule is eventually approved, the only thing it has to do is populate
     * this column.
     */
    @Test
    @DisplayName("a non-zero transportation snapshot is invoiced, collected, and not refunded")
    void transportationSnapshotFlowsThroughWithoutPricing() throws Exception {
        stubGateway();
        String code = placeOrder();
        Long orderId = orderIdOf(code);

        // A transportation amount, taken from nowhere but this test. The order's
        // sale has already been posted for the product, so this is the future shape
        // of an order that arrives with a shipping charge already priced.
        final long transport = 50_000L;   // ₹500
        long invoiceWithoutTransport = invoiceTotal(orderId);
        jdbc.update("UPDATE customer_order SET shipping_value = ? WHERE id = ?", transport, orderId);

        assertEquals(invoiceWithoutTransport + transport, invoiceTotal(orderId),
                "the invoice grows by exactly the transportation the snapshot carries");

        PaymentAttemptEntity paid = captureOnline(code, "ONLINE_FULL", null);

        assertEquals(invoiceTotal(orderId), paid.getAmountPaise(),
                "the customer is charged the transportation the snapshot carries");
        assertEquals(transport, paid.getTransportAllocatedPaise(),
                "and the approved waterfall settles it in full, before the product");

        Long returnId = verifyReturn(code);
        RefundService.Eligibility e = refundService.eligibilityFor(returnId);

        assertTrue(e.eligible(), e.reason());
        assertEquals(paid.getProductAllocatedPaise() + paid.getGstAllocatedPaise(), e.amountPaise(),
                "the refund is product and its GST only");
        assertEquals(paid.getAmountPaise() - transport, e.amountPaise(),
                "transportation is excluded from the refund — the existing approved rule");
    }

    // ── gateway fee: the actual fee is never overwritten (P0-12 §8) ─────────

    @Test
    @DisplayName("the actual gateway fee and the two portions are three separate facts")
    void actualGatewayFeeIsNeverOverwritten() throws Exception {
        stubGateway();
        String code = placeOrder();

        PaymentAttemptEntity paid = captureOnline(code, "ONLINE_FULL", 10_001L);

        // Read back from the database, not from the in-memory object, so this is
        // what was actually persisted.
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT amount_paise, gateway_fee_paise, gateway_fee_business_paise,
                       gateway_fee_customer_paise, net_settlement_paise
                  FROM payment_attempt WHERE id = ?
                """, paid.getId());

        assertEquals(10_001L, ((Number) row.get("gateway_fee_paise")).longValue(),
                "the actual fee stays the actual fee — the customer portion must not overwrite it");
        assertEquals(5_001L, ((Number) row.get("gateway_fee_business_paise")).longValue(),
                "the business takes the odd paisa");
        assertEquals(5_000L, ((Number) row.get("gateway_fee_customer_paise")).longValue());
        assertEquals(
                ((Number) row.get("amount_paise")).longValue() - 10_001L,
                ((Number) row.get("net_settlement_paise")).longValue(),
                "settlement = gross payment − actual gateway fee");

        // And the three are genuinely different numbers, so a later change that
        // collapsed them could not pass this.
        assertNotEquals(row.get("gateway_fee_paise"), row.get("gateway_fee_business_paise"));
        assertNotEquals(row.get("gateway_fee_business_paise"), row.get("gateway_fee_customer_paise"));
    }

    // ── every journal balances ──────────────────────────────────────────────

    @Test
    @DisplayName("every journal these decisions produce balances to the paisa")
    void everyJournalBalances() throws Exception {
        configureCodFeeTax(900, 900, 1800);
        stubGateway();

        String codOrder = placeOrder();
        paymentService.initiate(buyerToken, codOrder, "COD", "cod-" + UUID.randomUUID());

        String refunded = placeOrder();
        captureOnline(refunded, "ONLINE_FULL", 10_000L);
        Long returnId = verifyReturn(refunded);
        runAsReturning(() -> refundService.issue(returnId, "r-" + UUID.randomUUID(), "admin"),
                "ADMIN_GST");

        List<Map<String, Object>> unbalanced = jdbc.queryForList("""
                SELECT je.journal_number, je.source_type FROM journal_entry je
                  JOIN journal_entry_line l ON l.journal_entry_id = je.id
                 WHERE je.source_type IN ('SALE','COD_FEE','PAYMENT_COLLECTION','REFUND','REVERSAL')
                 GROUP BY je.id, je.journal_number, je.source_type
                HAVING SUM(l.debit_paise) <> SUM(l.credit_paise)
                    OR SUM(l.debit_paise) <> je.total_debit_paise
                """);
        assertTrue(unbalanced.isEmpty(), "every journal must balance: " + unbalanced);
    }

    // ── security ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("issuing a refund needs the administrator authority, not the verifier's")
    void refundNeedsAdminAuthority() throws Exception {
        stubGateway();
        String code = placeOrder();
        captureOnline(code, "ONLINE_FULL", null);
        Long returnId = verifyReturn(code);

        // Inspecting goods is not the same permission as paying a customer back.
        assertThrows(org.springframework.security.access.AccessDeniedException.class,
                () -> runAs(() -> refundService.issue(returnId, "r-" + UUID.randomUUID(), "x"),
                        "CREATE_CREDIT_NOTE", "VIEW_GST"));
        assertEquals(0, refundJournals(orderIdOf(code)));
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private static SecurityContext contextFor(String... authorities) {
        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken("automation-admin", "n/a",
                java.util.Arrays.stream(authorities)
                        .map(org.springframework.security.core.authority.SimpleGrantedAuthority::new)
                        .map(a -> (org.springframework.security.core.GrantedAuthority) a)
                        .toList()));
        return ctx;
    }

    private void runAs(ThrowingRunnable work, String... authorities) throws Exception {
        SecurityContextHolder.setContext(contextFor(authorities));
        try { work.run(); } finally { SecurityContextHolder.clearContext(); }
    }

    private <T> T runAsReturning(ThrowingSupplier<T> work, String... authorities) throws Exception {
        SecurityContextHolder.setContext(contextFor(authorities));
        try { return work.get(); } finally { SecurityContextHolder.clearContext(); }
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
    @FunctionalInterface private interface ThrowingSupplier<T> { T get() throws Exception; }
}
