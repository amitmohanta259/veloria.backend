package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "expense")
public class ExpenseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    private String voucherNumber;
    private String expenseType;
    private LocalDate expenseDate;

    private String supplierName;
    private String supplierGstin;
    private String supplierState;

    private String invoiceNumber;
    private LocalDate invoiceDate;

    private Long subtotalPaise;
    private Long taxPaise;
    private Long totalPaise;

    private String status;
    private String notes;

    @OneToMany(mappedBy = "expense", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @Builder.Default
    private List<ExpenseLineItemEntity> lineItems = new ArrayList<>();

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
