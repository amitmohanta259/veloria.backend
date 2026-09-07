package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A vendor invoice line, with its own HSN and tax breakdown. */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "purchase_invoice_item")
public class PurchaseInvoiceItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long purchaseInvoiceId;

    private Long purchaseOrderItemId;
    private Integer lineNumber;

    private UUID productUuid;
    private String productName;
    private String description;
    private String hsnCode;

    @Builder.Default private Integer quantity = 1;
    @Builder.Default private String unit = "PCS";

    @Builder.Default private Long unitPrice = 0L;
    @Builder.Default private Long grossValue = 0L;
    @Builder.Default private Long discount = 0L;
    @Builder.Default private Long taxableValue = 0L;

    @Builder.Default private Integer gstRateBp = 0;
    @Builder.Default private Integer cgstRateBp = 0;
    @Builder.Default private Long cgstAmount = 0L;
    @Builder.Default private Integer sgstRateBp = 0;
    @Builder.Default private Long sgstAmount = 0L;
    @Builder.Default private Integer igstRateBp = 0;
    @Builder.Default private Long igstAmount = 0L;
    @Builder.Default private Integer cessRateBp = 0;
    @Builder.Default private Long cessAmount = 0L;

    @Builder.Default private Long totalTax = 0L;
    @Builder.Default private Long totalValue = 0L;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
