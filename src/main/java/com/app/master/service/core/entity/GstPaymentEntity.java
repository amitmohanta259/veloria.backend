package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/** An actual GST payment against a period (spec section 27). */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "gst_payment")
public class GstPaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String paymentReference;

    private LocalDate paymentDate;

    @Column(nullable = false)
    private String taxPeriod;

    private String financialYear;

    @Builder.Default private Long cgstPaise = 0L;
    @Builder.Default private Long sgstPaise = 0L;
    @Builder.Default private Long igstPaise = 0L;
    @Builder.Default private Long cessPaise = 0L;
    @Builder.Default private Long interestPaise = 0L;
    @Builder.Default private Long lateFeePaise = 0L;
    @Builder.Default private Long totalPaise = 0L;

    private String paymentMode;
    private String challanNumber;

    @Column(columnDefinition = "TEXT")
    private String notes;

    private Long movementId;
    private Long organizationId;
    private Long gstRegistrationId;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
    private String createdBy;
}
