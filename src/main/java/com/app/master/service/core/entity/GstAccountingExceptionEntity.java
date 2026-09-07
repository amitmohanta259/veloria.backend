package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A GST operation that could not complete.
 *
 * Replaces the silent catch blocks the audit found in order placement, vendor
 * invoice upload and return verification. The business operation still succeeds,
 * but the failure is now a durable, reviewable record instead of a log line.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "gst_accounting_exception")
public class GstAccountingExceptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SALE_MOVEMENT, PURCHASE_MOVEMENT, RETURN_CREDIT_NOTE, OUTPUT_TAX_WRITE */
    @Column(nullable = false)
    private String operation;

    private String sourceType;
    private Long sourceId;
    private String sourceDocumentNumber;

    /** PENDING_REVIEW, RESOLVED, IGNORED */
    @Builder.Default
    @Column(nullable = false)
    private String status = "PENDING_REVIEW";

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String errorDetail;

    private Instant resolvedAt;
    private String resolvedBy;

    @Column(columnDefinition = "TEXT")
    private String resolutionNotes;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    private Long organizationId;

    // ── Classification, for exceptions raised by historical-data review ──────
    // Runtime-failure rows leave these null; detector rows always set them.

    /** MISSING_GSTIN, INVALID_GSTIN, MISSING_HSN, LEDGER_INCONSISTENCY, … */
    private String exceptionType;

    /** CRITICAL, HIGH, MEDIUM, LOW */
    private String severity;

    private String taxPeriod;

    private Long gstRegistrationId;

    private String reviewedBy;

    private Instant reviewedAt;

    @Column(columnDefinition = "TEXT")
    private String reviewComments;
}
