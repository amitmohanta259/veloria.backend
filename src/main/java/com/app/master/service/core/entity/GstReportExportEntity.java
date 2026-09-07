package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** An audit record of a report export (spec phase 19). */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "gst_report_export")
public class GstReportExportEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String reportType;

    @Column(nullable = false)
    private String format;

    private String taxPeriod;
    @Builder.Default private Integer rowCount = 0;
    @Builder.Default private Long byteSize = 0L;

    private Long organizationId;
    private Long gstRegistrationId;
    private String exportedBy;

    @Builder.Default
    private Instant exportedAt = Instant.now();
}
