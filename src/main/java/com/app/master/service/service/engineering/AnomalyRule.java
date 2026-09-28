package com.app.master.service.service.engineering;

import com.app.master.service.core.engineering.AnomalyDomain;
import com.app.master.service.core.engineering.AnomalySeverity;

import java.time.Instant;
import java.util.List;

/**
 * One deterministic check against business data.
 *
 * <p>A rule reads and returns candidate findings. It never writes, never decides
 * severity dynamically, and never repairs anything. Every rule is independent: one
 * failing marks itself failed and the scan continues, ending {@code PARTIAL} (§54).
 *
 * <p>A rule must be able to say <em>which table and which column</em> holds the
 * anomalous value, because that is what the finding encrypts and what an engineer
 * ultimately needs. A rule that cannot name them is not implementable here.
 */
public interface AnomalyRule {

    /** Stable identifier, e.g. {@code GST_TOTAL_MISMATCH}. Plaintext and searchable. */
    String ruleId();

    AnomalyDomain domain();

    AnomalySeverity severity();

    /** One line, shown in the anomaly list. */
    String description();

    /**
     * Runs the check.
     *
     * @param scope the scan's immutable scope — a rule must respect it rather than
     *              reading the whole table, so a narrow scan stays narrow (§37)
     */
    List<Candidate> evaluate(ScanScope scope);

    /**
     * What a rule found, before it is encrypted and persisted.
     *
     * <p>{@code tableName} and {@code columnName} are plaintext <em>here</em> and
     * only here — in memory, on the way to encryption. They are never returned from
     * an API or written to a column in this form.
     */
    record Candidate(
            String entityType,
            String entityId,
            String transactionId,
            String orderId,
            String productId,
            String actualValue,
            String expectedValue,
            String detail,
            String tableName,
            String columnName
    ) {}

    /** The bounded window and filters a scan was created with. */
    record ScanScope(
            Instant from,
            Instant to,
            String transactionId,
            String productId,
            List<AnomalyDomain> domains
    ) {
        public boolean includes(AnomalyDomain domain) {
            return domains == null || domains.isEmpty() || domains.contains(domain);
        }
    }
}
