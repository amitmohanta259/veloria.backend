package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Supporting evidence for a finding — the figures a rule compared, by reference.
 *
 * <p>References, not copies: an evidence row says "journal 42, account 1100", not
 * the customer's name or a payment identifier. No PII (§25).
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "engineering_anomaly_evidence")
public class EngineeringAnomalyEvidenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    @Column(name = "anomaly_id", nullable = false)
    private Long anomalyId;

    @Column(name = "evidence_type", nullable = false)
    private String evidenceType;

    @Column(name = "evidence_reference")
    private String evidenceReference;

    @Builder.Default
    private Instant created = Instant.now();
}
