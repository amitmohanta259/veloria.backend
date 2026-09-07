package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/** A GSTR-2B file ingestion run (spec section 9). */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "gstr2b_import")
public class Gstr2bImportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String taxPeriod;

    /** CSV, JSON, XLSX — the parser is selected from this. */
    private String sourceFormat;
    private String fileName;

    @Builder.Default private Integer recordCount = 0;
    @Builder.Default private Long totalTaxablePaise = 0L;
    @Builder.Default private Long totalTaxPaise = 0L;

    /** IMPORTED, RECONCILED, SUPERSEDED */
    @Builder.Default private String status = "IMPORTED";

    private Long organizationId;
    private Long gstRegistrationId;

    @Builder.Default
    private Instant importedAt = Instant.now();
    private String importedBy;
}
