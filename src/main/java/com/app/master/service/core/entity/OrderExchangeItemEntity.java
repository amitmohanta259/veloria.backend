package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One line of an exchange, on either the returned or the replacement side. */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "order_exchange_item")
public class OrderExchangeItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long exchangeRequestId;

    /** RETURNED or REPLACEMENT */
    @Column(nullable = false)
    private String side;

    private Long orderItemId;
    private UUID productUuid;
    private String productName;
    private String hsnCode;

    @Column(nullable = false)
    private Integer quantity;

    @Builder.Default private Long unitPricePaise = 0L;
    @Builder.Default private Long taxableValuePaise = 0L;
    @Builder.Default private Long cgstAmountPaise = 0L;
    @Builder.Default private Long sgstAmountPaise = 0L;
    @Builder.Default private Long igstAmountPaise = 0L;
    @Builder.Default private Long cessAmountPaise = 0L;
    @Builder.Default private Long totalTaxPaise = 0L;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
