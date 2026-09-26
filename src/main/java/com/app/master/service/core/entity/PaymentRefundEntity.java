package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Money given back for one payment attempt.
 *
 * <p>An attempt may be refunded more than once — a partial refund followed by
 * another — so this is a separate table rather than columns on the attempt.
 *
 * <p>A row here is a <em>request</em> until the gateway confirms it. The local
 * record existing is not the same as the customer having their money, which is
 * why {@code status} and {@code completedAt} are separate from creation.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "payment_refund")
public class PaymentRefundEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    private UUID uuid = UUID.randomUUID();

    @Column(name = "payment_attempt_id", nullable = false)
    private Long paymentAttemptId;

    @Column(name = "customer_order_id", nullable = false)
    private Long customerOrderId;

    @Column(name = "order_code", nullable = false)
    private String orderCode;

    /** Refund idempotency: the same request twice must not return money twice. */
    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "razorpay_refund_id")
    private String razorpayRefundId;

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    /**
     * What the refund gave back, by head.
     *
     * <p>There is no transportation, COD-fee or gateway-fee column because the
     * approved policy does not refund any of them. Their absence is the policy,
     * expressed in the schema.
     */
    @Builder.Default
    @Column(name = "product_refunded_paise", nullable = false)
    private Long productRefundedPaise = 0L;

    @Builder.Default
    @Column(name = "gst_refunded_paise", nullable = false)
    private Long gstRefundedPaise = 0L;

    /** REQUESTED, COMPLETED or FAILED. Only COMPLETED means the money moved. */
    @Column(nullable = false)
    private String status;

    private String reason;

    /**
     * The verified return this refund was earned by.
     *
     * <p>The approved workflow refuses to refund without one, so recording which
     * return it was makes that rule auditable afterwards rather than only
     * enforced at the moment of the call.
     */
    @Column(name = "return_request_id")
    private Long returnRequestId;

    /**
     * Where the money went. {@code RAZORPAY_SOURCE} — back to whatever instrument
     * paid, which is the gateway's own behaviour and not a routing decision made
     * here. A cash-on-delivery order has no electronic destination at all; see the
     * COD refund blocker in the P0-11 report.
     */
    @Column(name = "refund_destination")
    private String refundDestination;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "failure_description")
    private String failureDescription;
}
