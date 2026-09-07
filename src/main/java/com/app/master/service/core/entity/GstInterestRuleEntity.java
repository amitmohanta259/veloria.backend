package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A statutory interest rate, valid for a stated period.
 *
 * Rates are configured, never compiled in. Interest under the GST Act has
 * changed by notification more than once, and a rate hard-coded today would
 * silently misstate a liability computed for an earlier period. An empty table
 * means interest cannot be computed — which is reported, not guessed around.
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "gst_interest_rule")
public class GstInterestRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;

    /** LATE_PAYMENT, EXCESS_ITC_CLAIMED, … */
    @Column(name = "rule_type", nullable = false)
    private String ruleType;

    /** Basis points a year: 1800 = 18%. */
    @Column(name = "rate_bp", nullable = false)
    private Integer rateBp;

    /** SIMPLE_DAILY, … — how the rate is applied. */
    @Column(name = "calculation_method")
    private String calculationMethod;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** The notification or circular this rate comes from. Required for an audit. */
    @Column(name = "source_reference")
    private String sourceReference;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Null while the rate is still current. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
