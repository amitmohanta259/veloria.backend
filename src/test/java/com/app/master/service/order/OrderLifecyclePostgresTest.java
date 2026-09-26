package com.app.master.service.order;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.service.admin.SalesOrderService;
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
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The order lifecycle against real PostgreSQL: what each status does to stock,
 * and what happens when two operators act at once.
 *
 * <p>Real PostgreSQL because the guarantees under test are PostgreSQL's:
 * {@code SELECT … FOR UPDATE} making the second transition wait, and the
 * derived stock query itself. H2 would not exercise either.
 *
 * <p>Not {@code @Transactional}: worker threads must see each other's commits.
 *
 * <p>Every method carries the administrator authority, because the service now
 * refuses transitions without it. {@link OrderLifecycleAuthorizationTest} is
 * where the absence of that authority is tested.
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
class OrderLifecyclePostgresTest {

    @Autowired private SalesOrderService salesOrderService;
    @Autowired private com.app.master.service.repository.admin.InventoryProductRepository productRepository;
    @Autowired private JdbcTemplate jdbc;

    private String buyerUuid;
    private String buyerEmail;
    private final List<String> orderCodes = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();

    // ── fixture ──────────────────────────────────────────────────────────────

    @BeforeEach
    void seedBuyer() {
        buyerUuid = UUID.randomUUID().toString();
        buyerEmail = "lifecycle-" + buyerUuid.substring(0, 8) + "@automation.veloria.test";
        jdbc.update("""
                INSERT INTO users (uuid, first_name, last_name, email, phone, active, archive, created)
                VALUES (?::uuid, 'Automation', 'Buyer', ?, '9000000000', true, false, now())
                """, buyerUuid, buyerEmail);
    }

