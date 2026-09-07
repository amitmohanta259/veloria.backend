package com.app.master.service.core.entity;

import com.app.master.service.core.dto.Base;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@SuperBuilder
@Entity
@Table(name = "customer_order")
public class CustomerOrderEntity extends Base {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    private String orderCode;
    private String customerId;
    private String customerName;
    private String customerEmail;
    private String deliveryLocation;
    private Long totalValue;
    private String cancelReason;
    private String currency;
    private String status;
    private Instant orderPlacedAt;
    private Instant deliveredAt;

    private Long taxableValue;
    private Long cgstAmount;
    private Long sgstAmount;
    private Long igstAmount;
    private Long totalTaxAmount;
    private String buyerStateCode;
    private String sellerStateCode;
    private String placeOfSupply;

    /** POSTED, PENDING_REVIEW, FAILED — visibility for GST write failures. */
    @Builder.Default
    private String gstStatus = "POSTED";

    /** B2B or B2C (spec section 5). */
    @Builder.Default
    private String customerType = "B2C";

    private String customerGstin;
    private String sellerGstin;
    private String customerLegalName;

    /** Commercial breakdown (spec phases 11-13). GST is charged on taxableValue. */
    @Builder.Default private Long grossValue = 0L;
    @Builder.Default private Long discountValue = 0L;
    private String couponCode;
    @Builder.Default private Long shippingValue = 0L;
    @Builder.Default private Long shippingTaxableValue = 0L;
    @Builder.Default private Long cessAmount = 0L;
    @Builder.Default private Long roundOff = 0L;

    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Builder.Default
    private Boolean archive = Boolean.FALSE;
}
