package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * e-Way bill applicability and submission state (spec phase 24).
 * The e-way bill number is government-issued and stays null until a real
 * portal integration returns one.
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "ewaybill_document")
public class EWayBillDocumentEntity {

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

    @Builder.Default private Long consignmentValuePaise = 0L;
    private String originStateCode;
    private String destinationStateCode;
    private String transporterName;
    private String transporterId;
    private String vehicleNumber;

    @Builder.Default private String submissionStatus = "NOT_SUBMITTED";

    // ── Government-issued. Never populated locally. ──
    @Column(name = "ewb_number")
    private String ewbNumber;
    @Column(name = "ewb_date")
    private Instant ewbDate;
    private Instant validUntil;

    private String provider;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    private Long organizationId;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    private String createdBy;
}
