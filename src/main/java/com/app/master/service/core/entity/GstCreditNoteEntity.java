package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "gst_credit_note")
public class GstCreditNoteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "credit_note_number", nullable = false, unique = true)
    private String creditNoteNumber;

    @Column(name = "original_order_code", nullable = false)
    private String originalOrderCode;

    private Long originalOrderId;
    private Long returnRequestId;

    private String customerId;
    private String customerName;
    private String customerGstin;

    private String taxPeriod;
    private String financialYear;
    private String originalTaxPeriod;
    private LocalDate creditNoteDate;

    @Builder.Default
    private Long taxableValuePaise = 0L;
    @Builder.Default
    private Long cgstPaise = 0L;
    @Builder.Default
    private Long sgstPaise = 0L;
    @Builder.Default
    private Long igstPaise = 0L;
    @Builder.Default
    private Long totalCreditPaise = 0L;

    private String supplyType;
    private String placeOfSupply;

    /** PRODUCT_OK or DAMAGED — recorded for context; does not decide GST. */
    private String condition;

    /** CUSTOMER_RETURN, PRICE_REVISION, ORDER_CANCELLED, OTHER */
    private String reasonCode;

    /**
     * DRAFT → PENDING_REVIEW → APPROVED → ISSUED → REPORTED → RECONCILED,
     * or CANCELLED / REJECTED (spec section 16). New credit notes start at
     * PENDING_REVIEW; only a human transition marks one ISSUED.
     */
    @Builder.Default
    private String status = "PENDING_REVIEW";

    /** NOT_REPORTED, REPORTED_GSTR1, RECONCILED */
    @Builder.Default
    private String reportingStatus = "NOT_REPORTED";

    private String approvedBy;
    private Instant approvedAt;
    private Instant issuedAt;
    private Instant reportedAt;
    private LocalDate originalInvoiceDate;
    private String createdBy;

    @Column(columnDefinition = "TEXT")
    private String notes;

    /** FK to the gst_movement_ledger row that represents this credit note */
    private Long movementId;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    /** Tenant scope (spec section 58). */
    private Long organizationId;
    private Long gstRegistrationId;
}
