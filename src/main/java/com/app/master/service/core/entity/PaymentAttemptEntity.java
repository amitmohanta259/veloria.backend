package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One attempt to collect money for an order.
 *
 * <p>An order has many of these. A partially paid order has two successful ones
 * — the first half and the balance — and any order may carry failed attempts
 * alongside a successful one. A failed attempt is never overwritten: it is the
 * record of something that happened.
 *
 * <p>Deliberately not extending {@code Base}: the payment tables carry their own
 * timestamps and have no archive flag, because a payment is never soft-deleted.
 *
 * <p>No instrument data is stored here. {@code paymentMethod} holds what the
 * gateway reported — "card", "upi" — and nothing more. There is no column for a
 * card number, a CVV, a PIN or an OTP, and none may be added.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "payment_attempt")
public class PaymentAttemptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    @Column(name = "customer_order_id", nullable = false)
    private Long customerOrderId;

    @Column(name = "order_code", nullable = false)
    private String orderCode;

    /** The customer this attempt belongs to. Checked before anyone may pay or verify. */
    @Column(name = "customer_id", nullable = false)
    private String customerId;

    /**
     * Payment idempotency.
     *
     * <p>Not {@code clientOrderReference}, which identifies a checkout attempt
     * and belongs to the order (P0-3). One order can legitimately need several
     * payment attempts; reusing the order's reference would make the second one
     * look like a duplicate of the first.
     */
    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    /** 1 for the first collection, 2 for the balance of a partial payment. */
    @Builder.Default
    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo = 1;

    /** RAZORPAY or COD. */
    @Column(nullable = false)
    private String gateway;

    @Column(nullable = false)
    private String status;

    /** Integer paise, computed by the server. Never taken from a request body. */
    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Builder.Default
    @Column(nullable = false)
    private String currency = "INR";

    // ── the allocation this payment carried, frozen at the time it was taken ──

    @Builder.Default
    @Column(name = "product_allocated_paise", nullable = false)
    private Long productAllocatedPaise = 0L;

    @Builder.Default
    @Column(name = "gst_allocated_paise", nullable = false)
    private Long gstAllocatedPaise = 0L;

    @Builder.Default
    @Column(name = "transport_allocated_paise", nullable = false)
    private Long transportAllocatedPaise = 0L;

    @Builder.Default
    @Column(name = "other_allocated_paise", nullable = false)
    private Long otherAllocatedPaise = 0L;

    // ── gateway identifiers ──────────────────────────────────────────────────

    @Column(name = "razorpay_order_id")
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id")
    private String razorpayPaymentId;

    /** What the gateway says was used: "card", "upi", "netbanking", "wallet". */
    @Column(name = "payment_method")
    private String paymentMethod;

    /**
     * What the gateway charged, once it is known.
     *
     * <p>Null means not yet known, which is the honest state until settlement
     * data arrives. It is never filled in from an assumed percentage.
     */
    @Column(name = "gateway_fee_paise")
    private Long gatewayFeePaise;

    /**
     * The approved 50/50 split of the fee, and what the bank is left with.
     *
     * <p>All three are derived from {@link #gatewayFeePaise} — the fee the gateway
     * actually charged — and from nothing else. No percentage is applied anywhere:
     * a rate would be a guess at what the gateway will charge, and the whole point
     * is that this reflects what it did charge.
     *
     * <p>Null while the fee is null, because half of an unknown number is not
     * zero. The odd paisa of an odd fee goes to the business, so the customer is
     * never charged the extra one.
     */
    @Column(name = "gateway_fee_business_paise")
    private Long gatewayFeeBusinessPaise;

    @Column(name = "gateway_fee_customer_paise")
    private Long gatewayFeeCustomerPaise;

    /** Gross paid, less the fee: what the settlement is expected to bring in. */
    @Column(name = "net_settlement_paise")
    private Long netSettlementPaise;

    // ── failure detail, safe fields only ─────────────────────────────────────

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_description")
    private String failureDescription;

    @Column(name = "failure_source")
    private String failureSource;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "failed_at")
    private Instant failedAt;
}
