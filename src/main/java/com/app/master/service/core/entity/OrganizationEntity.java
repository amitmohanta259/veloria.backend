package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * The tenant boundary for GST data (spec section 58).
 *
 * One organization owns one or more GST registrations. Every GST record carries
 * an organization id so a caller from one organization can never read another's
 * invoices, purchases, ITC, ledger or returns.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "organization")
public class OrganizationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String legalName;

    private String tradeName;
    private String pan;

    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }
}
