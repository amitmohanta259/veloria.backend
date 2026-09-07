package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The outcome of matching one purchase invoice against GSTR-2B.
 * Differences are recorded explicitly — a mismatch is never silently matched
 * (spec section 10).
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "itc_reconciliation")
public class ItcReconciliationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long inputTaxId;
    @Column(name = "gstr2b_record_id")
    private Long gstr2bRecordId;
    private String taxPeriod;

    @Column(nullable = false)
    private String matchStatus;

    @Builder.Default private Long taxableDifferencePaise = 0L;
    @Builder.Default private Long cgstDifferencePaise = 0L;
    @Builder.Default private Long sgstDifferencePaise = 0L;
    @Builder.Default private Long igstDifferencePaise = 0L;
    @Builder.Default private Long totalDifferencePaise = 0L;

    @Column(columnDefinition = "TEXT")
    private String differenceNotes;

    private Long organizationId;

    @Builder.Default
    private Instant reconciledAt = Instant.now();
    private String reconciledBy;
}
