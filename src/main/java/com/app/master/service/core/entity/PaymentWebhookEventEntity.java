package com.app.master.service.core.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * A webhook Razorpay has delivered to us.
 *
 * <p>Exists to make redelivery harmless. Razorpay retries a webhook it believes
 * was not acknowledged, so the same event can arrive several times; inserting
 * the gateway's own event id under a unique index means the second arrival
 * fails at the database rather than applying a second state transition,
 * releasing inventory twice, or posting a second accounting effect.
 *
 * <p>The payload itself is not stored. It can contain customer contact details
 * and gateway metadata that serve no purpose once the event is applied, and
 * keeping it would widen what a database leak exposes.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Entity
@Table(name = "payment_webhook_event")
public class PaymentWebhookEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Razorpay's x-razorpay-event-id. Unique — this is the deduplication. */
    @Column(name = "event_id", nullable = false)
    private String eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "razorpay_order_id")
    private String razorpayOrderId;

    @Column(name = "razorpay_payment_id")
    private String razorpayPaymentId;

    /** RECEIVED, PROCESSED, IGNORED or FAILED. */
    @Column(nullable = false)
    private String status;

    private String note;

    @Builder.Default
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;
}
