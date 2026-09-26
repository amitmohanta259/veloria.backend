package com.app.master.service.inventory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Why {@code addStock} had to stop reading before it wrote.
 *
 * This test does not need a database: the defect is in the shape of the
 * operation, not in PostgreSQL. It models both shapes against the same shared
 * cell and shows that only the atomic one conserves every addition.
 *
 * The production code now issues
 * {@code UPDATE … SET initial_stock = initial_stock + :qty}, which is the
 * atomic shape — the read and the write are one statement, so the row lock
 * PostgreSQL takes serialises concurrent callers.
 *
 * {@link InventoryProductServiceImplStockTest} then pins the production code to
 * that shape, so this stays a demonstration of the rule rather than a claim
 * about untested code.
 */
class AddStockConcurrencyTest {

    private static final long INITIAL = 100L;
    private static final int THREADS = 20;
    private static final long EACH = 10L;
    private static final long EXPECTED = INITIAL + THREADS * EACH;   // 300

    /** The shape the code used to have: read, add in Java, write back. */
    @Test
    @DisplayName("Read-modify-write loses concurrent additions — this is the defect that was fixed")
    void readModifyWriteLosesUpdates() throws Exception {
        long[] cell = {INITIAL};
        runConcurrently(() -> {
            long seen = cell[0];          // SELECT
            Thread.yield();               // the window another writer slips through
            cell[0] = seen + EACH;        // UPDATE … SET stock = :valueComputedInJava
        });
        assertTrue(cell[0] <= EXPECTED, "cannot exceed the total actually added");
        // Not asserting that it *must* lose — the interleaving is not guaranteed.
        // The point is the contrast with the atomic case below.
    }

    /** The shape the code has now: the increment happens inside the write. */
    @Test
    @DisplayName("Atomic increment conserves every addition: 100 + 20×10 = 300")
    void atomicIncrementConservesEveryAddition() throws Exception {
        AtomicLong cell = new AtomicLong(INITIAL);
        runConcurrently(() -> cell.addAndGet(EACH));   // UPDATE … SET stock = stock + :qty
        assertEquals(EXPECTED, cell.get(),
                "final stock must be initial + the sum of every successful addition");
    }

    @Test
    @DisplayName("Concurrent additions to different sizes of one product do not interfere")
    void additionsToDifferentSizesAreIndependent() throws Exception {
        Map<String, AtomicLong> sizes = new ConcurrentHashMap<>();
        sizes.put("S", new AtomicLong(INITIAL));
        sizes.put("M", new AtomicLong(INITIAL));
        String[] keys = {"S", "M"};

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        for (int i = 0; i < THREADS; i++) {
            String key = keys[i % 2];
            pool.submit(() -> {
                try {
                    start.await();
                    sizes.get(key).addAndGet(EACH);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "threads must finish");
        pool.shutdownNow();

        long perSize = INITIAL + (THREADS / 2) * EACH;
        assertEquals(perSize, sizes.get("S").get());
        assertEquals(perSize, sizes.get("M").get());
        assertEquals(perSize * 2, sizes.get("S").get() + sizes.get("M").get(),
                "the product roll-up is the sum of its sizes");
    }

    private static void runConcurrently(Runnable action) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    action.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "threads must finish");
        pool.shutdownNow();
    }
}
