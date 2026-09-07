package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/** One supplier invoice line as reported in GSTR-2B. */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "gstr2b_record")
public class Gstr2bRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long importId;

    private String supplierGstin;
    private String supplierName;
    private String invoiceNumber;
    private LocalDate invoiceDate;

    @Builder.Default private String invoiceType = "B2B";

    @Builder.Default private Long taxableValuePaise = 0L;
    @Builder.Default private Long cgstPaise = 0L;
    @Builder.Default private Long sgstPaise = 0L;
    @Builder.Default private Long igstPaise = 0L;
    @Builder.Default private Long cessPaise = 0L;
    @Builder.Default private Long totalTaxPaise = 0L;

    private String taxPeriod;

    /** MATCHED, PARTIAL_MATCH, MISMATCH, MISSING_IN_2B, DUPLICATE, PENDING_REVIEW */
    @Builder.Default private String matchStatus = "PENDING_REVIEW";

    private Long matchedInputTaxId;
    private Long organizationId;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
