package com.app.master.service.accounting;

import com.app.master.service.core.entity.PaymentAttemptEntity;
import com.app.master.service.core.payment.CancellationActor;
import com.app.master.service.core.payment.CancellationReason;
import com.app.master.service.core.payment.PaymentStatus;
import com.app.master.service.core.request.client.PlaceOrderRequest;
import com.app.master.service.repository.payment.PaymentAttemptRepository;
import com.app.master.service.service.admin.AccountingPostingService;
import com.app.master.service.service.admin.impl.SalesOrderServiceImpl;
import com.app.master.service.service.client.ClientOrderService;
import com.app.master.service.service.payment.PaymentService;
import com.app.master.service.service.payment.RazorpayGateway;
import com.app.master.service.support.AccountingResidue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * The approved accounting model, end to end, against real PostgreSQL.
 *
 * <pre>
 *   Order created → SALE → AR
 *                            ↓
 *                   payment / COD collection → AR settled
 * </pre>
 *
 * <p>The sale is recognised when the order is created, independently of
 * payment; the collection settles the receivable it raised. A partially paid
 * order therefore has one sale and two collections, and is settled only when the
 * second lands.
 *
 * <p>Not {@code @Transactional}: the sale is posted after the order's
 * transaction commits, so a test-owned transaction would prevent it happening at
 * all.
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
class SaleAndCollectionPostgresTest {

    @MockBean private RazorpayGateway razorpay;

    @Autowired private ClientOrderService orderService;
    @Autowired private PaymentService paymentService;
    @Autowired private SalesOrderServiceImpl salesOrderService;
    @Autowired private AccountingPostingService accounting;
    @Autowired private PaymentAttemptRepository attemptRepo;
    @Autowired private JdbcTemplate jdbc;

    private String buyerUuid;
    private String buyerToken;
    private UUID productUuid;
    private Long productId;
    private final List<String> orderCodes = new ArrayList<>();

    // ── fixture ──────────────────────────────────────────────────────────────

