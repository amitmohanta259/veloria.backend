package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** One test outcome within a run, parsed from the framework's own report. */
@Entity @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@Table(name = "engineering_test_result")
public class TestResultEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false) private Long runId;

    @Column(nullable = false) private String suite;
    private String module;

    @Column(name = "class_name") private String className;

    @Column(name = "test_name", nullable = false) private String testName;

    @Column(nullable = false) private String status;

    @Column(name = "duration_ms") private Long durationMs;

    @Column(name = "failure_message", columnDefinition = "text") private String failureMessage;

    @Builder.Default private Instant created = Instant.now();
}
