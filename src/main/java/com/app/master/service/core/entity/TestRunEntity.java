package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One execution of a test suite, triggered from the dashboard by a person. */
@Entity @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@Table(name = "engineering_test_run")
public class TestRunEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default private UUID uuid = UUID.randomUUID();

    @Column(name = "run_number", nullable = false, updatable = false)
    private String runNumber;

    @Column(name = "triggered_by", nullable = false, updatable = false)
    private String triggeredBy;

    @Column(name = "test_type", nullable = false, updatable = false)
    private String testType;

    @Column(updatable = false)
    private String modules;

    @Column(nullable = false, updatable = false)
    private String environment;

    @Column(nullable = false)
    private String status;

    /** The allowlisted command label that ran — the audit trail for the run. */
    @Column(name = "command_label")
    private String commandLabel;

    @Column(name = "git_commit")
    private String gitCommit;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "completed_at") private Instant completedAt;

    @Column(name = "total_count",   nullable = false) @Builder.Default private Integer totalCount = 0;
    @Column(name = "passed_count",  nullable = false) @Builder.Default private Integer passedCount = 0;
    @Column(name = "failed_count",  nullable = false) @Builder.Default private Integer failedCount = 0;
    @Column(name = "skipped_count", nullable = false) @Builder.Default private Integer skippedCount = 0;
    @Column(name = "blocked_count", nullable = false) @Builder.Default private Integer blockedCount = 0;

    @Column(name = "failure_reason", columnDefinition = "text") private String failureReason;
    @Column(name = "log_path") private String logPath;

    @Builder.Default private Instant created = Instant.now();
    private Instant modified;
}
