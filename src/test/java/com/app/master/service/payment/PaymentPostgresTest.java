package com.app.master.service.payment;

import com.app.master.service.core.entity.CustomerOrderEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.order.OrderStatus;
import com.app.master.service.core.payment.CancellationActor;
import com.app.master.service.core.payment.CancellationReason;
import com.app.master.service.repository.admin.CustomerOrderRepository;
import com.app.master.service.repository.payment.PaymentAttemptRepository;
import com.app.master.service.service.admin.impl.SalesOrderServiceImpl;
import com.app.master.service.service.payment.PaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Payment against real PostgreSQL.
 *
 * <p>These cover what the database is responsible for — the unique indexes that
 * make a duplicate payment impossible, the row lock that makes the browser
 * callback and the webhook converge, and the cancellation that releases stock
 * exactly once. They do not call Razorpay: the gateway paths need credentials
 * and a network, and what is under test here is this application's own
 * correctness around them.
 *
 * <p>Not {@code @Transactional}: worker threads must see each other's commits.
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
class PaymentPostgresTest {

    @Autowired private PaymentService paymentService;
    @Autowired private SalesOrderServiceImpl orderService;
    @Autowired private PaymentAttemptRepository attemptRepo;
    @Autowired private CustomerOrderRepository orderRepo;
    @Autowired private JdbcTemplate jdbc;

    private String buyerUuid;
    private String buyerToken;
    private String otherToken;
    private String otherUuid;
    private final List<String> orderCodes = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<String> users = new ArrayList<>();

    // ── fixture ──────────────────────────────────────────────────────────────

    @BeforeEach
    void seed() {
        buyerUuid = newUser();
        buyerToken = newSession(buyerUuid);
        otherUuid = newUser();
        otherToken = newSession(otherUuid);
    }

