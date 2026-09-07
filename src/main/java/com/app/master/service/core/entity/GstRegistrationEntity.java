package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A GST registration (GSTIN) held by an organization (spec section 4).
 *
 * The business runs one today, but purchases and sales already identify their
 * applicable registration so a second state registration needs no migration of
 * existing accounting records.
 *
 * The state code is derived from the GSTIN rather than stored independently —
 * characters 1-2 of a GSTIN are the state code, and the audit found the two
 * disagreeing.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "gst_registration")
public class GstRegistrationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;

    private String legalName;
    private String tradeName;

    @Column(nullable = false, unique = true, length = 15)
    private String gstin;

    @Column(length = 2)
    private String stateCode;

    private String stateName;

    /** REGULAR, COMPOSITION, CASUAL, SEZ, ISD */
    @Builder.Default
    private String registrationType = "REGULAR";

    private LocalDate registrationDate;

    /** MONTHLY or QUARTERLY */
    @Builder.Default
    private String returnFrequency = "MONTHLY";

    @Builder.Default
    private Boolean isActive = Boolean.TRUE;

    @Builder.Default
    private Boolean isPrimary = Boolean.FALSE;

    @Builder.Default
    private String invoicePrefix = "INV";
    @Builder.Default
    private String creditNotePrefix = "CN";
    @Builder.Default
    private String debitNotePrefix = "DN";

    @Column(columnDefinition = "TEXT")
    private String address;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    /** The state this GSTIN declares. Authoritative over any stored value. */
    @Transient
    public String declaredStateCode() {
        return gstin != null && gstin.length() == 15 ? gstin.substring(0, 2) : stateCode;
    }
}
