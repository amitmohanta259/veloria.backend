package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The GST tax invoice — the accounting document, separate from the order.
 *
 * The order records the commercial transaction; this records the supply for GST
 * purposes. Every monetary field here is a SNAPSHOT taken when the invoice was
 * built: changing a product price, a tax rule, an address or a GSTIN afterwards
 * must never alter an invoice that has already been issued.
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "sales_invoice")
public class SalesInvoiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null while DRAFT; assigned from the series at issue. Unique in the database. */
    private String invoiceNumber;
    private LocalDate invoiceDate;

    private Long organizationId;
    private Long gstRegistrationId;

    private Long orderId;
    private String orderCode;

    private String customerId;
    /** B2B or B2C */
    @Builder.Default private String customerType = "B2C";
    private String customerGstin;
    private String customerName;
    private String customerLegalName;

    @Column(columnDefinition = "TEXT")
    private String billingAddressSnapshot;

    @Column(columnDefinition = "TEXT")
    private String shippingAddressSnapshot;

    private String placeOfSupply;
    private String sellerGstin;
    private String sellerStateCode;
    private String supplyType;

    @Builder.Default private Boolean reverseCharge = Boolean.FALSE;
    @Builder.Default private String currency = "INR";

    @Builder.Default private Long grossValue = 0L;
    @Builder.Default private Long discountValue = 0L;
    @Builder.Default private Long shippingValue = 0L;
    @Builder.Default private Long shippingTaxableValue = 0L;
    @Builder.Default private Long taxableValue = 0L;

    @Builder.Default private Long cgstAmount = 0L;
    @Builder.Default private Long sgstAmount = 0L;
    @Builder.Default private Long igstAmount = 0L;
    @Builder.Default private Long cessAmount = 0L;
    @Builder.Default private Long totalTax = 0L;
    @Builder.Default private Long roundOff = 0L;
    @Builder.Default private Long totalInvoiceValue = 0L;

    /** DRAFT, ISSUED, CANCELLED, AMENDED */
    @Builder.Default
    @Column(nullable = false)
    private String status = "DRAFT";

    private String taxPeriod;
    private String financialYear;

    private Instant issuedAt;
    private String issuedBy;
    private Instant cancelledAt;
    private String cancelledBy;

    @Column(columnDefinition = "TEXT")
    private String cancellationReason;

    private Instant amendedAt;
    private String amendedBy;

    @Column(columnDefinition = "TEXT")
    private String amendmentReason;

    /** Set on an amending invoice, pointing at the invoice it supersedes. */
    private Long originalInvoiceId;

    /** True once this invoice has posted its ledger movements. */
    @Builder.Default private Boolean movementPosted = Boolean.FALSE;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    @Transient
    public boolean isIssued() { return "ISSUED".equals(status) || "AMENDED".equals(status); }

    @Transient
    public boolean isCancelled() { return "CANCELLED".equals(status); }
}
