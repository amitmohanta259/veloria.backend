package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Append-only history of every GST state change (spec section 30).
 * Nothing in this table is ever updated or deleted.
 *
 * The table predates this work (GstAccounting005), so the original
 * changed_by / changed_at column names are preserved.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "gst_audit_log")
public class GstAuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** GST_CREDIT_NOTE, GST_MOVEMENT, GST_INPUT_TAX, RETURN_REQUEST */
    @Column(nullable = false)
    private String entityType;

    private Long entityId;

    /** Human-readable handle: credit note number, order code, invoice number. */
    private String entityReference;

    /** CREDIT_NOTE_CREATED, CREDIT_NOTE_APPROVED, ITC_REVERSED, ... */
    @Column(nullable = false)
    private String action;

    private String fieldName;

    @Column(columnDefinition = "TEXT")
    private String oldValue;

    @Column(columnDefinition = "TEXT")
    private String newValue;

    @Column(columnDefinition = "TEXT")
    private String reason;

    @Column(name = "changed_by")
    private String performedBy;

    private String taxPeriod;

    @Builder.Default
    @Column(name = "changed_at")
    private Instant createdAt = Instant.now();

    private Long organizationId;
}
