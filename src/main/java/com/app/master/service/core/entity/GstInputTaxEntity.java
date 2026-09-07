package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "gst_input_tax")
public class GstInputTaxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long purchaseOrderId;
    private UUID purchaseOrderUuid;

    private String vendorGstin;
    private String vendorName;
    private String vendorStateCode;
    private String invoiceNumber;
    private LocalDate invoiceDate;

    private String financialYear;
    private String taxPeriod;

    private Long taxableValue;
    private BigDecimal cgstRate;
    private Long cgstAmount;
    private BigDecimal sgstRate;
    private Long sgstAmount;
    private BigDecimal igstRate;
    private Long igstAmount;
    private Long totalInputTax;

    private String itcEligibility;
    private Long eligibleCgst;
    private Long eligibleSgst;
    private Long eligibleIgst;
    private Long ineligibleCgst;
    private Long ineligibleSgst;
    private Long ineligibleIgst;

    /** PENDING_REVIEW, ELIGIBLE, PARTIALLY_ELIGIBLE, INELIGIBLE, CLAIMED, REVERSED, RECLAIMED */
    private String itcStatus;
    private Long reversalAmount;
    private String reversalReason;
    private Long reclaimedAmount;

    /** Running balances, maintained only from gst_itc_transaction rows. */
    @Builder.Default private Long claimedCgst = 0L;
    @Builder.Default private Long claimedSgst = 0L;
    @Builder.Default private Long claimedIgst = 0L;
    @Builder.Default private Long reversedCgst = 0L;
    @Builder.Default private Long reversedSgst = 0L;
    @Builder.Default private Long reversedIgst = 0L;
    @Builder.Default private Long reclaimedCgst = 0L;
    @Builder.Default private Long reclaimedSgst = 0L;
    @Builder.Default private Long reclaimedIgst = 0L;
    @Builder.Default private Long cessAmount = 0L;

    /** VALID_DOCUMENT, MISSING_DOCUMENT, NOT_REFLECTED_IN_2B, BLOCKED_CREDIT, ... */
    private String eligibilityReason;

    @Column(columnDefinition = "TEXT")
    private String eligibilityNotes;

    private Instant itcClaimedAt;

    /** ITC currently available: claimed minus reversed plus reclaimed. */
    @Transient
    public long netClaimedCgst() { return nz(claimedCgst) - nz(reversedCgst) + nz(reclaimedCgst); }
    @Transient
    public long netClaimedSgst() { return nz(claimedSgst) - nz(reversedSgst) + nz(reclaimedSgst); }
    @Transient
    public long netClaimedIgst() { return nz(claimedIgst) - nz(reversedIgst) + nz(reclaimedIgst); }

    private static long nz(Long v) { return v != null ? v : 0L; }

    private Boolean goodsReceived;
    private String gstr2bMatchStatus;
    private String itcClaimedPeriod;
    private String remarks;
    // Builder.Default: without it Lombok discards the initializer and inserts
    // null, which the NOT NULL column rejects.
    @Builder.Default
    private String gstType = "INPUT_TAX";

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    /** Tenant scope (spec section 58). */
    private Long organizationId;
    private Long gstRegistrationId;

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}
