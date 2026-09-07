package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A GST tax period and its computed totals (spec sections 20 and 21).
 *
 * The table existed but had no entity or service; totals were never computed
 * and nothing enforced locking. Both are now implemented.
 */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "gst_tax_period")
public class GstTaxPeriodEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String financialYear;

    @Column(nullable = false)
    private String taxPeriod;

    private LocalDate periodStartDate;
    private LocalDate periodEndDate;

    /** OPEN, UNDER_REVIEW, READY_FOR_FILING, FILED, LOCKED */
    @Builder.Default private String status = "OPEN";

    @Builder.Default private Long totalOutwardTaxableValue = 0L;
    @Builder.Default private Long outputCgst = 0L;
    @Builder.Default private Long outputSgst = 0L;
    @Builder.Default private Long outputIgst = 0L;
    @Builder.Default private Long totalOutputTax = 0L;

    @Builder.Default private Long totalInwardTaxableValue = 0L;
    @Builder.Default private Long inputCgst = 0L;
    @Builder.Default private Long inputSgst = 0L;
    @Builder.Default private Long inputIgst = 0L;
    @Builder.Default private Long totalInputTax = 0L;

    @Builder.Default private Long eligibleCgstItc = 0L;
    @Builder.Default private Long eligibleSgstItc = 0L;
    @Builder.Default private Long eligibleIgstItc = 0L;

    @Builder.Default private Long reversedCgst = 0L;
    @Builder.Default private Long reversedSgst = 0L;
    @Builder.Default private Long reversedIgst = 0L;

    @Builder.Default private Long netCgstLiability = 0L;
    @Builder.Default private Long netSgstLiability = 0L;
    @Builder.Default private Long netIgstLiability = 0L;

    @Builder.Default private Long cashCgstPayable = 0L;
    @Builder.Default private Long cashSgstPayable = 0L;

    // Explicit column names: Hibernate's naming strategy renders gstr1Status as
    // "gstr1status" with no underscore before the digit, which does not match.
    /** NOT_STARTED, PREPARED, READY_TO_FILE, FILED */
    @Builder.Default
    @Column(name = "gstr1_status")
    private String gstr1Status = "NOT_STARTED";

    @Builder.Default
    @Column(name = "gstr3b_status")
    private String gstr3bStatus = "NOT_STARTED";

    /** NOT_IMPORTED, IMPORTED, RECONCILED */
    @Builder.Default
    @Column(name = "gstr2b_status")
    private String gstr2bStatus = "NOT_IMPORTED";

    private Instant lockedAt;
    private String lockedBy;
    private Instant unlockedAt;
    private String unlockedBy;

    @Column(columnDefinition = "TEXT")
    private String unlockReason;

    private Long organizationId;
    private Long gstRegistrationId;

    @Transient
    public boolean isLocked() {
        return "LOCKED".equals(status) || "FILED".equals(status);
    }
}
