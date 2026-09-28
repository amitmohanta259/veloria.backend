package com.app.master.service.service.engineering;

import com.app.master.service.core.engineering.AnomalyDomain;
import com.app.master.service.core.engineering.AnomalySeverity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The rule set, all reading through the read-only datasource.
 *
 * <p>Every query here is a {@code SELECT}. They run as {@code veloria_scan_ro},
 * which holds no other privilege, so the read-only guarantee does not depend on
 * this class being careful — a mutating statement would be refused by PostgreSQL.
 *
 * <p><b>What is deliberately absent.</b> No transportation rule and no
 * gateway-customer-charge rule. Both have no approved expected value — transportation
 * pricing and the gateway customer split are recorded UNDECIDED in
 * {@code p0-14-decision-register.md} — so a rule comparing against one would be
 * inventing the very business rule it claims to test (§51). {@code shipping_value}
 * is 0 on every order and flagging that as wrong would be meaningless.
 *
 * <p>Amounts are integer paise throughout, and comparisons are exact integer
 * comparisons. No rule uses a tolerance: a rounding tolerance would hide the
 * one-paisa reconciliation failures these checks exist to catch.
 */
@Component
public class AnomalyRules {

    private final JdbcTemplate jdbc;

    /** Explicit, so the qualifier lands on the parameter — see AnomalyScanService. */
    public AnomalyRules(@Qualifier("engineeringReadOnlyJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every rule, in the order a scan runs them. */
    public List<AnomalyRule> all() {
        return List.of(
                gstTotalMismatch(),
                gstHeadsMismatch(),
                codInclusiveArithmetic(),
                missingPlaceOfSupply(),
                missingHsn(),
                codChargeWithoutSac(),
                unbalancedJournal(),
                orphanJournalLine(),
                duplicateSaleJournal(),
                arMismatch(),
                collectionExceedsInvoice(),
                paymentWithoutOrder(),
                refundExceedsCollected(),
                refundWithoutVerifiedReturn(),
                returnQuantityExceedsPurchased(),
                inventoryReleaseExceedsConsumption(),
                invoiceTotalMismatch(),
                invoiceSupplyTypeMismatch(),
                codRefundWithoutApprovedDestination()
        );
    }

    // ── GST ──────────────────────────────────────────────────────────────────

    /**
     * The order's stated total tax must equal the sum of its heads.
     *
     * <p>Not a rounding check — these are integer paise, so any difference is a real
     * inconsistency between two columns that must agree by construction.
     */
    private AnomalyRule gstTotalMismatch() {
        return rule("GST_TOTAL_MISMATCH", AnomalyDomain.GST, AnomalySeverity.HIGH,
                "The order's total tax does not equal CGST + SGST + IGST",
                scope -> query(scope, """
                        SELECT o.order_code, o.id,
                               o.total_tax_amount AS actual,
                               (COALESCE(o.cgst_amount,0) + COALESCE(o.sgst_amount,0)
                                + COALESCE(o.igst_amount,0)) AS expected
                          FROM customer_order o
                         WHERE o.archive = false
                           AND COALESCE(o.total_tax_amount,0) <>
                               (COALESCE(o.cgst_amount,0) + COALESCE(o.sgst_amount,0)
                                + COALESCE(o.igst_amount,0))
                           AND (CAST(? AS timestamptz) IS NULL OR o.created >= CAST(? AS timestamptz))
                           AND (CAST(? AS timestamptz) IS NULL OR o.created <= CAST(? AS timestamptz))
                           AND (CAST(? AS text) IS NULL OR o.order_code = CAST(? AS text))
                        """, "ORDER", "customer_order", "total_tax_amount"));
    }

    /**
     * A supply is intra-state or inter-state, never both.
     *
     * <p>CGST+SGST and IGST on the same order means the supply type was decided
     * twice, differently — which misstates which state the tax is owed to.
     */
    private AnomalyRule gstHeadsMismatch() {
        return rule("GST_SUPPLY_TYPE_CONFLICT", AnomalyDomain.GST, AnomalySeverity.HIGH,
                "The order carries both CGST/SGST and IGST, so the supply type is inconsistent",
                scope -> query(scope, """
                        SELECT o.order_code, o.id,
                               (COALESCE(o.cgst_amount,0) + COALESCE(o.sgst_amount,0))::text
                                 || ' intra and ' || COALESCE(o.igst_amount,0)::text || ' inter' AS actual,
                               'one of the two to be zero' AS expected
                          FROM customer_order o
                         WHERE o.archive = false
                           AND (COALESCE(o.cgst_amount,0) + COALESCE(o.sgst_amount,0)) > 0
                           AND COALESCE(o.igst_amount,0) > 0
                           AND (CAST(? AS timestamptz) IS NULL OR o.created >= CAST(? AS timestamptz))
                           AND (CAST(? AS timestamptz) IS NULL OR o.created <= CAST(? AS timestamptz))
                           AND (CAST(? AS text) IS NULL OR o.order_code = CAST(? AS text))
                        """, "ORDER", "customer_order", "igst_amount"));
    }

    /**
     * The COD charge must reconcile: taxable + tax = the charge the customer pays.
     *
     * <p>This is the invariant P0-13 established. Under the configured INCLUSIVE
     * basis ₹50 carves to 4237 + 763; a row where those three do not agree means the
     * customer was quoted one figure and billed another.
     */
    private AnomalyRule codInclusiveArithmetic() {
        return rule("COD_FEE_ARITHMETIC_MISMATCH", AnomalyDomain.GST, AnomalySeverity.HIGH,
                "The COD charge's taxable value plus its tax does not equal the charge",
                scope -> query(scope, """
                        SELECT o.order_code, o.id,
                               (COALESCE(o.cod_fee_taxable_paise,0) + COALESCE(o.cod_fee_tax_paise,0))::text AS actual,
                               COALESCE(o.cod_fee_paise,0)::text AS expected
                          FROM customer_order o
                         WHERE o.archive = false
                           AND COALESCE(o.cod_fee_paise,0) > 0
                           AND o.cod_fee_tax_resolution = 'RULE_APPLIED'
                           AND (COALESCE(o.cod_fee_taxable_paise,0) + COALESCE(o.cod_fee_tax_paise,0))
                               <> COALESCE(o.cod_fee_paise,0)
                           AND (CAST(? AS timestamptz) IS NULL OR o.created >= CAST(? AS timestamptz))
                           AND (CAST(? AS timestamptz) IS NULL OR o.created <= CAST(? AS timestamptz))
                           AND (CAST(? AS text) IS NULL OR o.order_code = CAST(? AS text))
                        """, "ORDER", "customer_order", "cod_fee_taxable_paise"));
    }

    /**
     * A finalised order needs a place of supply.
     *
     * <p>Scoped to orders that were actually taxed: a shopper's abandoned draft is
     * not a compliance gap, and the application already refuses to bill a COD charge
     * without one.
     */
    private AnomalyRule missingPlaceOfSupply() {
        return rule("ORDER_PLACE_OF_SUPPLY_REQUIRED", AnomalyDomain.COMPLIANCE, AnomalySeverity.HIGH,
                "A finalised, taxed order has no place of supply, so its CGST/SGST vs IGST split is unverifiable",
                scope -> query(scope, """
                        SELECT o.order_code, o.id,
                               'null' AS actual,
                               'a state code' AS expected
                          FROM customer_order o
                         WHERE o.archive = false
                           AND COALESCE(o.total_tax_amount,0) > 0
                           AND (o.place_of_supply IS NULL OR btrim(o.place_of_supply) = '')
                           AND (o.buyer_state_code IS NULL OR btrim(o.buyer_state_code) = '')
                           AND (CAST(? AS timestamptz) IS NULL OR o.created >= CAST(? AS timestamptz))
                           AND (CAST(? AS timestamptz) IS NULL OR o.created <= CAST(? AS timestamptz))
                           AND (CAST(? AS text) IS NULL OR o.order_code = CAST(? AS text))
                        """, "ORDER", "customer_order", "place_of_supply"));
    }

    /** A taxed line must carry the classification its rate was looked up by. */
    private AnomalyRule missingHsn() {
        return rule("ORDER_ITEM_HSN_REQUIRED", AnomalyDomain.COMPLIANCE, AnomalySeverity.MEDIUM,
                "A taxed order line has no HSN, so the rate it was charged at cannot be traced to a rule",
                scope -> query(scope, """
                        SELECT o.order_code, i.id,
                               'null' AS actual,
                               'an HSN code' AS expected
                          FROM customer_order_item i
                          JOIN customer_order o ON o.id = i.customer_order_id
                         WHERE i.archive = false AND o.archive = false
                           AND COALESCE(i.total_tax_paise,0) > 0
                           AND (i.hsn_code IS NULL OR btrim(i.hsn_code) = '')
                           AND (CAST(? AS timestamptz) IS NULL OR o.created >= CAST(? AS timestamptz))
                           AND (CAST(? AS timestamptz) IS NULL OR o.created <= CAST(? AS timestamptz))
                           AND (CAST(? AS text) IS NULL OR o.order_code = CAST(? AS text))
                        """, "ORDER_ITEM", "customer_order_item", "hsn_code"));
    }

    /** A COD charge that was taxed must record the service code it was taxed under. */
    private AnomalyRule codChargeWithoutSac() {
        return rule("COD_FEE_SAC_REQUIRED", AnomalyDomain.COMPLIANCE, AnomalySeverity.MEDIUM,
                "A taxed COD charge has no SAC recorded, so its classification cannot be traced",
                scope -> query(scope, """
                        SELECT o.order_code, o.id,
                               'null' AS actual,
                               'the configured SAC' AS expected
                          FROM customer_order o
                         WHERE o.archive = false
                           AND COALESCE(o.cod_fee_tax_paise,0) > 0
                           AND (o.cod_fee_sac_code IS NULL OR btrim(o.cod_fee_sac_code) = '')
                           AND (CAST(? AS timestamptz) IS NULL OR o.created >= CAST(? AS timestamptz))
                           AND (CAST(? AS timestamptz) IS NULL OR o.created <= CAST(? AS timestamptz))
                           AND (CAST(? AS text) IS NULL OR o.order_code = CAST(? AS text))
                        """, "ORDER", "customer_order", "cod_fee_sac_code"));
    }

    // ── accounting ───────────────────────────────────────────────────────────

    /**
     * Double entry: every journal's debits must equal its credits.
     *
     * <p>The only CRITICAL rule. If this fires the ledger itself is wrong, and every
     * report drawn from it is unreliable.
     */
    private AnomalyRule unbalancedJournal() {
        return rule("JOURNAL_UNBALANCED", AnomalyDomain.ACCOUNTING, AnomalySeverity.CRITICAL,
                "A journal's debits do not equal its credits",
                scope -> {
                    List<AnomalyRule.Candidate> out = new ArrayList<>();
                    jdbc.query("""
                            SELECT je.journal_number, je.id,
                                   SUM(l.debit_paise) AS dr, SUM(l.credit_paise) AS cr
                              FROM journal_entry je
                              JOIN journal_entry_line l ON l.journal_entry_id = je.id
                             WHERE (CAST(? AS timestamptz) IS NULL OR je.created_at >= CAST(? AS timestamptz))
                               AND (CAST(? AS timestamptz) IS NULL OR je.created_at <= CAST(? AS timestamptz))
                             GROUP BY je.journal_number, je.id
                            HAVING SUM(l.debit_paise) <> SUM(l.credit_paise)
                            """,
                            ps -> bindWindow(ps, scope),
                            (RowCallbackHandler) rs -> {
                                out.add(new AnomalyRule.Candidate(
                                        "JOURNAL", String.valueOf(rs.getLong("id")),
                                        rs.getString("journal_number"), null, null,
                                        "debits " + rs.getLong("dr") + " paise",
                                        "credits " + rs.getLong("cr") + " paise",
                                        "The journal does not balance.",
                                        "journal_entry_line", "debit_paise"));
                            });
                    return out;
                });
    }

    /** A line whose journal no longer exists cannot be reported on or reversed. */
    private AnomalyRule orphanJournalLine() {
        return rule("JOURNAL_LINE_ORPHAN", AnomalyDomain.ACCOUNTING, AnomalySeverity.CRITICAL,
                "A journal line references a journal that does not exist",
                scope -> {
                    List<AnomalyRule.Candidate> out = new ArrayList<>();
                    jdbc.query("""
                            SELECT l.id, l.journal_entry_id
                              FROM journal_entry_line l
                              LEFT JOIN journal_entry je ON je.id = l.journal_entry_id
                             WHERE je.id IS NULL
                            """,
                            (RowCallbackHandler) rs -> {
                                out.add(new AnomalyRule.Candidate(
                                        "JOURNAL_LINE", String.valueOf(rs.getLong("id")),
                                        null, null, null,
                                        "journal_entry_id " + rs.getLong("journal_entry_id"),
                                        "an existing journal", "The line's journal is missing.",
                                        "journal_entry_line", "journal_entry_id"));
                            });
                    return out;
                });
    }

    /** An order books one sale. Two means the revenue is counted twice. */
    private AnomalyRule duplicateSaleJournal() {
        return rule("SALE_JOURNAL_DUPLICATE", AnomalyDomain.ACCOUNTING, AnomalySeverity.CRITICAL,
                "More than one SALE journal exists for the same order",
                scope -> {
                    List<AnomalyRule.Candidate> out = new ArrayList<>();
                    jdbc.query("""
                            SELECT o.order_code, je.source_id, COUNT(*) AS n
                              FROM journal_entry je
                              JOIN customer_order o ON CAST(o.id AS TEXT) = CAST(je.source_id AS TEXT)
                             WHERE je.source_type = 'SALE'
                               -- POSTED only. An order legitimately carries several
                               -- SALE journals when a posting was reversed and
                               -- re-made; those are superseded, not duplicated, and
                               -- counting them reported every order in the seed data
                               -- as having four duplicate sales.
                               AND je.status = 'POSTED'
                               AND (CAST(? AS timestamptz) IS NULL OR je.created_at >= CAST(? AS timestamptz))
                               AND (CAST(? AS timestamptz) IS NULL OR je.created_at <= CAST(? AS timestamptz))
                             GROUP BY o.order_code, je.source_id
                            HAVING COUNT(*) > 1
                            """,
                            ps -> bindWindow(ps, scope),
                            (RowCallbackHandler) rs -> {
                                out.add(new AnomalyRule.Candidate(
                                        "ORDER", String.valueOf(rs.getLong("source_id")),
                                        rs.getString("order_code"), rs.getString("order_code"), null,
                                        rs.getInt("n") + " SALE journals", "exactly 1",
                                        "The order's revenue is recognised more than once.",
                                        "journal_entry", "source_type"));
                            });
                    return out;
                });
    }

    /**
     * Receivable reconciliation.
     *
     * <p>AR for an order must be what it was invoiced less what was collected. A
     * difference means a sale, a charge or a collection is missing or duplicated.
     */
    private AnomalyRule arMismatch() {
        return rule("AR_MISMATCH", AnomalyDomain.ACCOUNTING, AnomalySeverity.HIGH,
                "The receivable on the order does not equal what was billed less what was collected",
                scope -> {
                    List<AnomalyRule.Candidate> out = new ArrayList<>();
                    jdbc.query("""
                            -- Every journal that touches the order's receivable,
                            -- including reversals. A reversal keys on the journal it
                            -- reverses, not on the order, so without the second leg
                            -- of this union a reversed-and-reposted sale looks like
                            -- the receivable having been debited twice over.
                            WITH order_journals AS (
                              SELECT je.id, CAST(je.source_id AS TEXT) AS oid
                                FROM journal_entry je
                               WHERE je.source_type IN ('SALE', 'COD_FEE', 'PAYMENT_COLLECTION')
                              UNION ALL
                              SELECT r.id, CAST(je.source_id AS TEXT) AS oid
                                FROM journal_entry r
                                JOIN journal_entry je ON je.id = r.reverses_journal_id
                               WHERE r.source_type = 'REVERSAL'
                            ),
                            ar AS (
                              SELECT oj.oid, SUM(l.debit_paise - l.credit_paise) AS balance
                                FROM order_journals oj
                                JOIN journal_entry_line l ON l.journal_entry_id = oj.id
                               WHERE l.account_code = '1100'
                               GROUP BY oj.oid
                            )
                            SELECT o.order_code, o.id, ar.balance,
                                   (COALESCE(o.taxable_value,0) + COALESCE(o.total_tax_amount,0)
                                    + COALESCE(o.shipping_value,0)
                                    + COALESCE(NULLIF(o.cod_fee_taxable_paise,0), o.cod_fee_paise, 0)
                                    + COALESCE(o.cod_fee_tax_paise,0)) AS invoiced
                              FROM customer_order o
                              JOIN ar ON ar.oid = CAST(o.id AS TEXT)
                             WHERE o.archive = false
                               AND ar.balance <>
                                   (COALESCE(o.taxable_value,0) + COALESCE(o.total_tax_amount,0)
                                    + COALESCE(o.shipping_value,0)
                                    + COALESCE(NULLIF(o.cod_fee_taxable_paise,0), o.cod_fee_paise, 0)
                                    + COALESCE(o.cod_fee_tax_paise,0))
                               AND (CAST(? AS timestamptz) IS NULL OR o.created >= CAST(? AS timestamptz))
                               AND (CAST(? AS timestamptz) IS NULL OR o.created <= CAST(? AS timestamptz))
                               AND (CAST(? AS text) IS NULL OR o.order_code = CAST(? AS text))
                            """,
                            ps -> bindOrder(ps, scope),
                            (RowCallbackHandler) rs -> {
                                out.add(new AnomalyRule.Candidate(
                                        "ORDER", String.valueOf(rs.getLong("id")),
                                        rs.getString("order_code"), rs.getString("order_code"), null,
                                        rs.getLong("balance") + " paise receivable",
                                        rs.getLong("invoiced") + " paise invoiced",
                                        "The receivable and the invoice disagree with nothing posted to explain it.",
                                        "journal_entry_line", "debit_paise"));
                            });
                    return out;
                });
    }

    // ── payment ──────────────────────────────────────────────────────────────

    /** A captured payment must not exceed the order it belongs to. */
    private AnomalyRule collectionExceedsInvoice() {
        return rule("COLLECTION_EXCEEDS_INVOICE", AnomalyDomain.PAYMENT, AnomalySeverity.HIGH,
                "More was captured against the order than it was invoiced for",
                scope -> query(scope, """
                        SELECT o.order_code, pa.id,
                               SUM(pa.amount_paise) OVER (PARTITION BY pa.customer_order_id)::text AS actual,
                               (COALESCE(o.taxable_value,0) + COALESCE(o.total_tax_amount,0)
                                + COALESCE(o.shipping_value,0)
                                + COALESCE(NULLIF(o.cod_fee_taxable_paise,0), o.cod_fee_paise, 0)
                                + COALESCE(o.cod_fee_tax_paise,0))::text AS expected
                          FROM payment_attempt pa
                          JOIN customer_order o ON o.id = pa.customer_order_id
                         WHERE pa.status IN ('CAPTURED', 'PAID', 'COLLECTED')
                           AND o.archive = false
                           AND pa.amount_paise >
                               (COALESCE(o.taxable_value,0) + COALESCE(o.total_tax_amount,0)
                                + COALESCE(o.shipping_value,0)
                                + COALESCE(NULLIF(o.cod_fee_taxable_paise,0), o.cod_fee_paise, 0)
                                + COALESCE(o.cod_fee_tax_paise,0))
                           AND (CAST(? AS timestamptz) IS NULL OR o.created >= CAST(? AS timestamptz))
                           AND (CAST(? AS timestamptz) IS NULL OR o.created <= CAST(? AS timestamptz))
                           AND (CAST(? AS text) IS NULL OR o.order_code = CAST(? AS text))
                        """, "PAYMENT_ATTEMPT", "payment_attempt", "amount_paise"));
    }

    /** A payment with no order behind it cannot be reconciled to revenue. */
    private AnomalyRule paymentWithoutOrder() {
        return rule("PAYMENT_WITHOUT_ORDER", AnomalyDomain.PAYMENT, AnomalySeverity.HIGH,
                "A payment attempt references no existing order",
                scope -> {
                    List<AnomalyRule.Candidate> out = new ArrayList<>();
                    jdbc.query("""
                            SELECT pa.id, pa.order_code
                              FROM payment_attempt pa
                              LEFT JOIN customer_order o ON o.id = pa.customer_order_id
                             WHERE o.id IS NULL
                            """,
                            (RowCallbackHandler) rs -> out.add(new AnomalyRule.Candidate(
                                    "PAYMENT_ATTEMPT", String.valueOf(rs.getLong("id")),
                                    rs.getString("order_code"), null, null,
                                    "no order row", "an existing order",
                                    "The payment cannot be reconciled to an order.",
                                    "payment_attempt", "customer_order_id")));
                    return out;
                });
    }

    // ── return and refund ────────────────────────────────────────────────────

    /** Never give back more than came in. */
    private AnomalyRule refundExceedsCollected() {
        return rule("REFUND_EXCEEDS_COLLECTED", AnomalyDomain.REFUND, AnomalySeverity.HIGH,
                "More has been refunded on the order than was ever collected",
                scope -> {
                    List<AnomalyRule.Candidate> out = new ArrayList<>();
                    jdbc.query("""
                            WITH collected AS (
                              SELECT customer_order_id, SUM(amount_paise) AS total
                                FROM payment_attempt
                               WHERE status IN ('CAPTURED', 'PAID', 'COLLECTED')
                               GROUP BY customer_order_id),
                            refunded AS (
                              SELECT customer_order_id, SUM(amount_paise) AS total
                                FROM payment_refund
                               WHERE status = 'REFUNDED'
                               GROUP BY customer_order_id)
                            SELECT o.order_code, o.id, r.total AS refunded, COALESCE(c.total,0) AS collected
                              FROM refunded r
                              JOIN customer_order o ON o.id = r.customer_order_id
                              LEFT JOIN collected c ON c.customer_order_id = r.customer_order_id
                             WHERE r.total > COALESCE(c.total, 0)
                               AND (CAST(? AS timestamptz) IS NULL OR o.created >= CAST(? AS timestamptz))
                               AND (CAST(? AS timestamptz) IS NULL OR o.created <= CAST(? AS timestamptz))
                               AND (CAST(? AS text) IS NULL OR o.order_code = CAST(? AS text))
                            """,
                            ps -> bindOrder(ps, scope),
                            (RowCallbackHandler) rs -> out.add(new AnomalyRule.Candidate(
                                    "ORDER", String.valueOf(rs.getLong("id")),
                                    rs.getString("order_code"), rs.getString("order_code"), null,
                                    rs.getLong("refunded") + " paise refunded",
                                    "at most " + rs.getLong("collected") + " paise",
                                    "The refund exceeds what was collected.",
                                    "payment_refund", "amount_paise")));
                    return out;
                });
    }

    /** The approved workflow: nothing is refunded before the warehouse verifies it. */
    private AnomalyRule refundWithoutVerifiedReturn() {
        return rule("REFUND_BEFORE_VERIFICATION", AnomalyDomain.REFUND, AnomalySeverity.HIGH,
                "A completed refund has no verified return behind it",
                scope -> {
                    List<AnomalyRule.Candidate> out = new ArrayList<>();
                    jdbc.query("""
                            SELECT pr.id, pr.order_code,
                                   COALESCE(rr.status, 'no return') AS return_status
                              FROM payment_refund pr
                              LEFT JOIN order_return_request rr ON rr.id = pr.return_request_id
                             WHERE pr.status = 'REFUNDED'
                               AND (rr.id IS NULL OR rr.status <> 'VERIFIED')
                               AND (CAST(? AS timestamptz) IS NULL OR pr.created_at >= CAST(? AS timestamptz))
                               AND (CAST(? AS timestamptz) IS NULL OR pr.created_at <= CAST(? AS timestamptz))
                            """,
                            ps -> bindWindow(ps, scope),
                            (RowCallbackHandler) rs -> out.add(new AnomalyRule.Candidate(
                                    "REFUND", String.valueOf(rs.getLong("id")),
                                    rs.getString("order_code"), rs.getString("order_code"), null,
                                    "return status " + rs.getString("return_status"),
                                    "VERIFIED", "The refund ran ahead of warehouse verification.",
                                    "order_return_request", "status")));
                    return out;
                });
    }

    /** You cannot return more than was bought. */
    private AnomalyRule returnQuantityExceedsPurchased() {
        return rule("RETURN_QUANTITY_EXCEEDS_PURCHASED", AnomalyDomain.RETURN, AnomalySeverity.HIGH,
                "More units were returned on a line than were purchased",
                scope -> {
                    List<AnomalyRule.Candidate> out = new ArrayList<>();
                    jdbc.query("""
                            SELECT o.order_code, i.id, i.returned_quantity, i.quantity
                              FROM customer_order_item i
                              JOIN customer_order o ON o.id = i.customer_order_id
                             WHERE i.archive = false
                               AND COALESCE(i.returned_quantity,0) > COALESCE(i.quantity,0)
                               AND (CAST(? AS timestamptz) IS NULL OR o.created >= CAST(? AS timestamptz))
                               AND (CAST(? AS timestamptz) IS NULL OR o.created <= CAST(? AS timestamptz))
                               AND (CAST(? AS text) IS NULL OR o.order_code = CAST(? AS text))
                            """,
                            ps -> bindOrder(ps, scope),
                            (RowCallbackHandler) rs -> out.add(new AnomalyRule.Candidate(
                                    "ORDER_ITEM", String.valueOf(rs.getLong("id")),
                                    rs.getString("order_code"), rs.getString("order_code"), null,
                                    rs.getInt("returned_quantity") + " returned",
                                    "at most " + rs.getInt("quantity"),
                                    "More was returned than bought.",
                                    "customer_order_item", "returned_quantity")));
                    return out;
                });
    }

    // ── inventory ────────────────────────────────────────────────────────────

    /**
     * Returns cannot exceed what was delivered.
     *
     * <p>Reads the existing derived model — {@code returned_quantity} against
     * {@code quantity} on a delivered line — rather than introducing a second
     * inventory calculation (§45).
     */
    private AnomalyRule inventoryReleaseExceedsConsumption() {
        return rule("INVENTORY_RELEASE_EXCEEDS_CONSUMPTION", AnomalyDomain.INVENTORY, AnomalySeverity.MEDIUM,
                "Stock returned to the shelf for a product exceeds what was consumed from it",
                scope -> {
                    List<AnomalyRule.Candidate> out = new ArrayList<>();
                    jdbc.query("""
                            SELECT i.product_uuid,
                                   SUM(COALESCE(i.returned_quantity,0)) AS released,
                                   SUM(COALESCE(i.quantity,0))          AS consumed
                              FROM customer_order_item i
                              JOIN customer_order o ON o.id = i.customer_order_id
                             WHERE i.archive = false AND o.archive = false
                               AND (CAST(? AS timestamptz) IS NULL OR o.created >= CAST(? AS timestamptz))
                               AND (CAST(? AS timestamptz) IS NULL OR o.created <= CAST(? AS timestamptz))
                             GROUP BY i.product_uuid
                            HAVING SUM(COALESCE(i.returned_quantity,0)) > SUM(COALESCE(i.quantity,0))
                            """,
                            ps -> bindWindow(ps, scope),
                            (RowCallbackHandler) rs -> out.add(new AnomalyRule.Candidate(
                                    "PRODUCT", rs.getString("product_uuid"),
                                    null, null, rs.getString("product_uuid"),
                                    rs.getLong("released") + " released",
                                    "at most " + rs.getLong("consumed"),
                                    "More stock was released than consumed.",
                                    "customer_order_item", "returned_quantity")));
                    return out;
                });
    }

    // ── invoice ──────────────────────────────────────────────────────────────

    /** The invoice's own total must equal the parts it is made of. */
    private AnomalyRule invoiceTotalMismatch() {
        return rule("INVOICE_TOTAL_MISMATCH", AnomalyDomain.INVOICE, AnomalySeverity.HIGH,
                "The invoice total does not equal its taxable value plus tax, shipping and round-off",
                scope -> {
                    List<AnomalyRule.Candidate> out = new ArrayList<>();
                    jdbc.query("""
                            SELECT si.invoice_number, si.id, si.order_code,
                                   si.total_invoice_value AS actual,
                                   (COALESCE(si.taxable_value,0) + COALESCE(si.total_tax,0)
                                    + COALESCE(si.shipping_value,0) - COALESCE(si.shipping_taxable_value,0)
                                    + COALESCE(si.round_off,0)) AS expected
                              FROM sales_invoice si
                             WHERE si.status <> 'CANCELLED'
                               AND COALESCE(si.total_invoice_value,0) <>
                                   (COALESCE(si.taxable_value,0) + COALESCE(si.total_tax,0)
                                    + COALESCE(si.shipping_value,0) - COALESCE(si.shipping_taxable_value,0)
                                    + COALESCE(si.round_off,0))
                               AND (CAST(? AS timestamptz) IS NULL OR si.created_at >= CAST(? AS timestamptz))
                               AND (CAST(? AS timestamptz) IS NULL OR si.created_at <= CAST(? AS timestamptz))
                            """,
                            ps -> bindWindow(ps, scope),
                            (RowCallbackHandler) rs -> out.add(new AnomalyRule.Candidate(
                                    "INVOICE", String.valueOf(rs.getLong("id")),
                                    rs.getString("order_code"), rs.getString("order_code"), null,
                                    rs.getLong("actual") + " paise",
                                    rs.getLong("expected") + " paise",
                                    "The invoice total and its components disagree.",
                                    "sales_invoice", "total_invoice_value")));
                    return out;
                });
    }

    /**
     * The order and its invoice must agree on the supply type.
     *
     * <p>An order carrying CGST+SGST whose invoice carries IGST (or the reverse)
     * means the two documents disagree about which state the tax is owed to. Both
     * cannot be right, and whichever is wrong has sent tax to the wrong government.
     *
     * <p>Found by using Transaction 360 on a real order: the screen showed an order
     * taxed intra-state beside its own invoice taxed inter-state, and no rule then
     * existed to catch it.
     */
    private AnomalyRule invoiceSupplyTypeMismatch() {
        return rule("INVOICE_SUPPLY_TYPE_MISMATCH", AnomalyDomain.INVOICE, AnomalySeverity.HIGH,
                "The order and its invoice disagree about whether the supply is intra- or inter-state",
                scope -> {
                    List<AnomalyRule.Candidate> out = new ArrayList<>();
                    jdbc.query("""
                            SELECT o.order_code, si.id, si.invoice_number,
                                   CASE WHEN COALESCE(si.igst_amount,0) > 0 THEN 'INTER_STATE'
                                        ELSE 'INTRA_STATE' END AS invoice_supply,
                                   CASE WHEN COALESCE(o.igst_amount,0) > 0 THEN 'INTER_STATE'
                                        ELSE 'INTRA_STATE' END AS order_supply
                              FROM sales_invoice si
                              JOIN customer_order o ON o.order_code = si.order_code
                             WHERE si.status <> 'CANCELLED' AND o.archive = false
                               AND COALESCE(o.total_tax_amount,0) > 0
                               AND (CASE WHEN COALESCE(si.igst_amount,0) > 0 THEN 1 ELSE 0 END)
                                <> (CASE WHEN COALESCE(o.igst_amount,0) > 0 THEN 1 ELSE 0 END)
                               AND (CAST(? AS timestamptz) IS NULL OR o.created >= CAST(? AS timestamptz))
                               AND (CAST(? AS timestamptz) IS NULL OR o.created <= CAST(? AS timestamptz))
                               AND (CAST(? AS text) IS NULL OR o.order_code = CAST(? AS text))
                            """,
                            ps -> bindOrder(ps, scope),
                            (RowCallbackHandler) rs -> out.add(new AnomalyRule.Candidate(
                                    "INVOICE", String.valueOf(rs.getLong("id")),
                                    rs.getString("order_code"), rs.getString("order_code"), null,
                                    "invoice " + rs.getString("invoice_supply"),
                                    "order " + rs.getString("order_supply"),
                                    "Invoice " + rs.getString("invoice_number")
                                        + " and its order disagree on the supply type.",
                                    "sales_invoice", "igst_amount")));
                    return out;
                });
    }

    /**
     * A COD refund cannot have been issued, because no destination is approved."""
     *
     * <p>Authoritative: {@code p0-14-decision-register.md} records D-COD-REFUND as
     * UNDECIDED. If a refund exists against a COD-only order, money left by a route
     * nobody approved.
     */
    private AnomalyRule codRefundWithoutApprovedDestination() {
        return rule("COD_REFUND_WITHOUT_APPROVED_DESTINATION", AnomalyDomain.REFUND, AnomalySeverity.HIGH,
                "A refund exists on a cash-on-delivery order, but no COD refund destination is approved",
                scope -> {
                    List<AnomalyRule.Candidate> out = new ArrayList<>();
                    jdbc.query("""
                            SELECT pr.id, pr.order_code, pr.refund_destination
                              FROM payment_refund pr
                             WHERE pr.status IN ('REFUNDED', 'PROCESSING')
                               AND NOT EXISTS (
                                     SELECT 1 FROM payment_attempt pa
                                      WHERE pa.customer_order_id = pr.customer_order_id
                                        AND pa.gateway <> 'COD')
                               AND (CAST(? AS timestamptz) IS NULL OR pr.created_at >= CAST(? AS timestamptz))
                               AND (CAST(? AS timestamptz) IS NULL OR pr.created_at <= CAST(? AS timestamptz))
                            """,
                            ps -> bindWindow(ps, scope),
                            (RowCallbackHandler) rs -> out.add(new AnomalyRule.Candidate(
                                    "REFUND", String.valueOf(rs.getLong("id")),
                                    rs.getString("order_code"), rs.getString("order_code"), null,
                                    "destination " + String.valueOf(rs.getString("refund_destination")),
                                    "no approved destination exists",
                                    "Cash was refunded with no approved destination (D-COD-REFUND is UNDECIDED).",
                                    "payment_refund", "refund_destination")));
                    return out;
                });
    }

    // ── plumbing ─────────────────────────────────────────────────────────────

    /**
     * The common shape: an order-scoped query returning order_code, id, actual,
     * expected. Factored out because a dozen rules differ only in their predicate,
     * and repeating the row mapping would make each one harder to read, not easier.
     */
    private java.util.function.Function<AnomalyRule.ScanScope, List<AnomalyRule.Candidate>> query(
            String sql, String entityType, String table, String column) {
        return scope -> {
            List<AnomalyRule.Candidate> out = new ArrayList<>();
            jdbc.query(sql, ps -> bindOrder(ps, scope), (RowCallbackHandler) rs -> {
                String code = rs.getString(1);
                out.add(new AnomalyRule.Candidate(
                        entityType, rs.getString(2), code, code, null,
                        rs.getString("actual"), rs.getString("expected"),
                        null, table, column));
            });
            return out;
        };
    }

    private List<AnomalyRule.Candidate> query(AnomalyRule.ScanScope scope, String sql,
                                              String entityType, String table, String column) {
        return query(sql, entityType, table, column).apply(scope);
    }

    /** from, from, to, to — the bounded window every rule honours. */
    private void bindWindow(java.sql.PreparedStatement ps, AnomalyRule.ScanScope scope) throws java.sql.SQLException {
        Timestamp from = scope.from() == null ? null : Timestamp.from(scope.from());
        Timestamp to   = scope.to()   == null ? null : Timestamp.from(scope.to());
        ps.setTimestamp(1, from);
        ps.setTimestamp(2, from);
        ps.setTimestamp(3, to);
        ps.setTimestamp(4, to);
    }

    /** The window plus the optional order filter. */
    private void bindOrder(java.sql.PreparedStatement ps, AnomalyRule.ScanScope scope) throws java.sql.SQLException {
        bindWindow(ps, scope);
        String txn = scope.transactionId();
        ps.setString(5, txn);
        ps.setString(6, txn);
    }

    private AnomalyRule rule(String id, AnomalyDomain domain, AnomalySeverity severity, String description,
                             java.util.function.Function<AnomalyRule.ScanScope, List<AnomalyRule.Candidate>> body) {
        return new AnomalyRule() {
            @Override public String ruleId() { return id; }
            @Override public AnomalyDomain domain() { return domain; }
            @Override public AnomalySeverity severity() { return severity; }
            @Override public String description() { return description; }
            @Override public List<AnomalyRule.Candidate> evaluate(ScanScope scope) { return body.apply(scope); }
        };
    }

    /** How many transactions the scan looked at — reported, never estimated. */
    public int countTransactionsInScope(AnomalyRule.ScanScope scope) {
        Integer n = jdbc.query("""
                SELECT COUNT(*) FROM customer_order o
                 WHERE o.archive = false
                   AND (CAST(? AS timestamptz) IS NULL OR o.created >= CAST(? AS timestamptz))
                   AND (CAST(? AS timestamptz) IS NULL OR o.created <= CAST(? AS timestamptz))
                   AND (CAST(? AS text) IS NULL OR o.order_code = CAST(? AS text))
                """,
                ps -> bindOrder(ps, scope),
                rs -> rs.next() ? rs.getInt(1) : 0);
        return n == null ? 0 : n;
    }
}
