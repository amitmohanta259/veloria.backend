package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An invoice numbering series, scoped to organization + registration +
 * document type + financial year (spec phase 7).
 *
 * The counter is advanced with an atomic UPDATE ... RETURNING, which takes a row
 * lock, so two concurrent requests cannot be handed the same number. The
 * generated number is also protected by a unique constraint on the invoice
 * table, so a duplicate fails at the database rather than being written.
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "gst_invoice_series")
public class GstInvoiceSeriesEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;
    private Long gstRegistrationId;

    /** SALES_INVOICE, CREDIT_NOTE, DEBIT_NOTE */
    @Column(nullable = false)
    private String documentType;

    @Column(nullable = false)
    private String financialYear;

    private String prefix;
    @Builder.Default private String separator = "/";
    @Builder.Default private Integer padding = 6;
    @Builder.Default private Long nextNumber = 1L;
    @Builder.Default private Boolean active = Boolean.TRUE;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
