package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One manual anomaly scan. A monitoring record — never a business record. */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "engineering_anomaly_scan")
public class EngineeringAnomalyScanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    /** SCAN-YYYYMMDD-NNNN — the identifier a person quotes. */
    @Column(name = "scan_number", nullable = false, updatable = false)
    private String scanNumber;

    @Column(name = "triggered_by", nullable = false, updatable = false)
    private String triggeredBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(nullable = false)
    private String status;

    // ── the immutable scope, captured at creation ──
    // updatable = false is the guarantee, not a convention: a running scan reads
    // these and nothing may rewrite them mid-flight.
    @Column(name = "scope_from", updatable = false)
    private Instant scopeFrom;

    @Column(name = "scope_to", updatable = false)
    private Instant scopeTo;

    @Column(name = "scope_transaction_id", updatable = false)
    private String scopeTransactionId;

    @Column(name = "scope_product_id", updatable = false)
    private String scopeProductId;

    @Column(updatable = false)
    private String domains;

    @Column(name = "rules_executed", nullable = false)
    @Builder.Default
    private Integer rulesExecuted = 0;

    @Column(name = "rules_failed", nullable = false)
    @Builder.Default
    private Integer rulesFailed = 0;

    @Column(name = "transactions_scanned", nullable = false)
    @Builder.Default
    private Integer transactionsScanned = 0;

    @Column(name = "anomalies_found", nullable = false)
    @Builder.Default
    private Integer anomaliesFound = 0;

    @Column(name = "critical_count", nullable = false)
    @Builder.Default
    private Integer criticalCount = 0;

    @Column(name = "high_count", nullable = false)
    @Builder.Default
    private Integer highCount = 0;

    @Column(name = "medium_count", nullable = false)
    @Builder.Default
    private Integer mediumCount = 0;

    @Column(name = "low_count", nullable = false)
    @Builder.Default
    private Integer lowCount = 0;

    @Column(name = "info_count", nullable = false)
    @Builder.Default
    private Integer infoCount = 0;

    @Column(name = "failure_reason")
    private String failureReason;

    @Builder.Default
    private Instant created = Instant.now();

    private Instant modified;
}
