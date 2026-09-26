package com.app.master.service.inventory;

import com.app.master.service.service.admin.InventoryProductService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real concurrency against real PostgreSQL.
 *
 * The sibling {@code AddStockConcurrencyTest} models the two shapes against an
 * in-memory cell and needs no database; it cannot prove row-level behaviour.
 * This one does: N threads call the production
 * {@link InventoryProductService#addStock} concurrently against a row this test
 * creates, and the final figure is read back with SQL.
 *
 * It runs through the Spring context, so the transaction boundary exercised is
 * the one the production method declares — the test itself is deliberately
 * <b>not</b> {@code @Transactional}, because a test-owned transaction would
 * both hide the real boundary and prevent the worker threads from seeing each
 * other's committed work.
 *
 * If {@code addStock} ever reverts to read-modify-write, threads will read the
 * same figure and overwrite each other, the total will come up short, and this
 * fails.
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
class AddStockPostgresConcurrencyTest {

    private static final long INITIAL = 100L;
    private static final int THREADS = 20;
    private static final long EACH = 10L;
    private static final long EXPECTED = INITIAL + THREADS * EACH;   // 300
    private static final String SIZE = "CONC-M";

    @Autowired private InventoryProductService inventoryProductService;
    @Autowired private JdbcTemplate jdbc;

    private UUID productUuid;
    private Long productId;

    @BeforeEach
    void seed() {
        productUuid = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO inventory_product (uuid, name, sku_id, initial_stock, archive, created)
                VALUES (?::uuid, ?, ?, ?, false, now())
                """, productUuid.toString(), "Automation Concurrency Product",
                "AUTOMATION-CONC-" + productUuid.toString().substring(0, 8), INITIAL);

        productId = jdbc.queryForObject(
                "SELECT id FROM inventory_product WHERE uuid = ?::uuid", Long.class, productUuid.toString());

        jdbc.update("""
                INSERT INTO inventory_product_size_stock (product_id, size, initial_stock, archive)
                VALUES (?, ?, ?, false)
                """, productId, SIZE, INITIAL);
    }

    @AfterEach
    void cleanUp() {
        if (productId != null) {
            jdbc.update("DELETE FROM inventory_product_size_stock WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM inventory_product WHERE id = ?", productId);
        }
    }

    private long sizeStockFromDatabase() {
        return jdbc.queryForObject(
                "SELECT initial_stock FROM inventory_product_size_stock WHERE product_id = ? AND size = ?",
                Long.class, productId, SIZE);
    }

    private long productStockFromDatabase() {
        return jdbc.queryForObject(
                "SELECT initial_stock FROM inventory_product WHERE id = ?", Long.class, productId);
    }

    @Test
    @DisplayName("20 concurrent additions of 10 against 100 leave exactly 300 — no addition is lost")
    void concurrentAdditionsAreAllApplied() throws Exception {
        assertEquals(INITIAL, sizeStockFromDatabase(), "fixture must start at the initial figure");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        try {
            for (int i = 0; i < THREADS; i++) {
                pool.submit(() -> {
                    try {
                        ready.countDown();
                        // Every worker waits here, so the transactions genuinely
                        // contend for the same row rather than running in turn.
                        start.await();
                        inventoryProductService.addStock(productUuid, SIZE, EACH);
                        succeeded.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(ready.await(30, TimeUnit.SECONDS), "all workers must reach the start line");
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "all workers must finish");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, failed.get(), "no addition should fail");
        assertEquals(THREADS, succeeded.get(), "every worker should have applied its addition");

        long actual = sizeStockFromDatabase();
        assertEquals(EXPECTED, actual,
                () -> "lost update: " + succeeded.get() + " additions of " + EACH + " against " + INITIAL
                        + " should give " + EXPECTED + ", PostgreSQL holds " + actual);

        // The roll-up is derived in SQL from the size rows, so it must agree.
        assertEquals(EXPECTED, productStockFromDatabase(), "product roll-up must match the sum of its sizes");
    }

    @Test
    @DisplayName("Concurrent additions to different sizes of one product are independent and roll up correctly")
    void concurrentAdditionsToDifferentSizes() throws Exception {
        String second = "CONC-L";
        jdbc.update("""
                INSERT INTO inventory_product_size_stock (product_id, size, initial_stock, archive)
                VALUES (?, ?, ?, false)
                """, productId, second, INITIAL);
        // The product total is the sum of its sizes, so seeding a second size
        // must move it too — otherwise the fixture starts inconsistent and the
        // assertion below would be measuring the fixture, not the code.
        jdbc.update("UPDATE inventory_product SET initial_stock = initial_stock + ? WHERE id = ?",
                INITIAL, productId);
        assertEquals(INITIAL * 2, productStockFromDatabase(), "fixture must start consistent");

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        try {
            for (int i = 0; i < THREADS; i++) {
                String target = (i % 2 == 0) ? SIZE : second;
                pool.submit(() -> {
                    try {
                        start.await();
                        inventoryProductService.addStock(productUuid, target, EACH);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception ignored) {
                        // counted by the assertions below
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "all workers must finish");
        } finally {
            pool.shutdownNow();
        }

        long perSize = INITIAL + (THREADS / 2) * EACH;   // 200 each
        assertEquals(perSize, sizeStockFromDatabase(), "first size");
        assertEquals(perSize, jdbc.queryForObject(
                "SELECT initial_stock FROM inventory_product_size_stock WHERE product_id = ? AND size = ?",
                Long.class, productId, second), "second size");
        assertEquals(perSize * 2, productStockFromDatabase(), "roll-up is the sum of both sizes");
    }

    @Test
    @DisplayName("A refused addition writes nothing")
    void refusedAdditionWritesNothing() {
        assertThrows(Exception.class, () -> inventoryProductService.addStock(productUuid, SIZE, 0L));
        assertEquals(INITIAL, sizeStockFromDatabase(), "a rejected quantity must leave the row untouched");
        assertEquals(INITIAL, productStockFromDatabase());
    }
}
