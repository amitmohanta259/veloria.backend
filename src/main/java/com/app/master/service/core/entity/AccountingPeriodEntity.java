package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/** A reporting month and whether it still accepts postings. */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "accounting_period")
public class AccountingPeriodEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String period;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "financial_year")
    private String financialYear;

    /** OPEN, CLOSED, LOCKED */
    @Builder.Default
    private String status = "OPEN";

    @Column(name = "closed_by")
    private String closedBy;

    @Column(name = "closed_at")
    private Instant closedAt;

    private Long organizationId;
}
