package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A debit note — an upward adjustment to an already-issued supply
 * (spec section 23). Flows into the ledger and return preparation the same way
 * a credit note does, but with positive amounts.
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "gst_debit_note")
public class GstDebitNoteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String debitNoteNumber;

    private LocalDate debitNoteDate;

    private String originalOrderCode;
    private Long originalOrderId;
    private LocalDate originalInvoiceDate;

    private String customerId;
    private String customerName;
    private String customerGstin;

    private String taxPeriod;
    private String financialYear;
    private String originalTaxPeriod;

    @Builder.Default private Long taxableValuePaise = 0L;
    @Builder.Default private Long cgstPaise = 0L;
    @Builder.Default private Long sgstPaise = 0L;
    @Builder.Default private Long igstPaise = 0L;
    @Builder.Default private Long cessPaise = 0L;
    @Builder.Default private Long totalDebitPaise = 0L;

    private String supplyType;
    private String placeOfSupply;

    /** PRICE_REVISION, SHORT_BILLED, RATE_CORRECTION, OTHER */
    private String reasonCode;

    @Builder.Default private String status = "PENDING_REVIEW";
    @Builder.Default private String reportingStatus = "NOT_REPORTED";

    @Column(columnDefinition = "TEXT")
    private String notes;

    private Long movementId;
    private String approvedBy;
    private Instant approvedAt;
    private Instant issuedAt;

    private Long organizationId;
    private Long gstRegistrationId;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    private String createdBy;
}
