package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * An immutable prepared return (spec sections 25 and 26).
 *
 * dataHash fingerprints the source movements. If the ledger changes afterwards
 * the snapshot is marked requiresRegeneration rather than being silently
 * rewritten. status never reaches FILED without an acknowledgement number.
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "gst_return_snapshot")
public class GstReturnSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** GSTR1 or GSTR3B */
    @Column(nullable = false)
    private String returnType;

    @Column(nullable = false)
    private String taxPeriod;

    private String financialYear;

    /** Internal dataset version — not an official government schema version. */
    private String schemaVersion;

    /** NOT_STARTED, DRAFT, PREPARED, UNDER_REVIEW, READY_TO_FILE, SUBMITTED, FILED */
    @Builder.Default private String status = "PREPARED";

    private String dataHash;

    @Column(columnDefinition = "TEXT")
    private String payload;

    @Builder.Default private Integer sourceMovementCount = 0;
    @Builder.Default private Boolean requiresRegeneration = Boolean.FALSE;

    private String filingReference;
    private String acknowledgementNumber;
    private Instant filedAt;
    private String filedBy;

    private Long organizationId;
    private Long gstRegistrationId;

    @Builder.Default
    private Instant preparedAt = Instant.now();
    private String preparedBy;
}
