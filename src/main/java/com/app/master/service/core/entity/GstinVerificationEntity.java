package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The outcome of checking a GSTIN (spec phase 25).
 *
 * FORMAT_VALID and VERIFIED are deliberately distinct: passing the checksum
 * pattern is not confirmation from the GST portal, and a database constraint
 * prevents VERIFIED without a named provider and timestamp.
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "gstin_verification")
public class GstinVerificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String gstin;

    /** UNVERIFIED, FORMAT_VALID, VERIFIED, INVALID, VERIFICATION_FAILED */
    @Column(nullable = false)
    private String status;

    private String legalName;
    private String tradeName;
    private String stateCode;
    private String registrationStatus;

    private String provider;
    private String providerReference;

    @Column(columnDefinition = "TEXT")
    private String lastError;

    private Instant verifiedAt;
    private Long organizationId;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
