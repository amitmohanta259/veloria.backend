package com.app.master.service.core.response.engineering;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What the Engineering API returns.
 *
 * <p>Safe DTOs: no ciphertext, no nonce, no tag, no wrapped key, no key version.
 * The browser gets either the decrypted table and column (for an authorized
 * reader), the marker {@code ENCRYPTED} (for one who may see findings but not
 * schema), or {@code DECRYPTION_UNAVAILABLE} (when the key cannot be recovered).
 *
 * <p>Key-management metadata is withheld deliberately. It would tell a client
 * nothing it can act on, and §35 requires the browser never to receive key
 * material — the least that satisfies both is to send none of it.
 */
public final class EngineeringDtos {

    private EngineeringDtos() {}

    public record ScanSummary(
            String scanId,
            String status,
            String triggeredBy,
            Instant requestedAt,
            Instant startedAt,
            Instant completedAt,
            Long durationMillis,
            int rulesExecuted,
            int rulesFailed,
            int transactionsScanned,
            int anomaliesFound,
            int criticalCount,
            int highCount,
            int mediumCount,
            int lowCount,
            int infoCount,
            String failureReason
    ) {}

    /** The run endpoint's 202 body. */
    public record ScanAccepted(String scanId, String status) {}

    public record AnomalyRow(
            String anomalyId,
            String scanId,
            String severity,
            String domain,
            String ruleId,
            String transactionId,
            String description,
            String actualValue,
            String expectedValue,
            Instant detectedAt,
            String status
    ) {}

    public record AnomalyDetail(
            String anomalyId,
            String scanId,
            String severity,
            String domain,
            String ruleId,
            String entityType,
            String entityId,
            String transactionId,
            String orderId,
            String productId,
            String actualValue,
            String expectedValue,
            String description,
            Instant detectedAt,
            Instant firstDetectedAt,
            Instant lastDetectedAt,
            int occurrenceCount,
            String status,
            /** Decrypted for an authorized reader; ENCRYPTED or DECRYPTION_UNAVAILABLE otherwise. */
            String tableName,
            String columnName,
            /** Whether this reader was shown the schema identifiers. */
            boolean schemaVisible,
            /** LOCAL or AWS_KMS — so a development finding is identifiable as one. */
            String keyProvider
    ) {}

    public record DashboardSummary(
            ScanSummary lastScan,
            ScanSummary lastFinishedScan,
            boolean scanInProgress,
            long openAnomalies,
            Map<String, Long> bySeverity,
            Map<String, Long> byDomain,
            List<ScanSummary> history
    ) {}

    /** One aggregate compared against the previous finished scan. */
    public record FluctuationRow(String metric, long previous, long current, long delta, long relatedAnomalies) {}

    public record TimelineEvent(String event, Instant at, String detail) {}

    public record Money(String label, long paise) {}

    public record Transaction360(
            String transactionId,
            Map<String, Object> order,
            List<Map<String, Object>> items,
            Map<String, Object> invoice,
            Map<String, Object> gst,
            List<Map<String, Object>> payments,
            List<Map<String, Object>> returns,
            List<Map<String, Object>> refunds,
            List<Map<String, Object>> journals,
            List<Money> reconciliation,
            List<TimelineEvent> timeline,
            List<AnomalyRow> anomalies
    ) {}
}