    @BeforeEach
    void seed() {
        buyerUuid = UUID.randomUUID().toString();
        String email = "sale-" + buyerUuid.substring(0, 8) + "@automation.veloria.test";
        jdbc.update("""
                INSERT INTO users (uuid, first_name, last_name, email, phone, active, archive, created)
                VALUES (?::uuid, 'Automation', 'Buyer', ?, '9000000000', true, false, now())
                """, buyerUuid, email);
        buyerToken = "sale-" + UUID.randomUUID();
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
                VALUES (?::uuid, 'Automation Sale Product', ?, 1000000, 1000000, 'INR', 20, '6211',
                        false, true, false, now())
                """, productUuid.toString(), "AUTO-SAL-" + productUuid.toString().substring(0, 8));
        productId = jdbc.queryForObject(
                "SELECT id FROM inventory_product WHERE uuid = ?::uuid", Long.class, productUuid.toString());
    }

    /** Places a real order through checkout, so the sale is posted the way it is in production. */
    private String placeOrder() throws Exception {
        PlaceOrderRequest r = new PlaceOrderRequest();
        r.setDeliveryLocation("12 MG Road, Bengaluru, Karnataka 560001");
        r.setCurrency("INR");
        PlaceOrderRequest.OrderItemRequest item = new PlaceOrderRequest.OrderItemRequest();
        item.setProductUuid(productUuid);
        item.setQuantity(1);
        r.setItems(List.of(item));

        String code = orderService.placeOrder(buyerToken, r).getOrderCode();
        orderCodes.add(code);
        // The sale is posted after the order's transaction commits.
        waitForSale(orderIdOf(code));
        return code;
    }

    /** The sale lands just after commit; give it a moment rather than racing it. */
    private void waitForSale(Long orderId) throws Exception {
        for (int i = 0; i < 50 && saleJournals(orderId) == 0; i++) Thread.sleep(100);
    }

    @AfterEach
    void cleanUp() {
        for (String code : orderCodes) {
            Long id = jdbc.query("SELECT id FROM customer_order WHERE order_code = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, code);
            if (id == null) continue;
            jdbc.update("""
                    DELETE FROM journal_entry_line WHERE journal_entry_id IN (
                        SELECT r.id FROM journal_entry r WHERE r.reverses_journal_id IN (
                            SELECT je.id FROM journal_entry je
                             WHERE (je.source_type IN ('SALE','COD_FEE') AND je.source_id=?)
                                OR (je.source_type='PAYMENT_COLLECTION' AND je.source_id IN
                                    (SELECT pa.id FROM payment_attempt pa WHERE pa.customer_order_id=?))))
                    """, id, id);
            jdbc.update("""
                    DELETE FROM journal_entry WHERE reverses_journal_id IN (
                        SELECT je.id FROM journal_entry je
                         WHERE (je.source_type IN ('SALE','COD_FEE') AND je.source_id=?)
                            OR (je.source_type='PAYMENT_COLLECTION' AND je.source_id IN
                                (SELECT pa.id FROM payment_attempt pa WHERE pa.customer_order_id=?)))
                    """, id, id);
            jdbc.update("""
                    DELETE FROM journal_entry_line WHERE journal_entry_id IN (
                        SELECT id FROM journal_entry
                         WHERE (source_type IN ('SALE','COD_FEE') AND source_id=?)
                            OR (source_type='PAYMENT_COLLECTION' AND source_id IN
                                (SELECT pa.id FROM payment_attempt pa WHERE pa.customer_order_id=?))
                            OR (source_type='REFUND' AND source_id IN
                                (SELECT pr.id FROM payment_refund pr WHERE pr.customer_order_id=?)))
                    """, id, id, id);
            jdbc.update("""
                    DELETE FROM journal_entry
                     WHERE (source_type IN ('SALE','COD_FEE') AND source_id=?)
                        OR (source_type='PAYMENT_COLLECTION' AND source_id IN
                            (SELECT pa.id FROM payment_attempt pa WHERE pa.customer_order_id=?))
                        OR (source_type='REFUND' AND source_id IN
                            (SELECT pr.id FROM payment_refund pr WHERE pr.customer_order_id=?))
                    """, id, id, id);
            jdbc.update("DELETE FROM payment_refund WHERE customer_order_id = ?", id);
            jdbc.update("DELETE FROM payment_attempt WHERE customer_order_id = ?", id);
            jdbc.update("DELETE FROM gst_movement_ledger WHERE CAST(source_id AS TEXT) = ?", code);
            jdbc.update("DELETE FROM gst_output_tax WHERE order_code = ?", code);
            jdbc.update("DELETE FROM gst_accounting_exception WHERE CAST(source_id AS TEXT) = ?", code);
            jdbc.update("DELETE FROM sales_order WHERE order_code = ?", code);
            jdbc.update("DELETE FROM customer_order_item WHERE customer_order_id = ?", id);
            jdbc.update("DELETE FROM customer_order WHERE id = ?", id);
        }
        orderCodes.clear();
        jdbc.update("DELETE FROM inventory_product WHERE id = ?", productId);
        jdbc.update("DELETE FROM user_address WHERE user_id = ?", buyerUuid);
        jdbc.update("DELETE FROM client_session WHERE user_id = ?", buyerUuid);
        jdbc.update("DELETE FROM users WHERE uuid = ?::uuid", buyerUuid);

        // Fail here rather than let residue move the historical checksum.
        AccountingResidue.assertNone(jdbc);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Long orderIdOf(String code) {
        return jdbc.queryForObject("SELECT id FROM customer_order WHERE order_code = ?", Long.class, code);
    }

    private long saleJournals(Long orderId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM journal_entry WHERE source_type='SALE' AND source_id=?",
                Long.class, orderId);
    }

    private long collectionJournals(Long orderId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM journal_entry
                 WHERE source_type='PAYMENT_COLLECTION'
                   AND source_id IN (SELECT id FROM payment_attempt WHERE customer_order_id=?)
                """, Long.class, orderId);
    }

    /** The receivable still standing for this order: sale debits less collection credits. */
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
                          (SELECT x.id FROM journal_entry x WHERE x.source_type IN ('SALE','COD_FEE') AND x.source_id=?))
                """, Long.class, orderId, orderId, orderId);
    }

    private long invoiceTotal(Long orderId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(total_value,0) + COALESCE(shipping_value,0)
                     + COALESCE(cod_fee_paise,0) + COALESCE(cod_fee_tax_paise,0)
                  FROM customer_order WHERE id = ?
                """, Long.class, orderId);
    }

    private void stubGateway() throws Exception {
        when(razorpay.createOrder(org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString()))
                .thenAnswer(i -> "order_" + UUID.randomUUID().toString().substring(0, 12));
    }

    private PaymentAttemptEntity captureOnline(String orderCode, String mode) throws Exception {
        String key = "k-" + UUID.randomUUID();
        paymentService.initiate(buyerToken, orderCode, mode, key);
        PaymentAttemptEntity a = attemptRepo.findByIdempotencyKey(key).orElseThrow();
        String paymentId = "pay_" + UUID.randomUUID().toString().substring(0, 12);
        when(razorpay.fetchPayment(anyString())).thenReturn(new RazorpayGateway.PaymentView(
                paymentId, a.getRazorpayOrderId(), "captured", a.getAmountPaise(),
                "INR", "card", null, null, null, null));
        paymentService.applyGatewayTruth(a, paymentId, "test");
        return attemptRepo.findById(a.getId()).orElseThrow();
    }

    // ── SALE at order creation ───────────────────────────────────────────────

    @Test
    @DisplayName("placing an order posts exactly one sale and raises the receivable")
    void orderCreationPostsOneSale() throws Exception {
        String code = placeOrder();
        Long orderId = orderIdOf(code);

        assertEquals(1, saleJournals(orderId), "one order, one sale");
        assertEquals(invoiceTotal(orderId), outstandingAr(orderId),
                "the receivable equals the invoice the server computed");
        assertTrue(outstandingAr(orderId) > 0);
    }

    @Test
    @DisplayName("the backfill cannot post a second sale for an order already accounted for")
    void backfillDoesNotDuplicate() throws Exception {
        String code = placeOrder();
        Long orderId = orderIdOf(code);

        accounting.backfill();
        accounting.backfill();

        assertEquals(1, saleJournals(orderId), "the backfill must not duplicate it");
    }

    @Test
    @DisplayName("20 concurrent posting attempts still produce exactly one sale")
    void concurrentSalePostingProducesOne() throws Exception {
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        var order = jdbc.queryForObject("SELECT order_code FROM customer_order WHERE id=?", String.class, orderId);
        assertNotNull(order);

        final int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try { start.await(); accounting.backfill(); }
                    catch (Exception ignored) { }
                    finally { done.countDown(); }
                });
            }
            start.countDown();
            assertTrue(done.await(180, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }

        assertEquals(1, saleJournals(orderId), "exactly one sale, and one receivable");
        assertEquals(invoiceTotal(orderId), outstandingAr(orderId));
    }

    // ── collection settles AR ────────────────────────────────────────────────

    @Test
    @DisplayName("a full payment settles the receivable to zero")
    void fullPaymentClearsAr() throws Exception {
        stubGateway();
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        long invoice = invoiceTotal(orderId);
        assertEquals(invoice, outstandingAr(orderId));

        PaymentAttemptEntity paid = captureOnline(code, "ONLINE_FULL");

        assertEquals(PaymentStatus.CAPTURED.name(), paid.getStatus());
        assertEquals(1, collectionJournals(orderId), "one payment, one collection");
        assertEquals(0, outstandingAr(orderId), "the receivable is settled exactly");
    }

    @Test
    @DisplayName("a repeated capture settles the receivable once, not twice")
    void collectionIsIdempotent() throws Exception {
        stubGateway();
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        PaymentAttemptEntity paid = captureOnline(code, "ONLINE_FULL");

        // The webhook turning up after the callback, several times over.
        for (int i = 0; i < 5; i++) {
            paymentService.applyGatewayTruth(paid, paid.getRazorpayPaymentId(), "webhook");
        }
        accounting.backfill();

        assertEquals(1, collectionJournals(orderId), "one collection, however many arrivals");
        assertEquals(0, outstandingAr(orderId));
    }

    @Test
    @DisplayName("a partial payment settles half the receivable, and the balance settles the rest")
    void partialPaymentSettlesInTwo() throws Exception {
        stubGateway();
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        long invoice = invoiceTotal(orderId);

        PaymentAttemptEntity first = captureOnline(code, "ONLINE_PARTIAL");
        assertEquals(invoice / 2, first.getAmountPaise(), "half the invoice");
        assertEquals(invoice - first.getAmountPaise(), outstandingAr(orderId),
                "the rest is still owed — a half-paid order is not settled");

        PaymentAttemptEntity second = captureOnline(code, "ONLINE_PARTIAL");
        assertEquals(invoice - first.getAmountPaise(), second.getAmountPaise(), "the balance");

        assertEquals(2, collectionJournals(orderId), "one sale, two collections");
        assertEquals(0, outstandingAr(orderId), "and now it is settled");
    }

    // ── COD ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("cash on delivery is collected when the order reaches the customer, not before")
    void codCollectsOnDelivery() throws Exception {
        String code = placeOrder();
        Long orderId = orderIdOf(code);

        paymentService.initiate(buyerToken, code, "COD", "cod-" + UUID.randomUUID());
        assertEquals(0, collectionJournals(orderId), "choosing COD collects nothing");
        assertTrue(outstandingAr(orderId) > 0, "the receivable stands until the cash arrives");

        runAsAdmin(() -> {
            salesOrderService.updateOrderStatus(code, "PACKED");
            salesOrderService.updateOrderStatus(code, "IN_TRANSIT");
            salesOrderService.updateOrderStatus(code, "OUT_FOR_DELIVERY");
            assertEquals(0, collectionJournals(orderId), "still nothing at the door");
            salesOrderService.updateOrderStatus(code, "DELIVERED");
        });

        assertEquals(1, collectionJournals(orderId), "delivery is when the cash is collected");
        assertEquals(0, outstandingAr(orderId), "and the receivable is settled");

        // The cash went to the cash account, not the bank.
        assertEquals(1, (long) jdbc.queryForObject("""
                SELECT count(*) FROM journal_entry_line l JOIN journal_entry je ON je.id=l.journal_entry_id
                 WHERE je.source_type='PAYMENT_COLLECTION' AND l.account_code='1010' AND l.debit_paise > 0
                   AND je.source_id IN (SELECT id FROM payment_attempt WHERE customer_order_id=?)
                """, Long.class, orderId));
    }

    @Test
    @DisplayName("a COD order cannot be collected twice, however often delivery is repeated")
    void codCollectionIsIdempotent() throws Exception {
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        paymentService.initiate(buyerToken, code, "COD", "cod-" + UUID.randomUUID());

        runAsAdmin(() -> {
            salesOrderService.updateOrderStatus(code, "PACKED");
            salesOrderService.updateOrderStatus(code, "IN_TRANSIT");
            salesOrderService.updateOrderStatus(code, "OUT_FOR_DELIVERY");
            salesOrderService.updateOrderStatus(code, "DELIVERED");
            for (int i = 0; i < 5; i++) salesOrderService.updateOrderStatus(code, "DELIVERED");
        });
        paymentService.recordCodCollection(orderId, code);

        assertEquals(1, collectionJournals(orderId), "one delivery, one collection");
        assertEquals(0, outstandingAr(orderId));
    }

    // ── payment failure reverses the sale ────────────────────────────────────

    @Test
    @DisplayName("a failed payment reverses the sale it had already raised, leaving no receivable")
    void failedPaymentReversesTheSale() throws Exception {
        String code = placeOrder();
        Long orderId = orderIdOf(code);
        assertEquals(1, saleJournals(orderId));
        assertTrue(outstandingAr(orderId) > 0, "the sale raised a receivable");

        // The approved consequence of a failed payment.
        runAsAdmin(() -> salesOrderService.cancelAs(code, CancellationActor.SYSTEM,
                CancellationReason.PAYMENT_FAILED, null, null));

        assertEquals(1, saleJournals(orderId), "the original sale is kept, not deleted");
        assertEquals(0, outstandingAr(orderId), "but nothing is owed any more");
        assertEquals(0, collectionJournals(orderId), "and no money was ever recorded as received");
    }

    @Test
    @DisplayName("the backfill does not resurrect a sale that a cancellation reversed")
    void backfillDoesNotResurrectAReversedSale() throws Exception {
        String code = placeOrder();
        Long orderId = orderIdOf(code);

        runAsAdmin(() -> salesOrderService.cancelAs(code, CancellationActor.SYSTEM,
                CancellationReason.PAYMENT_FAILED, null, null));
        accounting.backfill();
        accounting.backfill();

        assertEquals(1, saleJournals(orderId), "still one, and still reversed");
        assertEquals(0, outstandingAr(orderId));
    }

    // ── reconciliation ───────────────────────────────────────────────────────

    @Test
    @DisplayName("for every order, invoice minus collections minus reversals equals the receivable")
    void reconciles() throws Exception {
        stubGateway();
        String paid = placeOrder();
        String half = placeOrder();
        String cancelled = placeOrder();

        captureOnline(paid, "ONLINE_FULL");
        captureOnline(half, "ONLINE_PARTIAL");
        runAsAdmin(() -> salesOrderService.cancelAs(cancelled, CancellationActor.SYSTEM,
                CancellationReason.PAYMENT_FAILED, null, null));

        assertEquals(0, outstandingAr(orderIdOf(paid)), "fully paid → nothing owed");
        assertEquals(invoiceTotal(orderIdOf(half)) / 2 + invoiceTotal(orderIdOf(half)) % 2,
                outstandingAr(orderIdOf(half)), "half paid → the balance is owed");
        assertEquals(0, outstandingAr(orderIdOf(cancelled)), "cancelled → nothing owed");

        // Every journal this produced balances.
        List<Map<String, Object>> unbalanced = jdbc.queryForList("""
                SELECT je.journal_number FROM journal_entry je
                  JOIN journal_entry_line l ON l.journal_entry_id = je.id
                 WHERE je.source_type IN ('SALE','PAYMENT_COLLECTION','REVERSAL')
                 GROUP BY je.id, je.journal_number
                HAVING SUM(l.debit_paise) <> SUM(l.credit_paise)
                """);
        assertTrue(unbalanced.isEmpty(), "every journal must balance: " + unbalanced);
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
}
