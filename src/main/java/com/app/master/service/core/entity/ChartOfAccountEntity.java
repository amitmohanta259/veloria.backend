package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

/** One account in the chart. Every journal line names one of these by code. */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "chart_of_accounts")
public class ChartOfAccountEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    /** ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE */
    @Column(name = "account_type", nullable = false)
    private String accountType;

    /** DEBIT or CREDIT — the side that increases this account. */
    @Column(name = "normal_balance", nullable = false)
    private String normalBalance;

    private String subgroup;

    /** Current vs non-current, for the balance sheet split. */
    @Column(name = "is_current")
    private Boolean isCurrent;

    private Long organizationId;

    @Builder.Default
    private Boolean active = Boolean.TRUE;
}
