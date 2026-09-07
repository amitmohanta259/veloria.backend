package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * e-Invoice applicability and submission state (spec phase 23).
 *
 * The applicability decision is made here. IRN, acknowledgement number and the
 * signed QR payload are government-issued and stay null until a real IRP
 * integration returns them — a database constraint enforces that an IRN cannot
 * exist without an acknowledgement.
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "einvoice_document")
public class EInvoiceDocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long salesInvoiceId;
    private String documentNumber;
    private LocalDate documentDate;

    /** APPLICABLE, NOT_APPLICABLE, REQUIRES_REVIEW */
    @Column(nullable = false)
    private String applicability;

    @Column(columnDefinition = "TEXT")
    private String applicabilityReason;

    /** NOT_SUBMITTED, SUBMITTED, ACKNOWLEDGED, FAILED, CANCELLED */
    @Builder.Default private String submissionStatus = "NOT_SUBMITTED";
    private String requestReference;

    // ── Government-issued. Never populated locally. ──
    private String irn;
    private String acknowledgementNumber;
    private Instant acknowledgementDate;

    @Column(columnDefinition = "TEXT")
    private String signedQrPayload;

    private String provider;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    private Long organizationId;
    private Long gstRegistrationId;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    private String createdBy;
}
