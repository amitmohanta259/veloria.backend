package com.app.master.service.order;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.client.PlaceOrderRequest;
import com.app.master.service.core.response.client.PlaceOrderResponse;
import com.app.master.service.service.client.ClientOrderIdempotencyService;
import com.app.master.service.support.AccountingResidue;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checkout idempotency against real PostgreSQL.
 *
 * <p>The invariant under test is <b>one idempotent checkout = one committed
 * order = one inventory deduction</b>. Because stock here is derived from
 * committed order items, a duplicate order is not a cosmetic problem: it sells
 * units twice.
 *
 * <p>Real PostgreSQL, deliberately. H2 does not implement the partial unique
 * index, the row-level {@code FOR UPDATE} waits or the READ COMMITTED snapshot
 * behaviour that this protection is actually built on, so it could not tell a
 * working implementation from a broken one.
 *
 * <p>Not {@code @Transactional}: a test-owned transaction would hide the
 * production boundary and stop worker threads from seeing each other's
 * committed orders — which is the entire mechanism being verified.
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
class OrderIdempotencyPostgresTest {

    private static final String DELIVERY = "12 MG Road, Bengaluru, Karnataka 560001";

    @Autowired private ClientOrderIdempotencyService checkout;
    @Autowired private JdbcTemplate jdbc;

    private String buyerUuid;
    private String buyerEmail;
    private String token;
    private final List<String> extraBuyers = new ArrayList<>();
    private final List<UUID> products = new ArrayList<>();

    // ── fixture ──────────────────────────────────────────────────────────────

    @BeforeEach
    void seedBuyer() {
        buyerUuid = UUID.randomUUID().toString();
        buyerEmail = "idem-" + buyerUuid.substring(0, 8) + "@automation.veloria.test";
        insertUser(buyerUuid, buyerEmail);
        token = newSession(buyerUuid, buyerEmail);
    }

    private void insertUser(String uuid, String email) {
        jdbc.update("""
                INSERT INTO users (uuid, first_name, last_name, email, phone, active, archive, created)
                VALUES (?::uuid, 'Automation', 'Buyer', ?, '9000000000', true, false, now())
                """, uuid, email);
    }

