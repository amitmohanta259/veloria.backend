package com.veloria.automation.db;

import com.veloria.automation.support.ScenarioContext;

import java.util.UUID;

/**
 * Deterministic test data, created per scenario and removed by Hooks.
 *
 * Authentication is seeded rather than performed: a {@code client_session}
 * row is the only thing the API needs to treat a request as signed in, so
 * no test ever holds a password. Buyers are dedicated rows with a
 * recognisable {@code @automation.veloria.test} email, never a real person.
 */
public final class TestData {

    private TestData() {}

    public record Buyer(String userUuid, String email, String name, String phone) {}

    /** A fresh buyer row the order pipeline can look up by email. */
    public static Buyer seedBuyer(ScenarioContext ctx) {
        String uuid = UUID.randomUUID().toString();
        String email = "buyer-" + uuid.substring(0, 8) + "@automation.veloria.test";
        Db.execute("""
                INSERT INTO users (uuid, first_name, last_name, email, phone, active, archive, created)
                VALUES (?::uuid, 'Automation', 'Buyer', ?, '9000000000', true, false, now())
                """, uuid, email);
        ctx.onCleanup(() -> removeBuyer(uuid));
        return new Buyer(uuid, email, "Automation Buyer", "9000000000");
    }

    /**
     * A session for a buyer, with explicit lifetimes.
     *
     * {@code accessSeconds}/{@code loginSeconds} are offsets from now and may
     * be negative to fabricate a lapsed or ended session.
     */
    public static String seedSession(ScenarioContext ctx, Buyer buyer, long accessSeconds, long loginSeconds) {
        String token = "auto-" + UUID.randomUUID();
        Db.execute("""
                INSERT INTO client_session (token, user_id, name, email, phone, expiry, access_expiry)
                VALUES (?, ?, ?, ?, ?,
                        timezone('UTC', now()) + make_interval(secs => ?),
                        timezone('UTC', now()) + make_interval(secs => ?))
                """, token, buyer.userUuid(), buyer.name(), buyer.email(), buyer.phone(),
                (double) loginSeconds, (double) accessSeconds);
        ctx.onCleanup(() -> Db.execute("DELETE FROM client_session WHERE token = ?", token));
        return token;
    }

    /** A session that is valid right now, for the common case. */
    public static String seedLiveSession(ScenarioContext ctx, Buyer buyer) {
        return seedSession(ctx, buyer, 600, 7 * 24 * 3600);
    }

    /** Sessions the application created itself (by refreshing) are found by user. */
    public static void forgetSessionsOf(Buyer buyer) {
        Db.execute("DELETE FROM client_session WHERE user_id = ?", buyer.userUuid());
    }

