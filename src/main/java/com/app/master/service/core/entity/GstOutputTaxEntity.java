package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "gst_output_tax")
public class GstOutputTaxEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long customerOrderId;
    private UUID customerOrderUuid;

    private String orderCode;
    private String customerName;
    private String customerGstin;
    private String placeOfSupplyStateCode;
    private String supplyType;

    private String financialYear;
    private String taxPeriod;
    private LocalDate invoiceDate;

    private Long taxableValue;
    private BigDecimal cgstRate;
    private Long cgstAmount;
    private BigDecimal sgstRate;
    private Long sgstAmount;
    private BigDecimal igstRate;
    private Long igstAmount;
    private Long totalOutputTax;
    private Long invoiceValue;

    private String returnStatus;
    private String gstType = "OUTPUT_TAX";

    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    /** Tenant scope (spec section 58). */
    private Long organizationId;
    private Long gstRegistrationId;

    @PreUpdate
    void preUpdate() { updatedAt = Instant.now(); }
}
