package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A vendor's tax invoice, recorded at line level.
 *
 * Previously vendor GST was captured only at header level from a PDF extract,
 * with no line detail and therefore no HSN summary on the purchase side.
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "purchase_invoice")
public class PurchaseInvoiceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private Long gstRegistrationId;
    private Long purchaseOrderId;

    private UUID vendorUuid;
    private String vendorGstin;
    private String vendorName;
    private String vendorStateCode;

    @Column(nullable = false)
    private String vendorInvoiceNumber;
    private LocalDate vendorInvoiceDate;

    @Builder.Default private String invoiceType = "B2B";
    private String placeOfSupply;
    private String supplyType;
    @Builder.Default private Boolean reverseCharge = Boolean.FALSE;
    @Builder.Default private String currency = "INR";

    @Builder.Default private Long grossValue = 0L;
    @Builder.Default private Long discountValue = 0L;
    @Builder.Default private Long taxableValue = 0L;
    @Builder.Default private Long cgstAmount = 0L;
    @Builder.Default private Long sgstAmount = 0L;
    @Builder.Default private Long igstAmount = 0L;
    @Builder.Default private Long cessAmount = 0L;
    @Builder.Default private Long totalTax = 0L;
    @Builder.Default private Long totalInvoiceValue = 0L;

    /** RECORDED, CANCELLED */
    @Builder.Default private String status = "RECORDED";

    private String taxPeriod;
    private String financialYear;

    /** The gst_input_tax row this invoice produced. */
    private Long inputTaxId;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }
}
