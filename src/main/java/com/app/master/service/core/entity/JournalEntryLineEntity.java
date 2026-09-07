package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

/**
 * One side of a journal entry.
 *
 * A database check constraint enforces that exactly one of debit or credit is
 * non-zero — a line that is both, or neither, is not an accounting entry.
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "journal_entry_line")
public class JournalEntryLineEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journal_entry_id", nullable = false)
    private Long journalEntryId;

    @Column(name = "line_number")
    private Integer lineNumber;

    @Column(name = "account_code", nullable = false)
    private String accountCode;

    @Builder.Default
    @Column(name = "debit_paise")
    private Long debitPaise = 0L;

    @Builder.Default
    @Column(name = "credit_paise")
    private Long creditPaise = 0L;

    @Column(columnDefinition = "TEXT")
    private String description;
}
