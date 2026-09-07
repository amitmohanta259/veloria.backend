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
@Table(name = "gst_movement_ledger")
public class GstMovementLedgerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "movement_number", nullable = false, unique = true)
    private String movementNumber;

    /** SALE_OUTPUT, PURCHASE_INPUT, CUSTOMER_RETURN_OUTPUT_ADJUSTMENT, etc. */
    @Column(name = "movement_type", nullable = false)
    private String movementType;

    /** IN (vendor charges us), OUT (we charge customer), ADJUSTMENT */
    @Column(name = "direction", nullable = false)
    private String direction;

    /** CUSTOMER_ORDER, PURCHASE_ORDER, RETURN_REQUEST */
    private String sourceType;
    private Long sourceId;
    private String sourceDocumentNumber;

    /** Only for adjustments — points back to the original movement */
    private Long originalDocumentId;
    private String originalDocumentNumber;

    private LocalDate transactionDate;
    private String taxPeriod;
    private String financialYear;

    private String businessGstin;
    private String counterpartyGstin;
    private String counterpartyName;
    /** CUSTOMER or VENDOR */
    private String counterpartyType;

    private String productUuid;
    private String productName;
    private String skuId;
    private String hsnCode;

    @Builder.Default
    private Integer quantity = 1;
    @Builder.Default
    private Long unitPricePaise = 0L;
    @Builder.Default
    private Long taxableValuePaise = 0L;

    @Builder.Default
    private Integer cgstRateBp = 0;
    @Builder.Default
    private Integer sgstRateBp = 0;
    @Builder.Default
    private Integer igstRateBp = 0;

    @Builder.Default
    private Long cgstAmountPaise = 0L;
    @Builder.Default
    private Long sgstAmountPaise = 0L;
    @Builder.Default
    private Long igstAmountPaise = 0L;
    @Builder.Default
    private Long totalTaxPaise = 0L;

    /** PENDING_REVIEW / APPROVED / REJECTED / REVERSED — meaningful for purchases */
    private String itcStatus;

    private String placeOfSupply;
    private String sellerStateCode;
    private String buyerStateCode;
    private String supplyType;

    @Builder.Default
    private Boolean reverseCharge = Boolean.FALSE;

    private Long referenceTransactionId;
    private Long referenceReturnId;
    private Long referenceCreditNoteId;

    /** Item-level movements link back to the order line they came from. */
    private Long orderItemId;

    /** For return adjustments: how many units this movement reverses. */
    private Integer returnedQuantity;

    @Builder.Default
    private String status = "POSTED";

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    private String createdBy;

    /** Tenant scope (spec section 58). */
    private Long organizationId;
    private Long gstRegistrationId;
}
