package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One discovered thing the test programme is accountable for.
 *
 * <p>A UI element, an API endpoint, a route or an entity — enumerated from the
 * source so that a coverage claim has a denominator somebody can check. A
 * percentage over an unenumerated population is not a measurement.
 */
@Entity @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
@Table(name = "engineering_test_inventory")
public class TestInventoryEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false, updatable = false)
    private String itemId;

    @Column(name = "item_kind", nullable = false)
    private String itemKind;

    @Column(nullable = false) private String module;

    @Column(name = "source_file") private String sourceFile;
    private String route;

    @Column(name = "element_type") private String elementType;
    private String label;

    @Column(name = "test_status", nullable = false) @Builder.Default
    private String testStatus = "UNTESTED";

    @Column(name = "covering_test")  private String coveringTest;
    @Column(name = "blocked_reason") private String blockedReason;
    @Column(name = "last_run")       private String lastRun;

    @Column(columnDefinition = "text") private String notes;

    @Column(name = "discovered_at", nullable = false) @Builder.Default
    private Instant discoveredAt = Instant.now();

    private Instant modified;
}
