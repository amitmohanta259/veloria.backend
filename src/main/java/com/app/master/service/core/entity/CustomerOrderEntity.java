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

    /** Client-generated, one per checkout attempt; reused on retry. Null for orders placed before idempotency existed. */
    @Column(name = "client_order_reference")
    private String clientOrderReference;

    /** SHA-256 of the canonical request, so a reused reference carrying a different cart can be refused. */
    @Column(name = "request_fingerprint")
    private String requestFingerprint;

    /** ONLINE_FULL, ONLINE_PARTIAL or COD. Null on orders placed before payment existed. */
    @Column(name = "payment_mode")
    private String paymentMode;

    /** The approved flat COD handling charge, in paise. Zero for every other mode. */
    @Builder.Default
    @Column(name = "cod_fee_paise")
    private Long codFeePaise = 0L;

    /**
     * The COD charge's own tax, frozen at the rate that applied when the order
     * was placed.
     *
     * <p>Deliberately separate from the product figures above: the product GST
     * snapshot is a statutory per-line figure and must not absorb a charge that
     * is not a product. {@code codFeeTaxRateBp} is null when no rate was
     * resolved, which is not the same as 0%.
     */
    @Builder.Default @Column(name = "cod_fee_taxable_paise") private Long codFeeTaxablePaise = 0L;
    @Builder.Default @Column(name = "cod_fee_cgst_paise")    private Long codFeeCgstPaise = 0L;
    @Builder.Default @Column(name = "cod_fee_sgst_paise")    private Long codFeeSgstPaise = 0L;
    @Builder.Default @Column(name = "cod_fee_igst_paise")    private Long codFeeIgstPaise = 0L;
    @Builder.Default @Column(name = "cod_fee_tax_paise")     private Long codFeeTaxPaise = 0L;
    @Column(name = "cod_fee_tax_rate_bp")   private Integer codFeeTaxRateBp;
    @Column(name = "cod_fee_sac_code")      private String codFeeSacCode;

    /** RULE_APPLIED, NO_RULE, NO_HSN or NOT_CONFIGURED. */
    @Column(name = "cod_fee_tax_resolution")
    private String codFeeTaxResolution;

    /** CUSTOMER, ADMIN, DELIVERY_PARTNER or SYSTEM. Recorded for every cancellation. */
    @Column(name = "cancelled_by")
    private String cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;
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
