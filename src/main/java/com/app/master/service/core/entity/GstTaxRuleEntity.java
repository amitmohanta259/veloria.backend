package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "gst_tax_rules")
public class GstTaxRuleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    @Column(name = "hsn_code", nullable = false)
    private String hsnCode;

    @Column(name = "hsn_match_type", nullable = false)
    private String hsnMatchType; // EXACT, PREFIX

    private String description;

    @Column(name = "min_price_paise")
    private Long minPricePaise;

    @Column(name = "max_price_paise")
    private Long maxPricePaise;

    @Column(name = "cgst_rate_bp", nullable = false)
    private Integer cgstRateBp; // basis points: 250 = 2.5%

    @Column(name = "sgst_rate_bp", nullable = false)
    private Integer sgstRateBp;

    @Column(name = "igst_rate_bp", nullable = false)
    private Integer igstRateBp;

    /** Cess rate in basis points. Zero (or null) means no cess applies. */
    @Column(name = "cess_rate_bp")
    @Builder.Default
    private Integer cessRateBp = 0;

    /** e.g. COMPENSATION_CESS — descriptive, for reporting. */
    @Column(name = "cess_type")
    private String cessType;

    private Integer priority;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    private Boolean active;

    private Instant created;
    private Instant modified;
}