    /**
     * Everything an order writes, removed in dependency order.
     *
     * The application never deletes financial records — that is a rule of
     * the product, not of a test fixture. This exists so a developer can run
     * the order scenarios against a disposable database and not accumulate
     * automation orders in their GST returns. See README: do not point
     * @mutates scenarios at a shared environment.
     */
    public static void removeOrderGraph(String orderCode) {
        Long orderId = Db.query("SELECT id FROM customer_order WHERE order_code = ?", orderCode).stream()
                .map(r -> ((Number) r.get("id")).longValue()).findFirst().orElse(null);
        if (orderId == null) return;

        // Reversals reference the sale they reverse, so they go first. Orders now
        // book a sale when they are created and a reversal when they are
        // cancelled; leaving either behind orphans a journal that still moves the
        // receivable.
        Db.execute("""
                DELETE FROM journal_entry_line WHERE journal_entry_id IN
                  (SELECT r.id FROM journal_entry r WHERE r.reverses_journal_id IN
                    (SELECT id FROM journal_entry WHERE source_type IN ('SALE', 'COD_FEE') AND CAST(source_id AS TEXT) = ?))
                """, String.valueOf(orderId));
        Db.execute("""
                DELETE FROM journal_entry WHERE reverses_journal_id IN
                  (SELECT id FROM journal_entry WHERE source_type IN ('SALE', 'COD_FEE') AND CAST(source_id AS TEXT) = ?)
                """, String.valueOf(orderId));
        Db.execute("""
                DELETE FROM journal_entry_line WHERE journal_entry_id IN
                  (SELECT id FROM journal_entry
                    WHERE (source_type IN ('SALE', 'COD_FEE') AND CAST(source_id AS TEXT) = ?)
                       OR (source_type = 'PAYMENT_COLLECTION' AND source_id IN
                           (SELECT pa.id FROM payment_attempt pa WHERE CAST(pa.customer_order_id AS TEXT) = ?)))
                """, String.valueOf(orderId), String.valueOf(orderId));
        Db.execute("""
                DELETE FROM journal_entry
                  WHERE (source_type IN ('SALE', 'COD_FEE') AND CAST(source_id AS TEXT) = ?)
                     OR (source_type = 'PAYMENT_COLLECTION' AND source_id IN
                         (SELECT pa.id FROM payment_attempt pa WHERE CAST(pa.customer_order_id AS TEXT) = ?))
                """, String.valueOf(orderId), String.valueOf(orderId));
        // Refund journals before the refund rows they are keyed on.
        Db.execute("""
                DELETE FROM journal_entry_line WHERE journal_entry_id IN
                  (SELECT id FROM journal_entry WHERE source_type = 'REFUND' AND source_id IN
                    (SELECT pr.id FROM payment_refund pr WHERE CAST(pr.customer_order_id AS TEXT) = ?))
                """, String.valueOf(orderId));
        Db.execute("""
                DELETE FROM journal_entry WHERE source_type = 'REFUND' AND source_id IN
                  (SELECT pr.id FROM payment_refund pr WHERE CAST(pr.customer_order_id AS TEXT) = ?)
                """, String.valueOf(orderId));
        Db.execute("DELETE FROM payment_refund WHERE CAST(customer_order_id AS TEXT) = ?", String.valueOf(orderId));
        Db.execute("DELETE FROM payment_attempt WHERE CAST(customer_order_id AS TEXT) = ?", String.valueOf(orderId));
        Db.execute("""
                DELETE FROM gst_movement_ledger WHERE
                  (source_type = 'SALES_INVOICE_ITEM' AND CAST(source_id AS TEXT) IN
                     (SELECT CAST(i.id AS TEXT) FROM sales_invoice_item i
                        JOIN sales_invoice s ON s.id = i.sales_invoice_id WHERE s.order_code = ?))
                  OR CAST(source_id AS TEXT) = ?
                """, orderCode, orderCode);
        Db.execute("DELETE FROM sales_invoice_item WHERE sales_invoice_id IN (SELECT id FROM sales_invoice WHERE order_code = ?)", orderCode);
        Db.execute("DELETE FROM sales_invoice WHERE order_code = ?", orderCode);
        Db.execute("DELETE FROM gst_output_tax WHERE order_code = ?", orderCode);
        Db.execute("DELETE FROM gst_accounting_exception WHERE CAST(source_id AS TEXT) = ?", orderCode);
        Db.execute("DELETE FROM sales_order WHERE order_code = ?", orderCode);
        Db.execute("DELETE FROM customer_order_item WHERE customer_order_id = ?", orderId);
        Db.execute("DELETE FROM customer_order WHERE id = ?", orderId);
    }

    private static void removeBuyer(String userUuid) {
        Db.execute("DELETE FROM customer_bag WHERE user_id = ?", userUuid);
        Db.execute("DELETE FROM user_address WHERE user_id = ?", userUuid);
        Db.execute("DELETE FROM customer_favourite WHERE user_id = ?", userUuid);
        Db.execute("DELETE FROM client_session WHERE user_id = ?", userUuid);
        Db.execute("DELETE FROM users WHERE uuid = ?::uuid", userUuid);
    }
}
