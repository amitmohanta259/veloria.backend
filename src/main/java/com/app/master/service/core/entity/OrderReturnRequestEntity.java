package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "order_return_request")
public class OrderReturnRequestEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(name = "uuid")
    private String uuid = UUID.randomUUID().toString();

    private String orderCode;
    private String customerId;
    private String itemUuid;
    private String returnType;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(columnDefinition = "TEXT")
    private String frontImage;

    @Column(columnDefinition = "TEXT")
    private String backImage;

    @Column(columnDefinition = "TEXT")
    private String tagImage;

    private String verificationStatus;

    @Column(columnDefinition = "TEXT")
    private String verificationNotes;

    @Column(name = "return_number")
    private String returnNumber;

    /** REQUESTED, VERIFIED, REJECTED */
    @Builder.Default
    private String status = "REQUESTED";

    /**
     * Whether this return warrants a GST credit note. Deliberately separate
     * from returnCondition: physical damage governs inventory, not whether the
     * original supply is being commercially reversed (spec section 11/20).
     */
    @Builder.Default
    private Boolean gstAdjustmentRequired = Boolean.TRUE;

    /** NOT_STARTED, PENDING_REVIEW, COMPLETED, NOT_APPLICABLE, FAILED */
    @Builder.Default
    private String gstAdjustmentStatus = "NOT_STARTED";

    @Column(columnDefinition = "TEXT")
    private String gstAdjustmentReason;

    private Long creditNoteId;
    private Instant verifiedAt;
    private String verifiedBy;

    @Builder.Default
    private Boolean archive = Boolean.FALSE;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