    private String newUser() {
        String uuid = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO users (uuid, first_name, last_name, email, phone, active, archive, created)
                VALUES (?::uuid, 'Automation', 'Buyer', ?, '9000000000', true, false, now())
                """, uuid, "pay-" + uuid.substring(0, 8) + "@automation.veloria.test");
        users.add(uuid);
        return uuid;
    }

    private String newSession(String userUuid) {
        String t = "pay-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO client_session (token, user_id, name, email, phone, expiry, access_expiry)
                VALUES (?, ?, 'Automation Buyer', ?, '9000000000',
                        timezone('UTC', now()) + interval '1 hour',
                        timezone('UTC', now()) + interval '1 hour')
                """, t, userUuid, "pay-" + userUuid.substring(0, 8) + "@automation.veloria.test");
        return t;
    }

    /** An order for one unit, with the approved example's money on it. */
    private CustomerOrderEntity seedOrder(String ownerUuid) {
        UUID productUuid = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_product (uuid, name, sku_id, price, selling_price, price_currency,
                                               initial_stock, hsn_code, archive, created)
                VALUES (?::uuid, 'Automation Payment Product', ?, 1000000, 1000000, 'INR', 5, '6211', false, now())
                """, productUuid.toString(), "AUTO-PAY-" + productUuid.toString().substring(0, 8));
        Long productId = jdbc.queryForObject(
                "SELECT id FROM inventory_product WHERE uuid = ?::uuid", Long.class, productUuid.toString());
        productIds.add(productId);

        String code = "VO-PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        // Product 10,000.00 + GST 1,800.00 = total_value 11,800.00; transport 500.00.
        jdbc.update("""
                INSERT INTO customer_order (uuid, order_code, customer_id, customer_name, customer_email,
                                            delivery_location, currency, total_value, taxable_value,
                                            cgst_amount, sgst_amount, igst_amount, total_tax_amount,
                                            shipping_value, cod_fee_paise, status, order_placed_at, active, archive)
                VALUES (gen_random_uuid(), ?, ?, 'Automation Buyer', ?, '12 MG Road, Bengaluru', 'INR',
                        1180000, 1000000, 90000, 90000, 0, 180000, 50000, 0,
                        'ORDER_PLACED', timezone('UTC', now()), true, false)
                """, code, ownerUuid, "pay-" + ownerUuid.substring(0, 8) + "@automation.veloria.test");
        Long orderId = jdbc.queryForObject("SELECT id FROM customer_order WHERE order_code = ?", Long.class, code);
        jdbc.update("""
                INSERT INTO customer_order_item (uuid, customer_order_id, product_uuid, quantity, archive)
                VALUES (gen_random_uuid(), ?, ?::uuid, 1, false)
                """, orderId, productUuid.toString());
        orderCodes.add(code);
        return orderRepo.findByOrderCodeAndArchiveFalse(code).orElseThrow();
    }

    @AfterEach
    void cleanUp() {
        for (String code : orderCodes) {
            Long id = jdbc.query("SELECT id FROM customer_order WHERE order_code = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, code);
            if (id == null) continue;
            jdbc.update("DELETE FROM payment_refund WHERE customer_order_id = ?", id);
            jdbc.update("DELETE FROM payment_attempt WHERE customer_order_id = ?", id);
            jdbc.update("""
                    DELETE FROM journal_entry_line WHERE journal_entry_id IN (
                        SELECT r.id FROM journal_entry r WHERE r.reverses_journal_id IN (
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
        for (Long pid : productIds) jdbc.update("DELETE FROM inventory_product WHERE id = ?", pid);
        productIds.clear();
        for (String u : users) {
            jdbc.update("DELETE FROM client_session WHERE user_id = ?", u);
            jdbc.update("DELETE FROM users WHERE uuid = ?::uuid", u);
        }
        users.clear();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private long availableStock(Long productId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(ip.initial_stock, 0)
                     - COALESCE((SELECT SUM(coi.quantity) FROM customer_order_item coi
                                  JOIN customer_order co ON co.id = coi.customer_order_id AND co.archive = false
                                 WHERE coi.product_uuid = ip.uuid AND coi.archive = false
                                   AND co.status IN (%s)), 0)
                  FROM inventory_product ip WHERE ip.id = ?
                """.formatted(com.app.master.service.core.order.OrderStatus.CONSUMING_SQL),
                Long.class, productId);
    }

    private String statusOf(String orderCode) {
        return jdbc.queryForObject("SELECT status FROM customer_order WHERE order_code = ?", String.class, orderCode);
    }

    private long attemptsFor(Long orderId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment_attempt WHERE customer_order_id = ?", Long.class, orderId);
    }

    // ── the amount is the server's ───────────────────────────────────────────

    @Test
    @DisplayName("COD payable is the invoice plus the approved handling charge, computed by the server")
    void codPayableIsServerComputed() throws Exception {
        CustomerOrderEntity order = seedOrder(buyerUuid);

        PaymentService.CheckoutSession s = paymentService.initiate(
                buyerToken, order.getOrderCode(), "COD", UUID.randomUUID().toString());

        // 10,000 product + 1,800 GST + 500 transport + 50 COD fee = 12,350.00
        assertEquals(1235000, s.amountPaise(), "server computes product + GST + transport + COD fee");
        assertTrue(s.cashOnDelivery(), "COD must not open a gateway checkout");
        assertNull(s.razorpayOrderId(), "and must not create a Razorpay order");
        assertEquals(5000L, orderRepo.findById(order.getId()).orElseThrow().getCodFeePaise());
    }

    @Test
    @DisplayName("a partial payment asks for half the invoice, to the paise")
    void partialIsHalfTheInvoice() throws Exception {
        CustomerOrderEntity order = seedOrder(buyerUuid);

        // quote() asks what the payment would be without creating anything, so
        // the arithmetic is checked without a gateway round trip.
        long payable = paymentService.quote(buyerToken, order.getOrderCode(), "ONLINE_PARTIAL");

        // 10,000 + 1,800 + 500 = 12,300.00 invoice; half is 6,150.00.
        assertEquals(615000, payable);

        // And the full-payment quote is the whole invoice.
        assertEquals(1230000, paymentService.quote(buyerToken, order.getOrderCode(), "ONLINE_FULL"));
    }

    @Test
    @DisplayName("a customer cannot pay for another customer's order")
    void cannotPayForSomeoneElsesOrder() {
        CustomerOrderEntity mine = seedOrder(buyerUuid);

        VeloriaException e = assertThrows(VeloriaException.class, () -> paymentService.initiate(
                otherToken, mine.getOrderCode(), "COD", UUID.randomUUID().toString()));

        // Indistinguishable from an order that does not exist: a refusal must
        // not confirm that someone else's order is real.
        assertTrue(e.getMessage().contains("not found"), e.getMessage());
        assertEquals(0, attemptsFor(mine.getId()), "and nothing may be created");
    }

    @Test
    @DisplayName("an expired session cannot start a payment")
    void expiredSessionRefused() {
        CustomerOrderEntity order = seedOrder(buyerUuid);
        assertThrows(VeloriaException.class, () -> paymentService.initiate(
                "never-issued-" + UUID.randomUUID(), order.getOrderCode(), "COD", UUID.randomUUID().toString()));
        assertEquals(0, attemptsFor(order.getId()));
    }

    @Test
    @DisplayName("a cancelled order cannot be paid for")
    void cancelledOrderCannotBePaid() throws Exception {
        CustomerOrderEntity order = seedOrder(buyerUuid);
        orderService.cancelAs(order.getOrderCode(), CancellationActor.CUSTOMER,
                CancellationReason.CUSTOMER_REQUEST, null, buyerUuid);

        VeloriaException e = assertThrows(VeloriaException.class, () -> paymentService.initiate(
                buyerToken, order.getOrderCode(), "COD", UUID.randomUUID().toString()));
        assertTrue(e.getMessage().toLowerCase().contains("no longer active"), e.getMessage());
    }

    // ── idempotency ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the same idempotency key returns the same payment, never a second one")
    void repeatedInitiationIsIdempotent() throws Exception {
        CustomerOrderEntity order = seedOrder(buyerUuid);
        String key = UUID.randomUUID().toString();

        PaymentService.CheckoutSession first = paymentService.initiate(
                buyerToken, order.getOrderCode(), "COD", key);
        for (int i = 0; i < 4; i++) {
            PaymentService.CheckoutSession again = paymentService.initiate(
                    buyerToken, order.getOrderCode(), "COD", key);
            assertEquals(first.attemptUuid(), again.attemptUuid());
            assertEquals(first.amountPaise(), again.amountPaise());
        }
        assertEquals(1, attemptsFor(order.getId()), "one key, one attempt");
    }

    @Test
    @DisplayName("20 simultaneous initiations with one key create exactly one payment")
    void concurrentInitiationCreatesOneAttempt() throws Exception {
        final int threads = 20;
        CustomerOrderEntity order = seedOrder(buyerUuid);
        String key = UUID.randomUUID().toString();

        List<String> ids = Collections.synchronizedList(new ArrayList<>());
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        ids.add(paymentService.initiate(buyerToken, order.getOrderCode(), "COD", key).attemptUuid());
                    } catch (Exception e) {
                        failures.add(e.getMessage());
                    } finally { done.countDown(); }
                });
            }
            start.countDown();
            assertTrue(done.await(120, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }

        assertEquals(1, attemptsFor(order.getId()),
                "the unique index must permit exactly one attempt; got " + attemptsFor(order.getId()));
        assertEquals(1, new java.util.HashSet<>(ids).size(), "every caller gets the same payment: " + ids);
        assertTrue(failures.isEmpty(), "no caller should error: " + failures);
    }

    @Test
    @DisplayName("a second payment cannot be opened while one is still live for the order")
    void oneLivePaymentPerOrder() throws Exception {
        CustomerOrderEntity order = seedOrder(buyerUuid);
        paymentService.initiate(buyerToken, order.getOrderCode(), "COD", UUID.randomUUID().toString());

        VeloriaException e = assertThrows(VeloriaException.class, () -> paymentService.initiate(
                buyerToken, order.getOrderCode(), "COD", UUID.randomUUID().toString()));
        assertTrue(e.getMessage().toLowerCase().contains("already in progress"), e.getMessage());
        assertEquals(1, attemptsFor(order.getId()));
    }

    // ── payment failure releases inventory exactly once ──────────────────────

    @Test
    @DisplayName("a system cancellation for payment failure releases stock exactly once")
    void paymentFailureReleasesStockOnce() throws Exception {
        CustomerOrderEntity order = seedOrder(buyerUuid);
        Long productId = productIds.get(productIds.size() - 1);

        assertEquals(4, availableStock(productId), "the order holds one of five units");

        orderService.cancelAs(order.getOrderCode(), CancellationActor.SYSTEM,
                CancellationReason.PAYMENT_FAILED, null, null);

        assertEquals(OrderStatus.CANCELLED.name(), statusOf(order.getOrderCode()));
        assertEquals(5, availableStock(productId), "the unit comes back");
        assertEquals("SYSTEM", jdbc.queryForObject(
                "SELECT cancelled_by FROM customer_order WHERE order_code = ?", String.class, order.getOrderCode()));
        assertNotNull(jdbc.queryForObject(
                "SELECT cancelled_at FROM customer_order WHERE order_code = ?", java.sql.Timestamp.class,
                order.getOrderCode()), "when it was cancelled must be recorded");

        // Repeats — a duplicate callback, a redelivered webhook — change nothing.
        for (int i = 0; i < 5; i++) {
            orderService.cancelAs(order.getOrderCode(), CancellationActor.SYSTEM,
                    CancellationReason.PAYMENT_FAILED, null, null);
        }
        assertEquals(5, availableStock(productId), "stock must not be released again");
    }

    @Test
    @DisplayName("12 concurrent payment-failure cancellations release stock once")
    void concurrentFailureCancellationsReleaseOnce() throws Exception {
        final int threads = 12;
        CustomerOrderEntity order = seedOrder(buyerUuid);
        Long productId = productIds.get(productIds.size() - 1);

        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        orderService.cancelAs(order.getOrderCode(), CancellationActor.SYSTEM,
                                CancellationReason.PAYMENT_FAILED, null, null);
                    } catch (Exception e) {
                        failures.add(e.getMessage());
                    } finally { done.countDown(); }
                });
            }
            start.countDown();
            assertTrue(done.await(120, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }

        assertTrue(failures.isEmpty(), "a repeated cancellation is not an error: " + failures);
        assertEquals(5, availableStock(productId), "exactly one release");
        assertEquals(OrderStatus.CANCELLED.name(), statusOf(order.getOrderCode()));
    }

    @Test
    @DisplayName("a failed payment creates no sale revenue")
    void failedPaymentCreatesNoSale() throws Exception {
        CustomerOrderEntity order = seedOrder(buyerUuid);
        orderService.cancelAs(order.getOrderCode(), CancellationActor.SYSTEM,
                CancellationReason.PAYMENT_FAILED, null, null);

        assertEquals(0, (long) jdbc.queryForObject(
                "SELECT count(*) FROM journal_entry WHERE source_type='SALE' AND source_id=?",
                Long.class, order.getId()), "PAYMENT_FAILED is not a sale");
    }

    // ── the cancellation matrix ──────────────────────────────────────────────

    @Test
    @DisplayName("the approved cancellation matrix is enforced for every actor")
    void cancellationMatrix() throws Exception {
        // Customer: allowed up to dispatch, refused once out for delivery.
        assertCancellable(CancellationActor.CUSTOMER, "PACKED", true);
        assertCancellable(CancellationActor.CUSTOMER, "IN_TRANSIT", true);
        assertCancellable(CancellationActor.CUSTOMER, "OUT_FOR_DELIVERY", false);
        assertCancellable(CancellationActor.CUSTOMER, "DELIVERED", false);

        // Admin: also allowed at the door, never after delivery.
        assertCancellable(CancellationActor.ADMIN, "PACKED", true);
        assertCancellable(CancellationActor.ADMIN, "IN_TRANSIT", true);
        assertCancellable(CancellationActor.ADMIN, "OUT_FOR_DELIVERY", true);
        assertCancellable(CancellationActor.ADMIN, "DELIVERED", false);

        // Delivery partner: only at the door.
        assertCancellable(CancellationActor.DELIVERY_PARTNER, "PACKED", false);
        assertCancellable(CancellationActor.DELIVERY_PARTNER, "OUT_FOR_DELIVERY", true);
        assertCancellable(CancellationActor.DELIVERY_PARTNER, "DELIVERED", false);
    }

    private void assertCancellable(CancellationActor actor, String status, boolean expected) throws Exception {
        CustomerOrderEntity order = seedOrder(buyerUuid);
        jdbc.update("UPDATE customer_order SET status = ? WHERE id = ?", status, order.getId());

        if (expected) {
            orderService.cancelAs(order.getOrderCode(), actor,
                    CancellationReason.OPERATIONAL_ISSUE, null, buyerUuid);
            assertEquals("CANCELLED", statusOf(order.getOrderCode()),
                    actor + " should be able to cancel from " + status);
            assertEquals(actor.name(), jdbc.queryForObject(
                    "SELECT cancelled_by FROM customer_order WHERE order_code = ?",
                    String.class, order.getOrderCode()));
        } else {
            assertThrows(VeloriaException.class, () -> orderService.cancelAs(
                    order.getOrderCode(), actor, CancellationReason.OPERATIONAL_ISSUE, null, buyerUuid),
                    actor + " must not be able to cancel from " + status);
            assertEquals(status, statusOf(order.getOrderCode()), "and the order must be untouched");
        }
    }

    @Test
    @DisplayName("a customer cannot cancel another customer's order")
    void customerCannotCancelSomeoneElsesOrder() {
        CustomerOrderEntity mine = seedOrder(buyerUuid);

        assertThrows(VeloriaException.class, () -> orderService.cancelAs(
                mine.getOrderCode(), CancellationActor.CUSTOMER, CancellationReason.CUSTOMER_REQUEST,
                null, otherUuid));

        assertEquals("ORDER_PLACED", statusOf(mine.getOrderCode()));
    }

    @Test
    @DisplayName("an operational cancellation must say why")
    void reasonIsMandatoryForOperationalActors() {
        CustomerOrderEntity order = seedOrder(buyerUuid);
        for (CancellationActor actor : new CancellationActor[]{
                CancellationActor.ADMIN, CancellationActor.DELIVERY_PARTNER, CancellationActor.SYSTEM}) {
            VeloriaException e = assertThrows(VeloriaException.class, () -> orderService.cancelAs(
                    order.getOrderCode(), actor, null, null, null));
            assertTrue(e.getMessage().toLowerCase().contains("reason"), e.getMessage());
        }
        assertEquals("ORDER_PLACED", statusOf(order.getOrderCode()));
    }
}
