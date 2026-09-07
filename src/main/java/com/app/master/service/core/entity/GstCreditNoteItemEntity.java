package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * A credit note line (spec section 15). Contains only the returned items and
 * quantities, with the GST amounts taken from the original invoice snapshot.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "gst_credit_note_item")
public class GstCreditNoteItemEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long creditNoteId;

    private Long orderItemId;
    private Long returnItemId;

    private UUID productUuid;
    private String productName;
    private String hsnCode;

    @Column(nullable = false)
    private Integer quantity;

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
