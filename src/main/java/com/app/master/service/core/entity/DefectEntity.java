package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One defect, with the evidence needed to reproduce it.
 *
 * <p>A defect outlives the run that found it and keeps its number across
 * reopenings, so a closed issue that comes back is the same issue rather than a
 * new one with the history lost.
 */
@Entity @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@Table(name = "engineering_defect")
public class DefectEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default private UUID uuid = UUID.randomUUID();

    @Column(name = "defect_number", nullable = false, updatable = false)
    private String defectNumber;

    /** Deterministic: the same failure reopens this row instead of adding one. */
    @Column(nullable = false, updatable = false)
    private String fingerprint;

    @Column(nullable = false) private String title;

    private String module;
    private String feature;
    private String route;

    @Column(name = "ui_element_id") private String uiElementId;
    @Column(name = "api_endpoint")  private String apiEndpoint;
    @Column(name = "entity_name")   private String entityName;

    @Column(nullable = false) private String category;
    private String subcategory;

    @Column(nullable = false) private String severity;
    @Column(nullable = false) private String priority;
    @Column(nullable = false) private String status;

    @Column(columnDefinition = "text") private String description;
    @Column(name = "expected_behavior", columnDefinition = "text") private String expectedBehavior;
    @Column(name = "actual_behavior",   columnDefinition = "text") private String actualBehavior;
    @Column(columnDefinition = "text") private String preconditions;
    @Column(name = "reproduction_steps", columnDefinition = "text") private String reproductionSteps;

    // Evidence. Redacted before it reaches these columns.
    @Column(name = "evidence_curl",      columnDefinition = "text") private String evidenceCurl;
    @Column(name = "evidence_request",   columnDefinition = "text") private String evidenceRequest;
    @Column(name = "evidence_response",  columnDefinition = "text") private String evidenceResponse;
    @Column(name = "evidence_db_before", columnDefinition = "text") private String evidenceDbBefore;
    @Column(name = "evidence_db_after",  columnDefinition = "text") private String evidenceDbAfter;
    @Column(name = "screenshot_path") private String screenshotPath;

    @Column(name = "root_cause",          columnDefinition = "text") private String rootCause;
    @Column(name = "proposed_resolution", columnDefinition = "text") private String proposedResolution;
    @Column(name = "actual_fix",          columnDefinition = "text") private String actualFix;
    @Column(name = "changed_files",       columnDefinition = "text") private String changedFiles;
    @Column(name = "regression_test") private String regressionTest;

    @Column(name = "first_seen_run") private String firstSeenRun;
    @Column(name = "last_seen_run")  private String lastSeenRun;
    @Column(name = "retest_run")     private String retestRun;

    @Column(name = "reopen_count", nullable = false) @Builder.Default private Integer reopenCount = 0;

    @Column(name = "opened_at", nullable = false) private Instant openedAt;
    @Column(name = "fixed_at")    private Instant fixedAt;
    @Column(name = "verified_at") private Instant verifiedAt;
    @Column(name = "closed_at")   private Instant closedAt;

    @Column(name = "opened_by") private String openedBy;
    @Column(name = "closed_by") private String closedBy;

    @Builder.Default private Instant created = Instant.now();
    private Instant modified;
}