    /** A product with the given stock and no orders against it. */
    private UUID seedProduct(long stock) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_product (uuid, name, sku_id, price, selling_price, price_currency,
                                               initial_stock, hsn_code, archive, created)
                VALUES (?::uuid, 'Automation Lifecycle Product', ?, 100000, 100000, 'INR', ?, '6211', false, now())
                """, uuid.toString(), "AUTO-LC-" + uuid.toString().substring(0, 8), stock);
        productIds.add(jdbc.queryForObject(
                "SELECT id FROM inventory_product WHERE uuid = ?::uuid", Long.class, uuid.toString()));
        return uuid;
    }

    /** An order for one unit of the product, in the given starting status. */
    private String seedOrder(UUID productUuid, String status, int quantity) {
        String code = "VO-LC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("""
                INSERT INTO customer_order (uuid, order_code, customer_id, customer_email, delivery_location,
                                            currency, total_value, status, order_placed_at, active, archive)
                VALUES (gen_random_uuid(), ?, ?, ?, '12 MG Road, Bengaluru', 'INR', 100000, ?,
                        timezone('UTC', now()), true, false)
                """, code, buyerUuid, buyerEmail, status);
        Long orderId = jdbc.queryForObject(
                "SELECT id FROM customer_order WHERE order_code = ?", Long.class, code);
        jdbc.update("""
                INSERT INTO customer_order_item (uuid, customer_order_id, product_uuid, quantity, archive)
                VALUES (gen_random_uuid(), ?, ?::uuid, ?, false)
                """, orderId, productUuid.toString(), quantity);
        orderCodes.add(code);
        return code;
    }

    @AfterEach
    void cleanUp() {
        for (String code : orderCodes) {
            Long id = jdbc.query("SELECT id FROM customer_order WHERE order_code = ?",
                    rs -> rs.next() ? rs.getLong(1) : null, code);
            if (id == null) continue;
            jdbc.update("DELETE FROM customer_order_item WHERE customer_order_id = ?", id);
            jdbc.update("DELETE FROM customer_order WHERE id = ?", id);
        }
        orderCodes.clear();
        for (Long pid : productIds) {
            jdbc.update("DELETE FROM inventory_product_size_stock WHERE product_id = ?", pid);
            jdbc.update("DELETE FROM inventory_product WHERE id = ?", pid);
        }
        productIds.clear();
        jdbc.update("DELETE FROM users WHERE uuid = ?::uuid", buyerUuid);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Availability from the production query itself — the one checkout calls —
     * rather than a copy of it, so these assertions cannot pass against a
     * private re-statement of the rule.
     */
    private long available(UUID productUuid) {
        Long id = jdbc.queryForObject(
                "SELECT id FROM inventory_product WHERE uuid = ?::uuid", Long.class, productUuid.toString());
        return productRepository.availableStock(id);
    }

    private String statusOf(String orderCode) {
        return jdbc.queryForObject("SELECT status FROM customer_order WHERE order_code = ?", String.class, orderCode);
    }

    // ── stock across the lifecycle ───────────────────────────────────────────

    @Test
    @DisplayName("the delivery journey never releases stock — the P0-4 defect")
    void deliveryJourneyHoldsStock() throws Exception {
        UUID p = seedProduct(10);
        String code = seedOrder(p, "ORDER_PLACED", 1);

        assertEquals(9, available(p), "placing the order consumes the unit");

        salesOrderService.updateOrderStatus(code, "PACKED");
        assertEquals(9, available(p), "packing must not return it to the shelf");

        salesOrderService.updateOrderStatus(code, "IN_TRANSIT");
        assertEquals(9, available(p), "in transit must not return it to the shelf");

        // The specific defect: before P0-5A this status was missing from the
        // consuming set, so the unit became sellable again while on the van.
        salesOrderService.updateOrderStatus(code, "OUT_FOR_DELIVERY");
        assertEquals(9, available(p), "goods on a van are not available to sell");

        salesOrderService.updateOrderStatus(code, "DELIVERED");
        assertEquals(9, available(p), "delivery must not deduct the same unit a second time");
    }

    @Test
    @DisplayName("the return journey holds stock until the goods are actually handled back in")
    void returnJourneyHoldsStockUntilReceived() throws Exception {
        UUID p = seedProduct(5);
        String code = seedOrder(p, "DELIVERED", 1);
        assertEquals(4, available(p));

        salesOrderService.updateOrderStatus(code, "READY_TO_PICKUP");
        assertEquals(4, available(p), "a requested return is still in the customer's house");

        salesOrderService.updateOrderStatus(code, "PICKED_UP");
        assertEquals(4, available(p), "collected from the customer, not yet at the warehouse");

        salesOrderService.updateOrderStatus(code, "IN_TRANSIT_TO_SELLER");
        assertEquals(4, available(p), "in transit back is not on the shelf");

        salesOrderService.updateOrderStatus(code, "RECEIVED");
        assertEquals(4, available(p), "arrived but not yet inspected");

        salesOrderService.updateOrderStatus(code, "RETURNED");
        assertEquals(4, available(p), "still not sellable until a condition is recorded");

        // Inspection is what puts it back — the same field the returns flow writes.
        jdbc.update("""
                UPDATE customer_order_item SET return_condition = 'PRODUCT_OK'
                 WHERE customer_order_id = (SELECT id FROM customer_order WHERE order_code = ?)
                """, code);
        assertEquals(5, available(p), "inspected and resellable: back on the shelf, exactly once");
    }

    @Test
    @DisplayName("merely asking for a return does not put the goods back on sale")
    void returnRequestDoesNotReleaseStock() {
        UUID p = seedProduct(1);
        String code = seedOrder(p, "DELIVERED", 1);
        assertEquals(0, available(p));

        // This is what the client return endpoint writes, on every line, the
        // instant a customer asks. It used to both stop the line counting as
        // sold and credit it back, so one unit became two.
        jdbc.update("""
                UPDATE customer_order_item SET reason_for_return = 'Wrong size'
                 WHERE customer_order_id = (SELECT id FROM customer_order WHERE order_code = ?)
                """, code);

        assertEquals(0, available(p),
                "the customer still has the item; it must not be sellable, and certainly not twice");
    }

    @Test
    @DisplayName("cancellation releases the stock exactly once, however many times it is called")
    void cancellationReleasesOnce() throws Exception {
        UUID p = seedProduct(3);
        String code = seedOrder(p, "ORDER_PLACED", 2);
        assertEquals(1, available(p), "two of three units are spoken for");

        salesOrderService.updateOrderStatus(code, "CANCELLED");
        assertEquals(3, available(p), "cancelling returns both units");

        // Repeats are safe and change nothing.
        salesOrderService.updateOrderStatus(code, "CANCELLED");
        salesOrderService.cancelOrder(code, "second attempt");
        assertEquals(3, available(p), "stock must not be released twice");
        assertEquals("CANCELLED", statusOf(code));
    }

    @Test
    @DisplayName("a cancelled order keeps the reason it was first cancelled with")
    void cancellationKeepsTheOriginalReason() throws Exception {
        UUID p = seedProduct(3);
        String code = seedOrder(p, "ORDER_PLACED", 1);

        salesOrderService.cancelOrder(code, "customer changed their mind");
        salesOrderService.cancelOrder(code, "overwritten?");

        assertEquals("customer changed their mind",
                jdbc.queryForObject("SELECT cancel_reason FROM customer_order WHERE order_code = ?",
                        String.class, code));
    }

    @Test
    @DisplayName("a failed checkout holds no stock")
    void paymentFailedHoldsNoStock() {
        UUID p = seedProduct(4);
        seedOrder(p, "PAYMENT_FAILED", 1);
        assertEquals(4, available(p), "nothing was ever shipped");
    }

    // ── transition rules, enforced by the server ─────────────────────────────

    @Test
    @DisplayName("an unknown status is refused")
    void unknownStatusRefused() {
        UUID p = seedProduct(2);
        String code = seedOrder(p, "ORDER_PLACED", 1);

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> salesOrderService.updateOrderStatus(code, "SHIPPED_TO_MARS"));
        assertTrue(e.getMessage().contains("Unknown order status"), e.getMessage());
        assertEquals("ORDER_PLACED", statusOf(code), "the order must be untouched");
    }

    @Test
    @DisplayName("a transition the business does not perform is refused")
    void invalidTransitionRefused() {
        UUID p = seedProduct(2);
        String code = seedOrder(p, "ORDER_PLACED", 1);

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> salesOrderService.updateOrderStatus(code, "DELIVERED"));
        assertTrue(e.getMessage().contains("cannot be moved to DELIVERED"), e.getMessage());
        assertTrue(e.getMessage().contains("Allowed:"), "the operator should be told what is possible");
        assertEquals("ORDER_PLACED", statusOf(code));
    }

    @Test
    @DisplayName("repeating a transition that already happened is safe")
    void duplicateTransitionIsSafe() throws Exception {
        UUID p = seedProduct(5);
        String code = seedOrder(p, "ORDER_PLACED", 1);

        salesOrderService.updateOrderStatus(code, "PACKED");
        salesOrderService.updateOrderStatus(code, "PACKED");
        salesOrderService.updateOrderStatus(code, "PACKED");

        assertEquals("PACKED", statusOf(code));
        assertEquals(4, available(p), "no repeat may move stock");
    }

    @Test
    @DisplayName("a cancelled order cannot be brought back to life")
    void cancelledCannotBeReactivated() throws Exception {
        UUID p = seedProduct(5);
        String code = seedOrder(p, "ORDER_PLACED", 1);
        salesOrderService.cancelOrder(code, "customer changed their mind");
        assertEquals(5, available(p));

        // Every route back in must be refused, including the plain status update
        // that previously bypassed cancellation entirely.
        for (String attempt : new String[]{"ORDER_PLACED", "PACKED", "IN_TRANSIT", "DELIVERED"}) {
            VeloriaException e = assertThrows(VeloriaException.class,
                    () -> salesOrderService.updateOrderStatus(code, attempt),
                    "CANCELLED → " + attempt + " must be refused");
            assertTrue(e.getMessage().contains("final"), e.getMessage());
        }
        assertEquals("CANCELLED", statusOf(code));
        assertEquals(5, available(p), "and the stock stays released");
    }

    @Test
    @DisplayName("an order that has shipped can no longer be cancelled")
    void deliveredOrderCannotBeCancelled() throws Exception {
        UUID p = seedProduct(5);
        String code = seedOrder(p, "ORDER_PLACED", 1);
        salesOrderService.updateOrderStatus(code, "PACKED");
        salesOrderService.updateOrderStatus(code, "IN_TRANSIT");

        // An administrator may now stop an order in transit — the approved
        // P0-7 policy widened the window past dispatch.
        salesOrderService.updateOrderStatus(code, "OUT_FOR_DELIVERY");
        salesOrderService.updateOrderStatus(code, "DELIVERED");

        // But never once it has been delivered: that is the return process.
        VeloriaException e = assertThrows(VeloriaException.class,
                () -> salesOrderService.cancelOrder(code, "too late"));
        assertTrue(e.getMessage().toLowerCase().contains("cannot be cancelled"), e.getMessage());
        assertEquals("DELIVERED", statusOf(code));
        assertEquals(4, available(p), "and its unit is still consumed");
    }

    @Test
    @DisplayName("an administrator can stop an order that has already shipped")
    void shippedOrderCanBeCancelledByAdmin() throws Exception {
        UUID p = seedProduct(5);
        String code = seedOrder(p, "ORDER_PLACED", 1);
        salesOrderService.updateOrderStatus(code, "PACKED");
        salesOrderService.updateOrderStatus(code, "IN_TRANSIT");
        assertEquals(4, available(p));

        salesOrderService.cancelOrder(code, "customer called to stop it");

        assertEquals("CANCELLED", statusOf(code));
        assertEquals(5, available(p), "the unit returns to availability");
    }

    // ── concurrency ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("two operators acting at once leave one order in one legal status")
    void concurrentConflictingTransitions() throws Exception {
        final int attempts = 12;
        UUID p = seedProduct(20);
        String code = seedOrder(p, "ORDER_PLACED", 1);

        List<String> won = Collections.synchronizedList(new ArrayList<>());
        List<String> refused = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(attempts);
        try {
            for (int i = 0; i < attempts; i++) {
                // Half try to pack it, half try to cancel it. Both are legal
                // from ORDER_PLACED, so only ordering decides — but they must
                // not both happen.
                final String target = (i % 2 == 0) ? "PACKED" : "CANCELLED";
                pool.submit(() -> {
                    try {
                        startLine.await();
                        runAsAdmin(() -> salesOrderService.updateOrderStatus(code, target));
                        won.add(target);
                    } catch (Exception e) {
                        refused.add(target + ": " + e.getMessage());
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

        String finalStatus = statusOf(code);
        assertFalse(won.isEmpty(), "at least one caller should have succeeded");

        // Both targets are legal from ORDER_PLACED, and PACKED → CANCELLED is
        // legal too, so a run where some callers packed and others then
        // cancelled is a correct history rather than a lost update. What must
        // never happen is the order landing somewhere the machine cannot reach.
        assertTrue(finalStatus.equals("PACKED") || finalStatus.equals("CANCELLED"),
                "the order settled on an unreachable status: " + finalStatus);

        // The safety property: once a cancellation commits, no later caller may
        // move the order off it. Before P0-5A an ordinary status update would
        // have walked straight over a cancellation.
        if (won.contains("CANCELLED")) {
            assertEquals("CANCELLED", finalStatus,
                    "a cancellation was accepted and then bypassed: " + won);
        }

        // And stock matches the outcome, with no half-applied middle ground.
        assertEquals("CANCELLED".equals(finalStatus) ? 20 : 19, available(p),
                "stock must reflect exactly the status that committed");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM customer_order WHERE order_code = ?", Long.class, code),
                "the order must still be a single row");
    }

    @Test
    @DisplayName("concurrent cancellations of one order release its stock once")
    void concurrentCancellationsReleaseOnce() throws Exception {
        final int attempts = 10;
        UUID p = seedProduct(6);
        String code = seedOrder(p, "ORDER_PLACED", 2);
        assertEquals(4, available(p));

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(attempts);
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    try {
                        startLine.await();
                        runAsAdmin(() -> salesOrderService.cancelOrder(code, "concurrent"));
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
        assertEquals("CANCELLED", statusOf(code));
        assertEquals(6, available(p), "the two units come back once, not ten times");
    }

    /** Runs work on a worker thread with the administrator authority the service requires. */
    private void runAsAdmin(ThrowingRunnable work) throws Exception {
        var ctx = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "automation-admin", "n/a",
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ADMIN_GST"))));
        org.springframework.security.core.context.SecurityContextHolder.setContext(ctx);
        try {
            work.run();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }
}
