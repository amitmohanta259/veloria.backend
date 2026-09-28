package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One anomaly.
 *
 * <p>The table and column that hold the anomalous data are stored encrypted; the
 * identifiers needed to find, filter and explain the finding stay plaintext, which
 * is what lets the list be queried without decrypting anything.
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "engineering_anomaly_finding")
public class EngineeringAnomalyFindingEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    @Column(name = "scan_id", nullable = false)
    private Long scanId;

    /** Deterministic, over non-sensitive identifiers only. Unique in the database. */
    @Column(name = "anomaly_fingerprint", nullable = false)
    private String anomalyFingerprint;

    @Column(nullable = false)
    private String severity;

    @Column(nullable = false)
    private String domain;

    @Column(name = "rule_id", nullable = false)
    private String ruleId;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id")
    private String entityId;

    /** Null for a configuration-level anomaly; never invented. */
    @Column(name = "transaction_id")
    private String transactionId;

    @Column(name = "order_id")
    private String orderId;

    @Column(name = "product_id")
    private String productId;

    @Column(name = "actual_value")
    private String actualValue;

    @Column(name = "expected_value")
    private String expectedValue;

    @Column(columnDefinition = "text")
    private String description;

    // ── encrypted schema identifiers ──
    @Column(name = "encrypted_table_name", nullable = false, columnDefinition = "text")
    private String encryptedTableName;

    @Column(name = "encrypted_column_name", nullable = false, columnDefinition = "text")
    private String encryptedColumnName;

    /** The hour's data key, wrapped. Never the plaintext key. */
    @Column(name = "encrypted_data_key", nullable = false, columnDefinition = "text")
    private String encryptedDataKey;

    /** LOCAL or AWS_KMS — so a development finding is never taken for a production one. */
    @Column(name = "key_provider", nullable = false)
    private String keyProvider;

    @Column(name = "key_version", nullable = false)
    private String keyVersion;

    @Column(nullable = false)
    private String algorithm;

    @Column(name = "table_nonce", nullable = false)
    private String tableNonce;

    @Column(name = "table_auth_tag", nullable = false)
    private String tableAuthTag;

    @Column(name = "column_nonce", nullable = false)
    private String columnNonce;

    @Column(name = "column_auth_tag", nullable = false)
    private String columnAuthTag;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    @Column(name = "first_detected_at", nullable = false)
    private Instant firstDetectedAt;

    @Column(name = "last_detected_at", nullable = false)
    private Instant lastDetectedAt;

    @Column(name = "occurrence_count", nullable = false)
    @Builder.Default
    private Integer occurrenceCount = 1;

    /** The scanner writes OPEN and only OPEN. */
    @Column(nullable = false)
    private String status;

    @Builder.Default
    private Instant created = Instant.now();

    private Instant modified;
}
