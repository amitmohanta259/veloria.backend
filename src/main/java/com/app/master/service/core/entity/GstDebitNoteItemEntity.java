package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** A debit note line. */
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
@Entity
@Table(name = "gst_debit_note_item")
public class GstDebitNoteItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long debitNoteId;

    private Long orderItemId;
    private UUID productUuid;
    private String productName;
    private String hsnCode;

    @Builder.Default private Integer quantity = 1;
    @Builder.Default private Long unitPricePaise = 0L;
    @Builder.Default private Long taxableValuePaise = 0L;

    @Builder.Default private Integer cgstRateBp = 0;
    @Builder.Default private Integer sgstRateBp = 0;
    @Builder.Default private Integer igstRateBp = 0;

    @Builder.Default private Long cgstAmountPaise = 0L;
    @Builder.Default private Long sgstAmountPaise = 0L;
    @Builder.Default private Long igstAmountPaise = 0L;
    @Builder.Default private Long totalTaxPaise = 0L;

    @Builder.Default
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
