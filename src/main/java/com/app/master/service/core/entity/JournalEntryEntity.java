package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * One balanced accounting entry.
 *
 * Carries the business record that produced it, so every figure in every report
 * can be traced back to the sale, expense or payroll run behind it.
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "journal_entry")
public class JournalEntryEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journal_number", nullable = false, unique = true)
    private String journalNumber;

    /**
     * The <b>posting date</b>: the date whose period this entry is recorded in.
     * Equal to {@link #transactionDate} unless that period was closed, in which
     * case the approved policy moves the entry to the next open period.
     */
    @Column(name = "journal_date", nullable = false)
    private LocalDate journalDate;

    /**
     * When the business event actually happened — the order was placed, the cash
     * was taken, the refund was issued.
     *
     * <p>Kept separate from {@link #journalDate} so that a deferred posting can
     * still be audited against the day it belongs to. It is never overwritten.
     */
    @Column(name = "transaction_date")
    private LocalDate transactionDate;

    @Column(nullable = false)
    private String period;

    @Column(name = "financial_year")
    private String financialYear;

    private String reference;

    /** SALE, EXPENSE, PAYROLL, PURCHASE, GST_PAYMENT, ADJUSTMENT … */
    @Column(name = "source_type", nullable = false)
    private String sourceType;

    @Column(name = "source_id")
    private Long sourceId;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** POSTED or REVERSED. Entries are never deleted. */
    @Builder.Default
    private String status = "POSTED";

    @Column(name = "reverses_journal_id")
    private Long reversesJournalId;

    @Builder.Default
    @Column(name = "total_debit_paise")
    private Long totalDebitPaise = 0L;

    @Builder.Default
    @Column(name = "total_credit_paise")
    private Long totalCreditPaise = 0L;

    private Long organizationId;

    @Column(name = "created_by")
    private String createdBy;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    /** Loaded on demand through JournalEntryLineRepository, not mapped. */
    @Transient
    @Builder.Default
    private List<JournalEntryLineEntity> lines = new ArrayList<>();
}