    private String newSession(String userUuid, String email) {
        String t = "idem-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO client_session (token, user_id, name, email, phone, expiry, access_expiry)
                VALUES (?, ?, 'Automation Buyer', ?, '9000000000',
                        timezone('UTC', now()) + interval '1 hour',
                        timezone('UTC', now()) + interval '1 hour')
                """, t, userUuid, email);
        return t;
    }

    /** A second, unrelated customer with their own live session. */
    private String otherCustomerToken() {
        String uuid = UUID.randomUUID().toString();
        String email = "idem-other-" + uuid.substring(0, 8) + "@automation.veloria.test";
        insertUser(uuid, email);
        extraBuyers.add(uuid);
        return newSession(uuid, email);
    }

    private UUID seedProduct(long stock) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_product (uuid, name, sku_id, price, selling_price, price_currency,
                                               initial_stock, hsn_code, archive, created)
                VALUES (?::uuid, ?, ?, 100000, 100000, 'INR', ?, '6211', false, now())
                """, uuid.toString(), "Automation Idempotency Product",
                "AUTO-IDM-" + uuid.toString().substring(0, 8), stock);
        Long id = jdbc.queryForObject(
                "SELECT id FROM inventory_product WHERE uuid = ?::uuid", Long.class, uuid.toString());
        jdbc.update("""
                INSERT INTO inventory_product_size_stock (product_id, size, initial_stock, archive)
                VALUES (?, 'M', ?, false)
                """, id, stock);
        products.add(uuid);
        return uuid;
    }

    @AfterEach
    void cleanUp() {
        List<String> emails = new ArrayList<>(extraBuyers.stream()
                .map(u -> jdbc.queryForObject("SELECT email FROM users WHERE uuid = ?::uuid", String.class, u))
                .toList());
        emails.add(buyerEmail);

        for (String email : emails) {
            for (String code : jdbc.queryForList(
                    "SELECT order_code FROM customer_order WHERE customer_email = ?", String.class, email)) {
                Long id = jdbc.queryForObject(
                        "SELECT id FROM customer_order WHERE order_code = ?", Long.class, code);
                // Orders now book a sale when they are created, a COD fee when
                // cash on delivery is chosen, a collection when they are paid and
                // a refund when money goes back. A test that places one must
                // remove every journal it caused or they are left orphaned.
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
        }
        for (UUID p : products) {
            jdbc.update("DELETE FROM inventory_product_size_stock WHERE product_id IN "
                    + "(SELECT id FROM inventory_product WHERE uuid = ?::uuid)", p.toString());
            jdbc.update("DELETE FROM inventory_product WHERE uuid = ?::uuid", p.toString());
        }
        products.clear();
        for (String uuid : extraBuyers) {
            jdbc.update("DELETE FROM client_session WHERE user_id = ?", uuid);
            jdbc.update("DELETE FROM users WHERE uuid = ?::uuid", uuid);
        }
        extraBuyers.clear();
        jdbc.update("DELETE FROM client_session WHERE user_id = ?", buyerUuid);
        jdbc.update("DELETE FROM users WHERE uuid = ?::uuid", buyerUuid);

        // Fail here rather than let residue move the historical checksum.
        AccountingResidue.assertNone(jdbc);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private PlaceOrderRequest order(String reference, Map<UUID, Integer> lines) {
        PlaceOrderRequest r = new PlaceOrderRequest();
        r.setDeliveryLocation(DELIVERY);
        r.setClientOrderReference(reference);
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

    private long orderRowsFor(String reference) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM customer_order WHERE client_order_reference = ?", Long.class, reference);
    }

    private long soldUnits(UUID productUuid) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(coi.quantity), 0) FROM customer_order_item coi
                  JOIN customer_order co ON co.id = coi.customer_order_id AND co.archive = false
                 WHERE coi.product_uuid = ?::uuid AND coi.archive = false
                """, Long.class, productUuid.toString());
    }

    private static String reference() {
        return "chk-" + UUID.randomUUID();
    }

    // ── A: the ordinary retry ────────────────────────────────────────────────

    @Test
    @DisplayName("A — a retried checkout returns the first order and deducts stock once")
    void sequentialRetryIsIdempotent() throws Exception {
        UUID p = seedProduct(10);
        String ref = reference();

        PlaceOrderResponse first = checkout.placeOrder(token, order(ref, Map.of(p, 2)));
        PlaceOrderResponse retry = checkout.placeOrder(token, order(ref, Map.of(p, 2)));

        assertEquals(first.getOrderCode(), retry.getOrderCode(), "the retry must return the same order");
        assertEquals(first.getOrderUuid(), retry.getOrderUuid());
        assertEquals(1, orderRowsFor(ref), "one reference must mean one order row");
        assertEquals(2, soldUnits(p), "stock must be deducted once, not twice");
    }

    @Test
    @DisplayName("A2 — retrying many times still leaves one order")
    void repeatedRetriesStayIdempotent() throws Exception {
        UUID p = seedProduct(10);
        String ref = reference();

        String code = checkout.placeOrder(token, order(ref, Map.of(p, 1))).getOrderCode();
        for (int i = 0; i < 5; i++) {
            assertEquals(code, checkout.placeOrder(token, order(ref, Map.of(p, 1))).getOrderCode());
        }
        assertEquals(1, orderRowsFor(ref));
        assertEquals(1, soldUnits(p));
    }

    // ── B: the race ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("B — 20 simultaneous duplicate checkouts commit exactly one order and one deduction")
    void concurrentDuplicatesCommitOnce() throws Exception {
        final int attempts = 20;
        UUID p = seedProduct(50);           // ample stock, so only idempotency can limit the outcome
        String ref = reference();

        List<String> codes = Collections.synchronizedList(new ArrayList<>());
        List<String> failures = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(attempts);
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    try {
                        startLine.await();
                        codes.add(checkout.placeOrder(token, order(ref, Map.of(p, 1))).getOrderCode());
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

        assertEquals(1, orderRowsFor(ref),
                "exactly one order may exist for one reference; got " + orderRowsFor(ref));
        assertEquals(1, soldUnits(p), "exactly one unit may be sold; a duplicate order would sell more");
        assertTrue(failures.isEmpty(), "no caller should see an error: " + failures);

        Set<String> distinct = new java.util.HashSet<>(codes);
        assertEquals(attempts, codes.size(), "every attempt should have returned an order");
        assertEquals(1, distinct.size(), "every caller must be given the same order code: " + distinct);
    }

    @Test
    @DisplayName("B2 — the last unit sold concurrently under one reference yields one order, not an error")
    void concurrentDuplicatesOnTheLastUnit() throws Exception {
        final int attempts = 8;
        UUID p = seedProduct(1);            // the loser will hit the stock check, not the unique index
        String ref = reference();

        List<String> codes = Collections.synchronizedList(new ArrayList<>());
        List<String> failures = Collections.synchronizedList(new ArrayList<>());

        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(attempts);
        try {
            for (int i = 0; i < attempts; i++) {
                pool.submit(() -> {
                    try {
                        startLine.await();
                        codes.add(checkout.placeOrder(token, order(ref, Map.of(p, 1))).getOrderCode());
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

        assertEquals(1, orderRowsFor(ref), "one reference, one order");
        assertEquals(1, soldUnits(p), "the single unit must be sold once");
        // The point of this case: a duplicate of a checkout that already
        // succeeded must not be told the item is out of stock.
        assertTrue(failures.isEmpty(),
                "a duplicate of a successful checkout must not surface a stock error: " + failures);
        assertEquals(1, new java.util.HashSet<>(codes).size(), "all callers get the one order");
    }

    // ── C: distinct checkouts stay distinct ──────────────────────────────────

    @Test
    @DisplayName("C — different references create different orders")
    void differentReferencesCreateDifferentOrders() throws Exception {
        UUID p = seedProduct(10);

        PlaceOrderResponse one = checkout.placeOrder(token, order(reference(), Map.of(p, 1)));
        PlaceOrderResponse two = checkout.placeOrder(token, order(reference(), Map.of(p, 1)));

        assertNotEquals(one.getOrderCode(), two.getOrderCode(), "two checkouts are two orders");
        assertEquals(2, soldUnits(p), "each order deducts its own units");
    }

    @Test
    @DisplayName("C2 — a request without a reference is unaffected and still creates an order")
    void requestWithoutReferenceStillWorks() throws Exception {
        UUID p = seedProduct(10);

        PlaceOrderResponse one = checkout.placeOrder(token, order(null, Map.of(p, 1)));
        PlaceOrderResponse two = checkout.placeOrder(token, order(null, Map.of(p, 1)));

        assertNotEquals(one.getOrderCode(), two.getOrderCode(),
                "with no reference there is nothing to deduplicate, and behaviour must not change");
        assertEquals(2, soldUnits(p));
        assertEquals(2, (long) jdbc.queryForObject(
                "SELECT count(*) FROM customer_order WHERE customer_email = ? AND client_order_reference IS NULL",
                Long.class, buyerEmail), "the partial index must permit many reference-less rows");
    }

    // ── D: same reference, different cart ────────────────────────────────────

    @Test
    @DisplayName("D — reusing a reference for a different cart is refused, and the first order is untouched")
    void sameReferenceDifferentPayloadIsRefused() throws Exception {
        UUID p = seedProduct(10);
        UUID other = seedProduct(10);
        String ref = reference();

        PlaceOrderResponse first = checkout.placeOrder(token, order(ref, Map.of(p, 1)));

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> checkout.placeOrder(token, order(ref, Map.of(other, 1))),
                "a different cart under the same reference must not be accepted");
        assertTrue(e.getMessage().toLowerCase().contains("different order"), "message: " + e.getMessage());

        assertEquals(1, orderRowsFor(ref), "no second order may be created");
        assertEquals(1, soldUnits(p), "the first order stands unchanged");
        assertEquals(0, soldUnits(other), "and the other product was never sold");
        assertEquals(first.getOrderCode(), jdbc.queryForObject(
                "SELECT order_code FROM customer_order WHERE client_order_reference = ?", String.class, ref));
    }

    @Test
    @DisplayName("D2 — a changed quantity under the same reference is also refused")
    void sameReferenceChangedQuantityIsRefused() throws Exception {
        UUID p = seedProduct(10);
        String ref = reference();

        checkout.placeOrder(token, order(ref, Map.of(p, 1)));

        assertThrows(VeloriaException.class, () -> checkout.placeOrder(token, order(ref, Map.of(p, 3))),
                "quantity is part of what was ordered, so it is part of the fingerprint");
        assertEquals(1, soldUnits(p), "only the original unit is sold");
    }

    @Test
    @DisplayName("D3 — the same cart listed in a different order is still the same request")
    void itemOrderDoesNotChangeTheFingerprint() throws Exception {
        UUID a = seedProduct(10);
        UUID b = seedProduct(10);
        String ref = reference();

        PlaceOrderRequest forward = order(ref, Map.of());
        forward.setItems(List.of(line(a, 1), line(b, 2)));
        PlaceOrderRequest reversed = order(ref, Map.of());
        reversed.setItems(List.of(line(b, 2), line(a, 1)));

        String code = checkout.placeOrder(token, forward).getOrderCode();
        assertEquals(code, checkout.placeOrder(token, reversed).getOrderCode(),
                "line order is presentation, not intent");
        assertEquals(1, orderRowsFor(ref));
        assertEquals(1, soldUnits(a));
        assertEquals(2, soldUnits(b));
    }

    private static PlaceOrderRequest.OrderItemRequest line(UUID product, int qty) {
        PlaceOrderRequest.OrderItemRequest i = new PlaceOrderRequest.OrderItemRequest();
        i.setProductUuid(product);
        i.setQuantity(qty);
        return i;
    }

    // ── E: someone else's reference ──────────────────────────────────────────

    @Test
    @DisplayName("E — another customer presenting the same reference is refused and learns nothing")
    void otherCustomersReferenceIsRefused() throws Exception {
        UUID p = seedProduct(10);
        String ref = reference();

        PlaceOrderResponse mine = checkout.placeOrder(token, order(ref, Map.of(p, 1)));
        String intruderToken = otherCustomerToken();

        VeloriaException e = assertThrows(VeloriaException.class,
                () -> checkout.placeOrder(intruderToken, order(ref, Map.of(p, 1))),
                "a reference belongs to the checkout that created it");

        // No leakage: not the order code, not the uuid, not the owner.
        assertFalse(e.getMessage().contains(mine.getOrderCode()), "message must not disclose the order code");
        assertFalse(e.getMessage().contains(mine.getOrderUuid().toString()), "nor the order uuid");
        assertFalse(e.getMessage().toLowerCase().contains(buyerEmail.toLowerCase()), "nor the owner");

        assertEquals(1, orderRowsFor(ref), "and no order was created for the intruder");
        assertEquals(1, soldUnits(p));
    }

    // ── the database, not the service, is the authority ──────────────────────

    @Test
    @DisplayName("F — the database itself refuses a duplicate reference")
    void theIndexIsTheRealProtection() throws Exception {
        UUID p = seedProduct(10);
        String ref = reference();
        checkout.placeOrder(token, order(ref, Map.of(p, 1)));

        // Bypassing the service entirely: if the application layer were the only
        // guard, this would succeed and the invariant would rest on nothing.
        Exception e = assertThrows(Exception.class, () -> jdbc.update("""
                INSERT INTO customer_order (uuid, order_code, customer_id, client_order_reference,
                                            customer_email, currency, total_value, status,
                                            order_placed_at, active, archive)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, 'INR', 100000, 'ORDER_PLACED',
                        timezone('UTC', now()), true, false)
                """, "VO-DUPLICATE-" + UUID.randomUUID().toString().substring(0, 8),
                buyerUuid, ref, buyerEmail));
        assertTrue(rootMessage(e).contains("ux_customer_order_client_reference"),
                "the unique index must be what rejects it: " + rootMessage(e));

        assertEquals(1, orderRowsFor(ref));
    }

    @Test
    @DisplayName("F2 — the index is partial, so rows without a reference are never in conflict")
    void theIndexIgnoresNullReferences() {
        String code1 = "VO-NULLREF-" + UUID.randomUUID().toString().substring(0, 6);
        String code2 = "VO-NULLREF-" + UUID.randomUUID().toString().substring(0, 6);
        insertBare(code1);
        insertBare(code2);   // a second NULL must not collide with the first

        assertEquals(2, (long) jdbc.queryForObject(
                "SELECT count(*) FROM customer_order WHERE order_code IN (?, ?)", Long.class, code1, code2));
    }

    private void insertBare(String orderCode) {
        jdbc.update("""
                INSERT INTO customer_order (uuid, order_code, customer_id, client_order_reference,
                                            customer_email, currency, total_value, status,
                                            order_placed_at, active, archive)
                VALUES (gen_random_uuid(), ?, ?, NULL, ?, 'INR', 100000, 'ORDER_PLACED',
                        timezone('UTC', now()), true, false)
                """, orderCode, buyerUuid, buyerEmail);
    }

    private static String rootMessage(Throwable t) {
        return java.util.stream.Stream.iterate(t, java.util.Objects::nonNull, Throwable::getCause)
                .map(Throwable::getMessage)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining(" | "));
    }
}
