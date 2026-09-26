package com.app.master.service.inventory;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.PlaceOrderRequest;
import com.app.master.service.service.client.ClientOrderService;
import com.app.master.service.support.AccountingResidue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checkout inventory against real PostgreSQL.
 *
 * Stock here is derived — {@code initial_stock − sold + returned}, summed over
 * {@code customer_order_item.quantity} — so placing the order *is* the
 * deduction. What these tests prove is that the claim is serialised: concurrent
 * buyers cannot both read the same availability and both commit an order.
 *
 * Deliberately not {@code @Transactional}: a test-owned transaction would hide
 * the production boundary and stop worker threads from seeing each other's
 * committed orders.
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
class CheckoutInventoryPostgresTest {

    private static final String DELIVERY = "12 MG Road, Bengaluru, Karnataka 560001";

    @Autowired private ClientOrderService orderService;
    @Autowired private JdbcTemplate jdbc;

    private String buyerUuid;
    private String buyerEmail;
    private String token;
    private final List<UUID> products = new ArrayList<>();

    // ── fixture ──────────────────────────────────────────────────────────────

    @BeforeEach
    void seedBuyer() {
        buyerUuid = UUID.randomUUID().toString();
        buyerEmail = "checkout-" + buyerUuid.substring(0, 8) + "@automation.veloria.test";
        jdbc.update("""
                INSERT INTO users (uuid, first_name, last_name, email, phone, active, archive, created)
                VALUES (?::uuid, 'Automation', 'Buyer', ?, '9000000000', true, false, now())
                """, buyerUuid, buyerEmail);
        token = newSession(buyerUuid, buyerEmail);
    }

