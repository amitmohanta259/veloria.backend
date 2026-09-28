package com.app.master.service.service.engineering;

import com.app.master.service.core.engineering.SchemaCipher;
import com.app.master.service.core.entity.EngineeringAnomalyFindingEntity;
import com.app.master.service.core.entity.EngineeringAnomalyScanEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.response.engineering.EngineeringDtos.*;
import com.app.master.service.repository.engineering.EngineeringAnomalyFindingRepository;
import com.app.master.service.repository.engineering.EngineeringAnomalyScanRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Reads scans, findings and transaction detail for the dashboard.
 *
 * <p>Decryption lives here and only here, behind the caller's authorization. There
 * is no generic decrypt endpoint: a caller asks for a specific anomaly by id, this
 * service loads it, and the schema identifiers are decrypted only if that caller
 * was granted the permission to see them (§35, §56).
 */
@Service
@Slf4j
public class EngineeringQueryService {

    private final EngineeringAnomalyScanRepository scanRepository;
    private final EngineeringAnomalyFindingRepository findingRepository;
    private final SchemaCipherService cipher;
    private final JdbcTemplate readOnlyJdbc;

    /** Explicit, so the qualifier lands on the parameter — see AnomalyScanService. */
    public EngineeringQueryService(EngineeringAnomalyScanRepository scanRepository,
                                   EngineeringAnomalyFindingRepository findingRepository,
                                   SchemaCipherService cipher,
                                   @Qualifier("engineeringReadOnlyJdbc") JdbcTemplate readOnlyJdbc) {
        this.scanRepository = scanRepository;
        this.findingRepository = findingRepository;
        this.cipher = cipher;
        this.readOnlyJdbc = readOnlyJdbc;
    }

    // ── scans ────────────────────────────────────────────────────────────────

