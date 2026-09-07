package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One ITC claim, reversal or reclaim (spec sections 11 and 12).
 *
 * Balances on gst_input_tax are the running total of these rows; they are never
 * edited directly. transactionReference is unique so the same event cannot post
 * twice (spec section 26).
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "gst_itc_transaction")
public class GstItcTransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** e.g. GST-ITC-CLAIM-14, GST-ITC-REV-14-2 — unique, prevents double posting. */
    @Column(nullable = false, unique = true)
    private String transactionReference;

    @Column(nullable = false)
    private Long inputTaxId;

    /** CLAIM, REVERSAL, RECLAIM */
    @Column(nullable = false)
    private String transactionType;

    /** VALID_DOCUMENT, NOT_REFLECTED_IN_2B, BLOCKED_CREDIT, RETURNED_GOODS, ... */
    private String reasonCode;

    @Column(columnDefinition = "TEXT")
    private String reasonNotes;

    @Builder.Default private Long cgstPaise = 0L;
    @Builder.Default private Long sgstPaise = 0L;
    @Builder.Default private Long igstPaise = 0L;
    @Builder.Default private Long cessPaise = 0L;
    @Builder.Default private Long totalPaise = 0L;

    private String taxPeriod;
    private String financialYear;

    /** The gst_movement_ledger row this transaction posted. */
    private Long movementId;

    /** For a reclaim, the reversal it restores. */
    private Long reversalOfId;

    private Long organizationId;
    private Long gstRegistrationId;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    private String createdBy;
}