    private String newSession(String userUuid, String email) {
        String t = "checkout-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO client_session (token, user_id, name, email, phone, expiry, access_expiry)
                VALUES (?, ?, 'Automation Buyer', ?, '9000000000',
                        timezone('UTC', now()) + interval '1 hour',
                        timezone('UTC', now()) + interval '1 hour')
                """, t, userUuid, email);
        return t;
    }

    /** A product with the given stock, tracked on one size row as the schema expects. */
    private UUID seedProduct(long stock) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_product (uuid, name, sku_id, price, selling_price, price_currency,
                                               initial_stock, hsn_code, archive, created)
                VALUES (?::uuid, ?, ?, 100000, 100000, 'INR', ?, '6211', false, now())
                """, uuid.toString(), "Automation Checkout Product",
                "AUTO-CHK-" + uuid.toString().substring(0, 8), stock);
        Long id = jdbc.queryForObject("SELECT id FROM inventory_product WHERE uuid = ?::uuid", Long.class, uuid.toString());
        jdbc.update("""
                INSERT INTO inventory_product_size_stock (product_id, size, initial_stock, archive)
                VALUES (?, 'M', ?, false)
                """, id, stock);
        products.add(uuid);
        return uuid;
    }

    @AfterEach
    void cleanUp() {
        for (String code : jdbc.queryForList(
                "SELECT order_code FROM customer_order WHERE customer_email = ?", String.class, buyerEmail)) {
            Long id = jdbc.queryForObject("SELECT id FROM customer_order WHERE order_code = ?", Long.class, code);
            // Orders now book a sale when they are created, a COD fee when cash on
            // delivery is chosen, a collection when they are paid and a refund when
            // money goes back. A test that places one must remove every journal it
            // caused or they are left orphaned.
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
        for (UUID p : products) {
            jdbc.update("DELETE FROM inventory_product_size_stock WHERE product_id IN "
                    + "(SELECT id FROM inventory_product WHERE uuid = ?::uuid)", p.toString());
            jdbc.update("DELETE FROM inventory_product WHERE uuid = ?::uuid", p.toString());
        }
        products.clear();
        jdbc.update("DELETE FROM client_session WHERE user_id = ?", buyerUuid);
        jdbc.update("DELETE FROM users WHERE uuid = ?::uuid", buyerUuid);

        // Fail here rather than let residue move the historical checksum.
        AccountingResidue.assertNone(jdbc);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private PlaceOrderRequest order(Map<UUID, Integer> lines) {
        PlaceOrderRequest r = new PlaceOrderRequest();
        r.setDeliveryLocation(DELIVERY);
        List<PlaceOrderRequest.OrderItemRequest> items = new ArrayList<>();
        lines.forEach((uuid, qty) -> {
            PlaceOrderRequest.OrderItemRequest i = new PlaceOrderRequest.OrderItemRequest();
            i.setProductUuid(uuid);
            i.setQuantity(qty);
            items.add(i);
        });
        r.setItems(items);
        return r;
    }

    /** available = initial_stock − sold + returned, summing units. */
    private long available(UUID productUuid) {
        return jdbc.queryForObject("""
                SELECT COALESCE(ip.initial_stock, 0)
                     - COALESCE((SELECT SUM(coi.quantity) FROM customer_order_item coi
                                  JOIN customer_order co ON co.id = coi.customer_order_id AND co.archive = false
                                 WHERE coi.product_uuid = ip.uuid AND coi.archive = false
                                   AND coi.reason_for_return IS NULL
                                   AND co.status IN ('ORDER_PLACED','PACKED','IN_TRANSIT','DISPATCHED','DONE','DELIVERED')), 0)
                     + COALESCE((SELECT SUM(coi.quantity) FROM customer_order_item coi
                                  JOIN customer_order co ON co.id = coi.customer_order_id AND co.archive = false
                                 WHERE coi.product_uuid = ip.uuid AND coi.archive = false
                                   AND (co.status = 'RETURNED' OR coi.reason_for_return IS NOT NULL)), 0)
                  FROM inventory_product ip WHERE ip.uuid = ?::uuid
                """, Long.class, productUuid.toString());
    }

    private long orderCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM customer_order WHERE customer_email = ?", Long.class, buyerEmail);
    }

    private long soldUnits(UUID productUuid) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(coi.quantity), 0) FROM customer_order_item coi
                  JOIN customer_order co ON co.id = coi.customer_order_id
                 WHERE coi.product_uuid = ?::uuid AND coi.archive = false
                """, Long.class, productUuid.toString());
    }

    // ── Tests 1-3, 9: sequential correctness ─────────────────────────────────

    @Test
    @DisplayName("1 — a single-item order deducts exactly what was ordered")
    void singleItemDeducts() throws Exception {
        UUID p = seedProduct(10);
        orderService.placeOrder(token, order(Map.of(p, 3)));
        assertEquals(7, available(p), "10 − 3");
        assertEquals(3, soldUnits(p));
    }

    @Test
    @DisplayName("2 — ordering more than is available is refused and changes nothing")
    void insufficientStockIsRefused() {
        UUID p = seedProduct(3);
        VeloriaException e = assertThrows(VeloriaException.class,
                () -> orderService.placeOrder(token, order(Map.of(p, 4))));
        assertTrue(e.getMessage().toLowerCase().contains("left of"), "message: " + e.getMessage());
        assertEquals(3, available(p), "stock must be untouched");
        assertEquals(0, orderCount(), "no order may exist");
    }

    @Test
    @DisplayName("3 — ordering exactly what is left succeeds and leaves zero, never negative")
    void exactStockIsAllowed() throws Exception {
        UUID p = seedProduct(5);
        orderService.placeOrder(token, order(Map.of(p, 5)));
        assertEquals(0, available(p));
        assertTrue(available(p) >= 0, "stock must never go negative");

        // And the next unit is refused.
        assertThrows(VeloriaException.class, () -> orderService.placeOrder(token, order(Map.of(p, 1))));
        assertEquals(0, available(p));
    }

    // ── Test 6 (sequential form) + multi-item atomicity ──────────────────────

    @Test
    @DisplayName("A multi-item order is all-or-nothing: one unavailable line blocks the whole order")
    void multiItemIsAtomic() {
        UUID a = seedProduct(10);
        UUID b = seedProduct(10);
        UUID c = seedProduct(1);          // not enough for the 2 requested

        Map<UUID, Integer> lines = new java.util.LinkedHashMap<>();
        lines.put(a, 2); lines.put(b, 3); lines.put(c, 2);

        assertThrows(VeloriaException.class, () -> orderService.placeOrder(token, order(lines)));

        assertEquals(10, available(a), "A must be untouched");
        assertEquals(10, available(b), "B must be untouched");
        assertEquals(1,  available(c), "C must be untouched");
        assertEquals(0, orderCount(), "no partial order may exist");
    }

    @Test
    @DisplayName("Two lines of the same product are checked against their combined quantity")
    void repeatedProductLinesAreSummed() {
        UUID p = seedProduct(5);
        PlaceOrderRequest r = new PlaceOrderRequest();
        r.setDeliveryLocation(DELIVERY);
        List<PlaceOrderRequest.OrderItemRequest> items = new ArrayList<>();
        for (int qty : new int[]{3, 3}) {          // 6 in total against 5 available
            PlaceOrderRequest.OrderItemRequest i = new PlaceOrderRequest.OrderItemRequest();
            i.setProductUuid(p); i.setQuantity(qty); items.add(i);
        }
        r.setItems(items);

        assertThrows(VeloriaException.class, () -> orderService.placeOrder(token, r));
        assertEquals(5, available(p));
        assertEquals(0, orderCount());
    }

    // ── Test 4: the last unit ────────────────────────────────────────────────

    @Test
    @DisplayName("4 — eight concurrent buyers, one unit: exactly one order, stock 0, never negative")
    void concurrentLastUnit() throws Exception {
        UUID p = seedProduct(1);
        int buyers = 8;

        Result r = runConcurrently(buyers, i -> order(Map.of(p, 1)));

        assertEquals(1, r.succeeded.get(), "exactly one buyer may win the last unit");
        assertEquals(buyers - 1, r.failed.get(), "everyone else must be refused");
        assertEquals(0, available(p), "the unit is gone");
        assertTrue(available(p) >= 0, "stock must never go negative");
        assertEquals(1, orderCount(), "exactly one order committed");
        assertEquals(1, soldUnits(p));
    }

    // ── Test 5: mixed quantities ─────────────────────────────────────────────

    @Test
    @DisplayName("5 — concurrent buyers of 2,3,4,5 against 10: sold never exceeds stock")
    void concurrentMultiQuantity() throws Exception {
        UUID p = seedProduct(10);
        int[] quantities = {2, 3, 4, 5};

        Result r = runConcurrently(quantities.length, i -> order(Map.of(p, quantities[i])));

        long sold = soldUnits(p);
        assertTrue(sold <= 10, "sold " + sold + " must never exceed the 10 available");
        assertEquals(10 - sold, available(p), "available must be initial minus sold");
        assertTrue(available(p) >= 0, "never negative");
        assertEquals(r.succeeded.get(), orderCount(), "one committed order per success");
    }

    // ── Test 6: opposing multi-item orders (deadlock) ────────────────────────

    @Test
    @DisplayName("6 — opposing multi-item orders do not deadlock; each succeeds or fails whole")
    void concurrentOpposingMultiItemOrders() throws Exception {
        UUID one = seedProduct(20);
        UUID two = seedProduct(20);
        int pairs = 12;

        // Half request (one, two); half request (two, one). The service sorts by
        // product id, so both acquire locks in the same sequence.
        Result r = runConcurrently(pairs, i -> {
            Map<UUID, Integer> lines = new java.util.LinkedHashMap<>();
            if (i % 2 == 0) { lines.put(one, 1); lines.put(two, 1); }
            else            { lines.put(two, 1); lines.put(one, 1); }
            return order(lines);
        });

        assertEquals(0, r.deadlocks.get(), "no deadlock should surface: " + r.errors);
        assertEquals(pairs, r.succeeded.get(), "there is enough stock for every order");
        assertEquals(20 - pairs, available(one));
        assertEquals(20 - pairs, available(two));
        assertEquals(pairs, orderCount());
    }

    // ── Test 10: history matches what was sold ───────────────────────────────

    @Test
    @DisplayName("10 — the order items that record the sale account for exactly the stock consumed")
    void orderItemsAccountForStockConsumed() throws Exception {
        UUID p = seedProduct(10);
        orderService.placeOrder(token, order(Map.of(p, 2)));
        orderService.placeOrder(token, order(Map.of(p, 3)));

        assertEquals(5, soldUnits(p), "the sale history is the order items themselves");
        assertEquals(10 - soldUnits(p), available(p), "available is initial minus that history");
        assertEquals(2, orderCount());
    }

    // ── concurrency harness ──────────────────────────────────────────────────

    private record Result(AtomicInteger succeeded, AtomicInteger failed,
                          AtomicInteger deadlocks, List<String> errors) {}

    private Result runConcurrently(int n, java.util.function.IntFunction<PlaceOrderRequest> build)
            throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger ok = new AtomicInteger(), bad = new AtomicInteger(), dead = new AtomicInteger();
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());

        try {
            for (int i = 0; i < n; i++) {
                final int idx = i;
                pool.submit(() -> {
                    // A session per worker: concurrent buyers, not one buyer racing itself.
                    String workerToken = newSession(buyerUuid, buyerEmail);
                    try {
                        ready.countDown();
                        start.await();
                        orderService.placeOrder(workerToken, build.apply(idx));
                        ok.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        bad.incrementAndGet();
                        String msg = String.valueOf(e.getMessage());
                        errors.add(msg);
                        if (msg.toLowerCase().contains("deadlock")) dead.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(60, TimeUnit.SECONDS), "workers must reach the start line");
            start.countDown();
            assertTrue(done.await(120, TimeUnit.SECONDS), "workers must finish");
        } finally {
            pool.shutdownNow();
        }
        return new Result(ok, bad, dead, errors);
    }
}
