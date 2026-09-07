package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of a return: which order item, how many units, and the GST snapshot
 * being reversed for exactly those units.
 *
 * The GST figures here are a proportional share of the ORIGINAL order item's
 * stored snapshot — never recalculated from the current tax master.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "order_return_item")
public class OrderReturnItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long returnRequestId;

    @Column(nullable = false)
    private Long orderItemId;

    private UUID productUuid;
    private String productName;
    private String hsnCode;

    @Column(nullable = false)
    private Integer quantity;

    /** PRODUCT_OK, DAMAGED, LOST — physical condition, drives inventory only. */
    private String returnCondition;

    @Builder.Default
    private Long unitPricePaise = 0L;
    @Builder.Default
    private Long taxableValuePaise = 0L;

    @Builder.Default
    private Integer cgstRateBp = 0;
    @Builder.Default
    private Integer sgstRateBp = 0;
    @Builder.Default
    private Integer igstRateBp = 0;

    @Builder.Default
    private Long cgstAmountPaise = 0L;
    @Builder.Default
    private Long sgstAmountPaise = 0L;
    @Builder.Default
    private Long igstAmountPaise = 0L;
    @Builder.Default
    private Long totalTaxPaise = 0L;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
