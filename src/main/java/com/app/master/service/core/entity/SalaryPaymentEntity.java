package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "salary_payment")
public class SalaryPaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    private String voucherNumber;
    private String paymentMonth;

    private Long totalGrossPaise;
    private Long totalNetPaise;
    private Long totalEmployerContributionPaise;
    private Long totalEmployeeContributionPaise;
    private Integer employeeCount;

    private String status;
    private String notes;

    @OneToMany(mappedBy = "salaryPayment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<SalaryPaymentDetailEntity> details = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}