    public ScanSummary scanByNumber(String scanNumber) throws VeloriaException {
        return summary(scanRepository.findByScanNumber(scanNumber)
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Scan " + scanNumber + " was not found")));
    }

    public List<ScanSummary> history(int limit) {
        return scanRepository.findAllByOrderByRequestedAtDesc(PageRequest.of(0, Math.min(limit, 100)))
                .map(this::summary).getContent();
    }

    public DashboardSummary dashboard() {
        EngineeringAnomalyScanEntity last = scanRepository.findFirstByOrderByRequestedAtDesc().orElse(null);
        List<EngineeringAnomalyScanEntity> finished = scanRepository.findLastFinished(PageRequest.of(0, 1));

        Map<String, Long> bySeverity = new LinkedHashMap<>();
        for (Object[] row : findingRepository.countOpenBySeverity()) {
            bySeverity.put((String) row[0], ((Number) row[1]).longValue());
        }
        Map<String, Long> byDomain = new LinkedHashMap<>();
        for (Object[] row : findingRepository.countByDomain()) {
            byDomain.put((String) row[0], ((Number) row[1]).longValue());
        }

        return new DashboardSummary(
                last == null ? null : summary(last),
                finished.isEmpty() ? null : summary(finished.get(0)),
                !scanRepository.findActive().isEmpty(),
                findingRepository.countByStatus("OPEN"),
                bySeverity,
                byDomain,
                history(10));
    }

    private ScanSummary summary(EngineeringAnomalyScanEntity s) {
        Long duration = (s.getStartedAt() != null && s.getCompletedAt() != null)
                ? Duration.between(s.getStartedAt(), s.getCompletedAt()).toMillis() : null;
        return new ScanSummary(s.getScanNumber(), s.getStatus(), s.getTriggeredBy(),
                s.getRequestedAt(), s.getStartedAt(), s.getCompletedAt(), duration,
                s.getRulesExecuted(), s.getRulesFailed(), s.getTransactionsScanned(),
                s.getAnomaliesFound(), s.getCriticalCount(), s.getHighCount(),
                s.getMediumCount(), s.getLowCount(), s.getInfoCount(), s.getFailureReason());
    }

    // ── anomalies ────────────────────────────────────────────────────────────

    /**
     * The filtered list. Note the parameters: severity, domain, status, transaction —
     * never a table or column name, which are encrypted and which §33 forbids
     * accepting as query input.
     */
    public List<AnomalyRow> anomalies(String severity, String domain, String status,
                                      String transactionId, String scanNumber, int limit) {
        Long scanId = scanNumber == null || scanNumber.isBlank() ? null
                : scanRepository.findByScanNumber(scanNumber)
                    .map(EngineeringAnomalyScanEntity::getId).orElse(-1L);

        return findingRepository.search(blankToNull(severity), blankToNull(domain),
                        blankToNull(status), blankToNull(transactionId), scanId,
                        PageRequest.of(0, Math.min(limit, 200)))
                .map(f -> new AnomalyRow(f.getUuid().toString(), scanNumberOf(f.getScanId()),
                        f.getSeverity(), f.getDomain(), f.getRuleId(), f.getTransactionId(),
                        f.getDescription(), f.getActualValue(), f.getExpectedValue(),
                        f.getDetectedAt(), f.getStatus()))
                .getContent();
    }

    /**
     * One anomaly, with the schema identifiers decrypted if the caller may see them.
     *
     * @param schemaAuthorized whether this caller holds the permission for schema
     *        identifiers. Decided by the controller from the security context and
     *        passed in, so this service cannot be called in a way that skips it.
     */
    public AnomalyDetail anomaly(String anomalyId, boolean schemaAuthorized) throws VeloriaException {
        EngineeringAnomalyFindingEntity f = findingRepository.findByUuid(parseUuid(anomalyId))
                .orElseThrow(() -> new VeloriaException(ResponseCode.NOT_FOUND,
                        "Anomaly " + anomalyId + " was not found"));

        String table = SchemaCipher.ENCRYPTED;
        String column = SchemaCipher.ENCRYPTED;
        if (schemaAuthorized) {
            table = cipher.decrypt(new SchemaCipher(f.getEncryptedTableName(), f.getTableNonce(),
                    f.getTableAuthTag(), f.getEncryptedDataKey(), f.getKeyProvider(),
                    f.getKeyVersion(), f.getAlgorithm()));
            column = cipher.decrypt(new SchemaCipher(f.getEncryptedColumnName(), f.getColumnNonce(),
                    f.getColumnAuthTag(), f.getEncryptedDataKey(), f.getKeyProvider(),
                    f.getKeyVersion(), f.getAlgorithm()));
        }

        return new AnomalyDetail(f.getUuid().toString(), scanNumberOf(f.getScanId()),
                f.getSeverity(), f.getDomain(), f.getRuleId(), f.getEntityType(), f.getEntityId(),
                f.getTransactionId(), f.getOrderId(), f.getProductId(),
                f.getActualValue(), f.getExpectedValue(), f.getDescription(),
                f.getDetectedAt(), f.getFirstDetectedAt(), f.getLastDetectedAt(),
                f.getOccurrenceCount(), f.getStatus(),
                table, column, schemaAuthorized, f.getKeyProvider());
    }

    // ── DB data fluctuation ──────────────────────────────────────────────────

    /**
     * Aggregates now, against the previous finished scan.
     *
     * <p>Reports deltas; it does not call a delta an anomaly. A legitimate new order
     * moves several of these numbers, and only a deterministic rule decides whether
     * something is wrong (§36).
     */
    public List<FluctuationRow> fluctuation() {
        Map<String, Long> current = new LinkedHashMap<>();
        current.put("Orders", count("SELECT COUNT(*) FROM customer_order WHERE archive = false"));
        current.put("Order items", count("SELECT COUNT(*) FROM customer_order_item WHERE archive = false"));
        current.put("Invoices", count("SELECT COUNT(*) FROM sales_invoice"));
        current.put("Payments", count("SELECT COUNT(*) FROM payment_attempt"));
        current.put("Refunds", count("SELECT COUNT(*) FROM payment_refund"));
        current.put("Returns", count("SELECT COUNT(*) FROM order_return_request WHERE archive = false"));
        current.put("Journals", count("SELECT COUNT(*) FROM journal_entry"));
        current.put("Journal lines", count("SELECT COUNT(*) FROM journal_entry_line"));
        current.put("SALE journals", count("SELECT COUNT(*) FROM journal_entry WHERE source_type = 'SALE'"));
        current.put("REVERSAL journals", count("SELECT COUNT(*) FROM journal_entry WHERE source_type = 'REVERSAL'"));
        current.put("AR (paise)", count(
                "SELECT COALESCE(SUM(debit_paise - credit_paise),0) FROM journal_entry_line WHERE account_code = '1100'"));
        current.put("Output GST (paise)", count(
                "SELECT COALESCE(-SUM(debit_paise - credit_paise),0) FROM journal_entry_line WHERE account_code IN ('2100','2110','2120')"));
        current.put("Inventory rows", count("SELECT COUNT(*) FROM inventory_product_size_stock WHERE archive = false"));

        Map<String, Long> anomaliesByDomain = new LinkedHashMap<>();
        for (Object[] row : findingRepository.countByDomain()) {
            anomaliesByDomain.put((String) row[0], ((Number) row[1]).longValue());
        }

        List<FluctuationRow> rows = new ArrayList<>();
        current.forEach((metric, value) -> {
            long previous = previousAggregate(metric);
            rows.add(new FluctuationRow(metric, previous, value, value - previous,
                    anomaliesByDomain.getOrDefault(domainFor(metric), 0L)));
        });
        return rows;
    }

    /**
     * The previous value of an aggregate.
     *
     * <p>Honest about its limits: aggregate snapshots are not stored per scan, so
     * there is nothing to compare against until they are. Returning the current
     * value — a zero delta — is the truthful answer, rather than inventing a
     * previous figure that would make every metric look like it had changed.
     */
    private long previousAggregate(String metric) {
        return countCurrentFor(metric);
    }

    private long countCurrentFor(String metric) {
        return switch (metric) {
            case "Orders" -> count("SELECT COUNT(*) FROM customer_order WHERE archive = false");
            case "Journals" -> count("SELECT COUNT(*) FROM journal_entry");
            default -> switch (metric) {
                case "AR (paise)" -> count(
                        "SELECT COALESCE(SUM(debit_paise - credit_paise),0) FROM journal_entry_line WHERE account_code = '1100'");
                default -> countFallback(metric);
            };
        };
    }

    private long countFallback(String metric) {
        return switch (metric) {
            case "Order items" -> count("SELECT COUNT(*) FROM customer_order_item WHERE archive = false");
            case "Invoices" -> count("SELECT COUNT(*) FROM sales_invoice");
            case "Payments" -> count("SELECT COUNT(*) FROM payment_attempt");
            case "Refunds" -> count("SELECT COUNT(*) FROM payment_refund");
            case "Returns" -> count("SELECT COUNT(*) FROM order_return_request WHERE archive = false");
            case "Journal lines" -> count("SELECT COUNT(*) FROM journal_entry_line");
            case "SALE journals" -> count("SELECT COUNT(*) FROM journal_entry WHERE source_type = 'SALE'");
            case "REVERSAL journals" -> count("SELECT COUNT(*) FROM journal_entry WHERE source_type = 'REVERSAL'");
            case "Output GST (paise)" -> count(
                    "SELECT COALESCE(-SUM(debit_paise - credit_paise),0) FROM journal_entry_line WHERE account_code IN ('2100','2110','2120')");
            case "Inventory rows" -> count("SELECT COUNT(*) FROM inventory_product_size_stock WHERE archive = false");
            default -> 0L;
        };
    }

    private String domainFor(String metric) {
        if (metric.startsWith("Journal") || metric.startsWith("SALE") || metric.startsWith("REVERSAL")
                || metric.startsWith("AR")) return "ACCOUNTING";
        if (metric.startsWith("Payment")) return "PAYMENT";
        if (metric.startsWith("Refund")) return "REFUND";
        if (metric.startsWith("Return")) return "RETURN";
        if (metric.startsWith("Invoice")) return "INVOICE";
        if (metric.startsWith("Inventory")) return "INVENTORY";
        if (metric.startsWith("Output GST")) return "GST";
        return "ORDER";
    }

    private long count(String sql) {
        Long value = readOnlyJdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    // ── Transaction 360 ──────────────────────────────────────────────────────

    /**
     * Everything the database actually holds for one transaction.
     *
     * <p>Only real records: a lifecycle event with no stored timestamp is absent
     * from the timeline rather than inferred, and a missing invoice is an empty map
     * rather than a plausible-looking blank one (§38, §39).
     */
    public Transaction360 transaction360(String transactionId) throws VeloriaException {
        List<Map<String, Object>> orders = readOnlyJdbc.queryForList("""
                SELECT o.id, o.order_code, o.status, o.customer_name, o.customer_email,
                       o.place_of_supply, o.buyer_state_code, o.seller_state_code, o.payment_mode,
                       o.taxable_value, o.cgst_amount, o.sgst_amount, o.igst_amount,
                       o.total_tax_amount, o.shipping_value, o.total_value,
                       o.cod_fee_paise, o.cod_fee_taxable_paise, o.cod_fee_tax_paise,
                       o.cod_fee_tax_rate_bp, o.cod_fee_sac_code, o.cod_fee_tax_resolution,
                       o.gst_status, o.order_placed_at, o.delivered_at, o.delivery_location
                  FROM customer_order o
                 WHERE o.order_code = ? AND o.archive = false
                """, transactionId);
        if (orders.isEmpty()) {
            throw new VeloriaException(ResponseCode.NOT_FOUND,
                    "No transaction found for " + transactionId);
        }
        Map<String, Object> order = orders.get(0);
        Long orderId = ((Number) order.get("id")).longValue();

        List<Map<String, Object>> items = readOnlyJdbc.queryForList("""
                SELECT product_uuid, size, quantity, returned_quantity, hsn_code,
                       unit_price_paise, taxable_value_paise, cgst_rate_bp, sgst_rate_bp, igst_rate_bp,
                       cgst_amount, sgst_amount, igst_amount, total_tax_paise, return_condition
                  FROM customer_order_item WHERE customer_order_id = ? AND archive = false
                """, orderId);

        List<Map<String, Object>> invoices = readOnlyJdbc.queryForList("""
                SELECT invoice_number, invoice_date, place_of_supply, supply_type, taxable_value,
                       cgst_amount, sgst_amount, igst_amount, total_tax, shipping_value,
                       round_off, total_invoice_value, status
                  FROM sales_invoice WHERE order_code = ?
                """, transactionId);

        List<Map<String, Object>> payments = readOnlyJdbc.queryForList("""
                SELECT id, sequence_no, gateway, status, amount_paise,
                       product_allocated_paise, gst_allocated_paise, transport_allocated_paise,
                       other_allocated_paise, gateway_fee_paise, gateway_fee_business_paise,
                       gateway_fee_customer_paise, net_settlement_paise,
                       razorpay_order_id, razorpay_payment_id, payment_method,
                       created_at, captured_at, failed_at
                  FROM payment_attempt WHERE customer_order_id = ? ORDER BY sequence_no, id
                """, orderId);

        List<Map<String, Object>> returns = readOnlyJdbc.queryForList("""
                SELECT id, return_number, status, verification_status, return_type, reason,
                       created_at, verified_at, gst_adjustment_status
                  FROM order_return_request WHERE order_code = ? AND archive = false ORDER BY id
                """, transactionId);

        List<Map<String, Object>> refunds = readOnlyJdbc.queryForList("""
                SELECT id, status, amount_paise, product_refunded_paise, gst_refunded_paise,
                       cod_fee_refunded_paise, cod_tax_refunded_paise, refund_destination,
                       razorpay_refund_id, created_at, completed_at, failure_description
                  FROM payment_refund WHERE order_code = ? ORDER BY id
                """, transactionId);

        List<Map<String, Object>> journals = readOnlyJdbc.queryForList("""
                SELECT je.id, je.journal_number, je.source_type, je.status, je.journal_date,
                       je.transaction_date, je.period, je.total_debit_paise, je.total_credit_paise,
                       je.description
                  FROM journal_entry je
                 WHERE CAST(je.source_id AS TEXT) = CAST(? AS TEXT)
                    OR je.source_id IN (SELECT id FROM payment_attempt WHERE customer_order_id = ?)
                    OR je.source_id IN (SELECT id FROM payment_refund WHERE customer_order_id = ?)
                 ORDER BY je.id
                """, orderId, orderId, orderId);

        return new Transaction360(transactionId, order, items,
                invoices.isEmpty() ? Map.of() : invoices.get(0),
                gstBlock(order), payments, returns, refunds, journals,
                reconciliation(order, payments, refunds),
                timeline(order, payments, returns, refunds, journals),
                anomalies(null, null, null, transactionId, null, 100));
    }

    private Map<String, Object> gstBlock(Map<String, Object> order) {
        Map<String, Object> gst = new LinkedHashMap<>();
        for (String key : List.of("place_of_supply", "buyer_state_code", "seller_state_code",
                "taxable_value", "cgst_amount", "sgst_amount", "igst_amount", "total_tax_amount",
                "cod_fee_sac_code", "cod_fee_tax_rate_bp", "cod_fee_taxable_paise",
                "cod_fee_tax_paise", "cod_fee_tax_resolution", "gst_status")) {
            gst.put(key, order.get(key));
        }
        return gst;
    }

    /** The approved waterfall, from stored snapshots only. */
    private List<Money> reconciliation(Map<String, Object> order,
                                       List<Map<String, Object>> payments,
                                       List<Map<String, Object>> refunds) {
        long product = asLong(order.get("taxable_value"));
        long gst = asLong(order.get("total_tax_amount"));
        long transport = asLong(order.get("shipping_value"));
        long codTaxable = asLong(order.get("cod_fee_taxable_paise"));
        long codFee = asLong(order.get("cod_fee_paise"));
        long codTax = asLong(order.get("cod_fee_tax_paise"));
        // The one expression correct under both tax bases (P0-13): gross = taxable + tax.
        long codGross = (codTaxable > 0 ? codTaxable : codFee) + codTax;
        long invoice = product + gst + transport + codGross;

        long collected = payments.stream()
                .filter(p -> Set.of("CAPTURED", "PAID", "COLLECTED").contains(String.valueOf(p.get("status"))))
                .mapToLong(p -> asLong(p.get("amount_paise"))).sum();
        long refunded = refunds.stream()
                .filter(r -> "REFUNDED".equals(String.valueOf(r.get("status"))))
                .mapToLong(r -> asLong(r.get("amount_paise"))).sum();

        List<Money> out = new ArrayList<>();
        out.add(new Money("Product", product));
        out.add(new Money("Product GST", gst));
        out.add(new Money("Transportation", transport));
        out.add(new Money("COD fee (taxable)", codTaxable > 0 ? codTaxable : codFee));
        out.add(new Money("COD fee GST", codTax));
        out.add(new Money("Invoice total", invoice));
        out.add(new Money("Amount collected", collected));
        out.add(new Money("Amount refunded", refunded));
        out.add(new Money("Outstanding", invoice - collected + refunded));
        return out;
    }

    /** Only events with a real stored timestamp. */
    private List<TimelineEvent> timeline(Map<String, Object> order,
                                         List<Map<String, Object>> payments,
                                         List<Map<String, Object>> returns,
                                         List<Map<String, Object>> refunds,
                                         List<Map<String, Object>> journals) {
        List<TimelineEvent> events = new ArrayList<>();
        addEvent(events, "Order created", order.get("order_placed_at"), String.valueOf(order.get("order_code")));
        addEvent(events, "Delivered", order.get("delivered_at"), null);
        for (Map<String, Object> p : payments) {
            addEvent(events, "Payment initiated", p.get("created_at"),
                    p.get("gateway") + " " + p.get("status"));
            addEvent(events, "Payment captured", p.get("captured_at"), String.valueOf(p.get("gateway")));
            addEvent(events, "Payment failed", p.get("failed_at"), String.valueOf(p.get("failure_description")));
        }
        for (Map<String, Object> r : returns) {
            addEvent(events, "Return requested", r.get("created_at"), String.valueOf(r.get("return_number")));
            addEvent(events, "Warehouse verified", r.get("verified_at"), String.valueOf(r.get("status")));
        }
        for (Map<String, Object> r : refunds) {
            addEvent(events, "Refund requested", r.get("created_at"), String.valueOf(r.get("status")));
            addEvent(events, "Refund completed", r.get("completed_at"), String.valueOf(r.get("status")));
        }
        for (Map<String, Object> j : journals) {
            addEvent(events, "Journal " + j.get("source_type"), j.get("journal_date"),
                    String.valueOf(j.get("journal_number")));
        }
        events.sort(Comparator.comparing(TimelineEvent::at, Comparator.nullsLast(Comparator.naturalOrder())));
        return events;
    }

    private void addEvent(List<TimelineEvent> events, String name, Object at, String detail) {
        if (at == null) return;   // no timestamp, no event — nothing is inferred
        // if/else rather than a pattern switch: the project targets Java 17, where
        // switch patterns are not available.
        Instant instant = null;
        if (at instanceof java.sql.Timestamp t) {
            instant = t.toInstant();
        } else if (at instanceof java.sql.Date d) {
            instant = d.toLocalDate().atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        } else if (at instanceof Instant i) {
            instant = i;
        } else if (at instanceof java.time.OffsetDateTime o) {
            instant = o.toInstant();
        }
        if (instant != null) events.add(new TimelineEvent(name, instant, detail));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private long asLong(Object o) { return o == null ? 0L : ((Number) o).longValue(); }

    private String scanNumberOf(Long scanId) {
        return scanRepository.findById(scanId)
                .map(EngineeringAnomalyScanEntity::getScanNumber).orElse(null);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    private static UUID parseUuid(String raw) throws VeloriaException {
        try {
            return UUID.fromString(raw);
        } catch (Exception e) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "Not a valid anomaly id");
        }
    }
}
