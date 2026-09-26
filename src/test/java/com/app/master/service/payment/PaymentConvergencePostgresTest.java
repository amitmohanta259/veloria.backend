package com.app.master.service.payment;

import com.app.master.service.core.entity.PaymentAttemptEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.payment.PaymentStatus;
import com.app.master.service.repository.payment.PaymentAttemptRepository;
import com.app.master.service.service.payment.PaymentService;
import com.app.master.service.service.payment.RazorpayGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * What happens when the browser callback and Razorpay's webhook arrive together.
 *
 * <p>This is the race that matters most in a payment integration and the one
 * that cannot be exercised by clicking through a checkout: both paths report the
 * same event, usually within milliseconds of each other, and they must converge
 * on one answer rather than each applying their own.
 *
 * <p>The gateway itself is stubbed. That is deliberate and is not a way of
 * avoiding a real test: what is under examination is <em>this</em> application's
 * convergence logic, and pointing two concurrent threads at Razorpay's sandbox
 * would test Razorpay's behaviour instead. The signature and status checks that
 * sit in front of this logic are exercised separately, and against the real
 * gateway once credentials exist.
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
class PaymentConvergencePostgresTest {

    /** Stubbed so the convergence can be tested without a network or credentials. */
    @MockBean private RazorpayGateway razorpay;

    @Autowired private PaymentService paymentService;
    @Autowired private PaymentAttemptRepository attemptRepo;
    @Autowired private JdbcTemplate jdbc;

    private String buyerUuid;
    private Long orderId;
    private String orderCode;
    private Long productId;

    // ── fixture ──────────────────────────────────────────────────────────────

    @BeforeEach
    void seed() {
        buyerUuid = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (uuid, first_name, last_name, email, phone, active, archive, created)
                VALUES (?::uuid, 'Automation', 'Buyer', ?, '9000000000', true, false, now())
                """, buyerUuid, "conv-" + buyerUuid.substring(0, 8) + "@automation.veloria.test");

        UUID productUuid = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_product (uuid, name, sku_id, price, selling_price, price_currency,
                                               initial_stock, hsn_code, archive, created)
                VALUES (?::uuid, 'Automation Convergence Product', ?, 1000000, 1000000, 'INR', 5, '6211', false, now())
                """, productUuid.toString(), "AUTO-CNV-" + productUuid.toString().substring(0, 8));
        productId = jdbc.queryForObject(
                "SELECT id FROM inventory_product WHERE uuid = ?::uuid", Long.class, productUuid.toString());

