package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A statutory late-fee rate per return type, valid for a stated period.
 * Configured for the same reason interest is — see {@link GstInterestRuleEntity}.
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "gst_late_fee_rule")
public class GstLateFeeRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;

    /** GSTR1, GSTR3B, … */
    @Column(name = "return_type", nullable = false)
    private String returnType;

    @Column(name = "per_day_paise", nullable = false)
    private Long perDayPaise;

    /** The statutory cap, if the rule has one. */
    @Column(name = "max_paise")
    private Long maxPaise;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "source_reference")
    private String sourceReference;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
