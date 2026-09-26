package com.app.master.service.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails a test that left accounting rows behind.
 *
 * <p>Every order now writes to the ledger, so a test that places one and does not
 * remove its journals moves the historical journal checksum — and the checksum is
 * the only thing standing between the seven historical orders and silent
 * corruption. That happened once: a teardown hit one bad DELETE, aborted, and left
 * 29 orders and 39 journals behind, which then had to be identified and removed by
 * hand.
 *
 * <p>Per-statement error handling is not enough on its own, because it only catches
 * the statements a test remembered to write. This checks the outcome instead: after
 * teardown, is there anything referring to an order that no longer exists? It
 * therefore catches a cleanup that failed, a cleanup that ran in the wrong order,
 * and a cleanup that forgot a table.
 *
 * <p>Deliberately scoped to orphans rather than to absolute counts. A shared
 * database legitimately holds the historical orders and whatever another suite is
 * mid-way through; what is never legitimate is a journal whose order is gone.
 */
public final class AccountingResidue {

    private AccountingResidue() {}

    /** Asserts that nothing in the books refers to an order that no longer exists. */
    public static void assertNone(JdbcTemplate jdbc) {
        List<String> residue = new ArrayList<>();

        // A sale or a COD charge whose order has been deleted. This is the exact
        // shape of the residue that moved the checksum.
        count(jdbc, residue, "orphaned SALE/COD_FEE journals", """
                SELECT count(*) FROM journal_entry je
                 WHERE je.source_type IN ('SALE','COD_FEE') AND je.source_id IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM customer_order o WHERE o.id = je.source_id)
                """);

        // A collection or refund journal whose payment row has been deleted —
        // what happens when payments are removed before the journals keyed on them.
        count(jdbc, residue, "orphaned PAYMENT_COLLECTION journals", """
                SELECT count(*) FROM journal_entry je
                 WHERE je.source_type = 'PAYMENT_COLLECTION' AND je.source_id IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM payment_attempt pa WHERE pa.id = je.source_id)
                """);
        count(jdbc, residue, "orphaned REFUND journals", """
                SELECT count(*) FROM journal_entry je
                 WHERE je.source_type = 'REFUND' AND je.source_id IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM payment_refund pr WHERE pr.id = je.source_id)
                """);

        // A journal line with no header, or a header whose order is gone by way of
        // a reversal that outlived it.
        count(jdbc, residue, "journal lines with no entry", """
                SELECT count(*) FROM journal_entry_line l
                 WHERE NOT EXISTS (SELECT 1 FROM journal_entry je WHERE je.id = l.journal_entry_id)
                """);
        count(jdbc, residue, "reversals of a journal that is gone", """
                SELECT count(*) FROM journal_entry r
                 WHERE r.reverses_journal_id IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM journal_entry je WHERE je.id = r.reverses_journal_id)
                """);

        // Payments and refunds whose order has been deleted.
        count(jdbc, residue, "payments with no order", """
                SELECT count(*) FROM payment_attempt pa
                 WHERE NOT EXISTS (SELECT 1 FROM customer_order o WHERE o.id = pa.customer_order_id)
                """);
        count(jdbc, residue, "refunds with no order", """
                SELECT count(*) FROM payment_refund pr
                 WHERE NOT EXISTS (SELECT 1 FROM customer_order o WHERE o.id = pr.customer_order_id)
                """);

        // A period this test closed and forgot to reopen would silently defer every
        // later posting, so it counts as residue too.
        count(jdbc, residue, "accounting periods left closed", """
                SELECT count(*) FROM accounting_period WHERE status <> 'OPEN'
                """);

        assertTrue(residue.isEmpty(),
                "teardown left accounting residue behind, which will move the historical "
                + "journal checksum: " + residue);
    }

    private static void count(JdbcTemplate jdbc, List<String> into, String what, String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        if (n != null && n > 0) into.add(what + " = " + n);
    }
}
