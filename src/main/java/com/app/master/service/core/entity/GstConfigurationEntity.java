package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Configurable GST behaviour (spec phases 12, 13, 26).
 *
 * Anything that can change by notification, or that is a business decision
 * rather than an arithmetic fact, lives here instead of in code.
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "gst_configuration")
public class GstConfigurationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;

    @Column(nullable = false)
    private String configKey;
    private String configValue;
    @Builder.Default private String valueType = "STRING";

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Where the value came from — a notification number, a policy document. */
    private String sourceReference;

    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    @Builder.Default private Boolean active = Boolean.TRUE;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    private String updatedBy;
}