        orderCode = "VO-CNV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("""
                INSERT INTO customer_order (uuid, order_code, customer_id, customer_name, customer_email,
                                            delivery_location, currency, total_value, taxable_value,
                                            cgst_amount, sgst_amount, igst_amount, total_tax_amount,
                                            shipping_value, cod_fee_paise, status, order_placed_at, active, archive)
                VALUES (gen_random_uuid(), ?, ?, 'Automation Buyer', ?, '12 MG Road, Bengaluru', 'INR',
                        1180000, 1000000, 90000, 90000, 0, 180000, 0, 0,
                        'ORDER_PLACED', timezone('UTC', now()), true, false)
                """, orderCode, buyerUuid, "conv-" + buyerUuid.substring(0, 8) + "@automation.veloria.test");
        orderId = jdbc.queryForObject("SELECT id FROM customer_order WHERE order_code = ?", Long.class, orderCode);
        jdbc.update("""
                INSERT INTO customer_order_item (uuid, customer_order_id, product_uuid, quantity, archive)
                VALUES (gen_random_uuid(), ?, ?::uuid, 1, false)
                """, orderId, productUuid.toString());
    }

    /** A payment attempt sitting at PENDING, as it would be while the customer pays. */
    private PaymentAttemptEntity pendingAttempt(long amountPaise) {
        return attemptRepo.saveAndFlush(PaymentAttemptEntity.builder()
                .customerOrderId(orderId)
                .orderCode(orderCode)
                .customerId(buyerUuid)
                .idempotencyKey("conv-" + UUID.randomUUID())
                .sequenceNo(1)
                .gateway("RAZORPAY")
                .status(PaymentStatus.PENDING.name())
                .amountPaise(amountPaise)
                .currency("INR")
                .razorpayOrderId("order_" + UUID.randomUUID().toString().substring(0, 12))
                .createdAt(Instant.now())
                .build());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM payment_refund WHERE customer_order_id = ?", orderId);
        jdbc.update("DELETE FROM payment_attempt WHERE customer_order_id = ?", orderId);
        jdbc.update("DELETE FROM customer_order_item WHERE customer_order_id = ?", orderId);
        jdbc.update("DELETE FROM customer_order WHERE id = ?", orderId);
        jdbc.update("DELETE FROM inventory_product WHERE id = ?", productId);
        jdbc.update("DELETE FROM users WHERE uuid = ?::uuid", buyerUuid);
    }

    private RazorpayGateway.PaymentView captured(PaymentAttemptEntity a, String paymentId) {
        return new RazorpayGateway.PaymentView(paymentId, a.getRazorpayOrderId(), "captured",
                a.getAmountPaise(), "INR", "card", null, null, null, 2360L);
    }

    private String statusOf(Long attemptId) {
        return jdbc.queryForObject("SELECT status FROM payment_attempt WHERE id = ?", String.class, attemptId);
    }

    private long availableStock() {
        return jdbc.queryForObject("""
                SELECT COALESCE(ip.initial_stock, 0)
                     - COALESCE((SELECT SUM(coi.quantity) FROM customer_order_item coi
                                  JOIN customer_order co ON co.id = coi.customer_order_id AND co.archive = false
                                 WHERE coi.product_uuid = ip.uuid AND coi.archive = false
                                   AND co.status IN (%s)), 0)
                  FROM inventory_product ip WHERE ip.id = ?
                """.formatted(com.app.master.service.core.order.OrderStatus.CONSUMING_SQL), Long.class, productId);
    }

    // ── the race ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("callback and webhook arriving together produce one capture, not two")
    void callbackAndWebhookConverge() throws Exception {
        PaymentAttemptEntity attempt = pendingAttempt(1180000);
        String paymentId = "pay_" + UUID.randomUUID().toString().substring(0, 12);
        when(razorpay.fetchPayment(anyString())).thenReturn(captured(attempt, paymentId));

        final int threads = 16;   // half "callback", half "webhook"
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final String source = (i % 2 == 0) ? "callback" : "webhook";
                pool.submit(() -> {
                    try {
                        start.await();
                        paymentService.applyGatewayTruth(attempt, paymentId, source);
                    } catch (Exception e) {
                        failures.add(source + ": " + e.getMessage());
                    } finally { done.countDown(); }
                });
            }
            start.countDown();
            assertTrue(done.await(120, TimeUnit.SECONDS), "all arrivals should finish");
        } finally { pool.shutdownNow(); }

        assertTrue(failures.isEmpty(), "neither path should error: " + failures);
        assertEquals(PaymentStatus.CAPTURED.name(), statusOf(attempt.getId()),
                "the payment settles on captured");

        // One attempt, captured once. Not two rows, not a double allocation.
        assertEquals(1, (long) jdbc.queryForObject(
                "SELECT count(*) FROM payment_attempt WHERE customer_order_id = ?", Long.class, orderId));

        PaymentAttemptEntity settled = attemptRepo.findById(attempt.getId()).orElseThrow();
        assertEquals(180000, settled.getGstAllocatedPaise(), "GST allocated once, in full");
        assertEquals(1000000, settled.getProductAllocatedPaise(), "and the rest to product");
        assertEquals(settled.getAmountPaise(),
                settled.getGstAllocatedPaise() + settled.getTransportAllocatedPaise()
                        + settled.getOtherAllocatedPaise() + settled.getProductAllocatedPaise(),
                "the allocation must still add up after a race");

        // Exactly one payment is counted as collected.
        assertEquals(1180000, (long) attemptRepo.capturedTotalPaise(orderId),
                "a converged race must not count the money twice");
    }

    @Test
    @DisplayName("a failure reported by both paths cancels the order once and releases stock once")
    void concurrentFailureConverges() throws Exception {
        PaymentAttemptEntity attempt = pendingAttempt(1180000);
        String paymentId = "pay_" + UUID.randomUUID().toString().substring(0, 12);
        when(razorpay.fetchPayment(anyString())).thenReturn(new RazorpayGateway.PaymentView(
                paymentId, attempt.getRazorpayOrderId(), "failed", attempt.getAmountPaise(), "INR",
                "card", "BAD_REQUEST_ERROR", "Payment declined by the bank", "issuer", null));

        assertEquals(4, availableStock(), "the order holds one of five units");

        final int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < threads; i++) {
                final String source = (i % 2 == 0) ? "callback" : "webhook";
                pool.submit(() -> {
                    try {
                        start.await();
                        paymentService.applyGatewayTruth(attempt, paymentId, source);
                    } catch (Exception e) {
                        failures.add(source + ": " + e.getMessage());
                    } finally { done.countDown(); }
                });
            }
            start.countDown();
            assertTrue(done.await(120, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }

        assertTrue(failures.isEmpty(), "neither path should error: " + failures);
        assertEquals(PaymentStatus.FAILED.name(), statusOf(attempt.getId()));
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM customer_order WHERE id = ?", String.class, orderId));
        assertEquals("SYSTEM", jdbc.queryForObject(
                "SELECT cancelled_by FROM customer_order WHERE id = ?", String.class, orderId));

        assertEquals(5, availableStock(), "stock released exactly once, despite twelve arrivals");

        // And the failure was recorded, without a sale.
        PaymentAttemptEntity failed = attemptRepo.findById(attempt.getId()).orElseThrow();
        assertEquals("BAD_REQUEST_ERROR", failed.getFailureCode());
        assertNotNull(failed.getFailedAt());
        assertEquals(0, (long) jdbc.queryForObject(
                "SELECT count(*) FROM journal_entry WHERE source_type='SALE' AND source_id=?",
                Long.class, orderId), "a failed payment is never a sale");
        assertEquals(0, (long) attemptRepo.capturedTotalPaise(orderId), "and nothing was collected");
    }

    // ── verification refuses what it should ──────────────────────────────────

    @Test
    @DisplayName("a payment whose amount differs from the order is refused")
    void wrongAmountIsRefused() throws Exception {
        PaymentAttemptEntity attempt = pendingAttempt(1180000);
        String paymentId = "pay_" + UUID.randomUUID().toString().substring(0, 12);

        // The gateway reports one rupee. Signature or not, this is not our payment.
        when(razorpay.fetchPayment(anyString())).thenReturn(new RazorpayGateway.PaymentView(
                paymentId, attempt.getRazorpayOrderId(), "captured", 100L, "INR",
                "card", null, null, null, null));

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> paymentService.applyGatewayTruth(attempt, paymentId, "callback"));
        assertTrue(e.getMessage().toLowerCase().contains("does not match"), e.getMessage());

        assertEquals(PaymentStatus.PENDING.name(), statusOf(attempt.getId()), "and nothing moved");
        assertEquals(0, (long) attemptRepo.capturedTotalPaise(orderId));
    }

    @Test
    @DisplayName("a payment belonging to a different gateway order is refused")
    void wrongRazorpayOrderIsRefused() throws Exception {
        PaymentAttemptEntity attempt = pendingAttempt(1180000);
        String paymentId = "pay_" + UUID.randomUUID().toString().substring(0, 12);

        when(razorpay.fetchPayment(anyString())).thenReturn(new RazorpayGateway.PaymentView(
                paymentId, "order_SOMEONE_ELSES", "captured", attempt.getAmountPaise(), "INR",
                "card", null, null, null, null));

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> paymentService.applyGatewayTruth(attempt, paymentId, "callback"));
        assertTrue(e.getMessage().toLowerCase().contains("does not belong"), e.getMessage());
        assertEquals(PaymentStatus.PENDING.name(), statusOf(attempt.getId()));
    }

    @Test
    @DisplayName("a payment in a currency we did not ask for is refused")
    void wrongCurrencyIsRefused() throws Exception {
        PaymentAttemptEntity attempt = pendingAttempt(1180000);
        String paymentId = "pay_" + UUID.randomUUID().toString().substring(0, 12);

        when(razorpay.fetchPayment(anyString())).thenReturn(new RazorpayGateway.PaymentView(
                paymentId, attempt.getRazorpayOrderId(), "captured", attempt.getAmountPaise(), "USD",
                "card", null, null, null, null));

        assertThrows(VeloriaException.class,
                () -> paymentService.applyGatewayTruth(attempt, paymentId, "callback"));
        assertEquals(PaymentStatus.PENDING.name(), statusOf(attempt.getId()));
    }

    @Test
    @DisplayName("an unsigned callback is refused before anything is looked up")
    void unsignedCallbackIsRefused() {
        PaymentAttemptEntity attempt = pendingAttempt(1180000);

        // No credentials are configured in this test context, so no signature
        // can verify — which is the behaviour under test: refuse, never assume.
        VeloriaException e = assertThrows(VeloriaException.class, () -> paymentService.verify(
                "no-such-session", attempt.getRazorpayOrderId(), "pay_whatever", "not-a-signature"));
        assertNotNull(e.getMessage());
        assertEquals(PaymentStatus.PENDING.name(), statusOf(attempt.getId()),
                "a refused callback must not move the payment");
    }

    @Test
    @DisplayName("an already-captured payment ignores a late arrival rather than re-applying it")
    void lateArrivalIsIgnored() throws Exception {
        PaymentAttemptEntity attempt = pendingAttempt(1180000);
        String paymentId = "pay_" + UUID.randomUUID().toString().substring(0, 12);
        when(razorpay.fetchPayment(anyString())).thenReturn(captured(attempt, paymentId));

        paymentService.applyGatewayTruth(attempt, paymentId, "callback");
        assertEquals(PaymentStatus.CAPTURED.name(), statusOf(attempt.getId()));

        long allocatedAfterFirst = attemptRepo.findById(attempt.getId()).orElseThrow().getGstAllocatedPaise();

        // The webhook turns up afterwards, as it routinely does.
        for (int i = 0; i < 5; i++) {
            paymentService.applyGatewayTruth(attempt, paymentId, "webhook payment.captured");
        }

        assertEquals(PaymentStatus.CAPTURED.name(), statusOf(attempt.getId()));
        assertEquals(allocatedAfterFirst,
                attemptRepo.findById(attempt.getId()).orElseThrow().getGstAllocatedPaise(),
                "a late arrival must not allocate again");
        assertEquals(1180000, (long) attemptRepo.capturedTotalPaise(orderId),
                "nor count the money again");
    }
}
