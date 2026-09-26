package com.app.master.service.service.payment;

import com.app.master.service.core.entity.PaymentAttemptEntity;
import com.app.master.service.core.entity.PaymentWebhookEventEntity;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.payment.RazorpayProperties;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.repository.payment.PaymentAttemptRepository;
import com.app.master.service.repository.payment.PaymentWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Razorpay's side of the conversation.
 *
 * <p>A webhook is the only report of a payment that survives the customer
 * closing their browser mid-payment, so it cannot be treated as a nicety. It is
 * also the least trustworthy input the application receives: it arrives
 * unauthenticated from the public internet, so the signature is checked before
 * the body is even parsed.
 *
 * <p>Razorpay retries a webhook it believes was not acknowledged, which means
 * the same event arrives more than once as a matter of routine. Deduplication
 * is by inserting the gateway's own event id under a unique index — the second
 * arrival loses the insert and stops, rather than passing an {@code if} check
 * that a simultaneous delivery would also have passed.
 *
 * <p>The events handled are the current Razorpay payment and refund events:
 * {@code payment.captured}, {@code payment.authorized}, {@code payment.failed},
 * {@code refund.processed} and {@code refund.failed}. Anything else is recorded
 * and ignored rather than guessed at.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentWebhookService {

    private final RazorpayGateway razorpay;
    private final RazorpayProperties razorpayProperties;
    private final PaymentWebhookEventRepository eventRepo;
    private final PaymentAttemptRepository attemptRepo;
    private final PaymentService paymentService;

    /** What happened to a delivery, for the response and for the audit row. */
    public enum Outcome { PROCESSED, DUPLICATE, IGNORED }

    /**
     * Handles one webhook delivery.
     *
     * @param rawBody   the body exactly as received — the signature is over these
     *                  bytes, so it must not have been re-serialised
     * @param signature the {@code X-Razorpay-Signature} header
     * @param eventId   the {@code X-Razorpay-Event-Id} header, which is what
     *                  makes redelivery detectable
     */
    @Transactional(rollbackFor = Exception.class)
    public Outcome handle(String rawBody, String signature, String eventId) throws VeloriaException {

        if (!razorpayProperties.isWebhookConfigured()) {
            // Without the secret nothing can be verified, so nothing may be
            // trusted. Refusing is the only safe answer.
            log.error("Webhook received but RAZORPAY_WEBHOOK_SECRET is not configured; refusing it");
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "Webhook verification is not available.");
        }

        if (!razorpay.isWebhookSignatureValid(rawBody, signature)) {
            log.warn("Webhook rejected: signature did not verify (event {})", eventId);
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "Invalid webhook signature.");
        }

        if (eventId == null || eventId.isBlank()) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST, "Webhook is missing its event id.");
        }

        JSONObject body = new JSONObject(rawBody);
        String eventType = body.optString("event", "unknown");

        // Claim the event. Losing this race means another delivery of the same
        // event is already being applied, and this one must do nothing.
        PaymentWebhookEventEntity record;
        try {
            record = eventRepo.saveAndFlush(PaymentWebhookEventEntity.builder()
                    .eventId(eventId)
                    .eventType(eventType)
                    .status("RECEIVED")
                    .receivedAt(Instant.now())
                    .build());
        } catch (DataIntegrityViolationException duplicate) {
            log.info("Webhook {} ({}) has already been delivered; ignoring the repeat", eventId, eventType);
            return Outcome.DUPLICATE;
        }

        Optional<String> paymentId = paymentIdOf(body);
        record.setRazorpayPaymentId(paymentId.orElse(null));
        record.setRazorpayOrderId(orderIdOf(body).orElse(null));

        Outcome outcome = switch (eventType) {
            case "payment.captured", "payment.authorized", "payment.failed" ->
                    applyPaymentEvent(eventType, paymentId.orElse(null), record);
            case "refund.processed", "refund.failed" ->
                    // The refund lifecycle is recorded but not yet acted upon;
                    // see the refund section of the P0-7 report.
                    note(record, "Refund event recorded; refund processing is not enabled");
            default ->
                    note(record, "No handler for this event type");
        };

        record.setProcessedAt(Instant.now());
        record.setStatus(outcome.name().equals("PROCESSED") ? "PROCESSED" : "IGNORED");
        eventRepo.save(record);
        return outcome;
    }

    /**
     * Applies a payment event by asking Razorpay what the payment actually is.
     *
     * <p>The webhook body is not used as the source of truth for status or
     * amount. It tells us which payment to look at; {@link PaymentService}
     * re-reads it from the gateway and applies the same checks the browser
     * callback goes through, so the two paths cannot reach different answers.
     */
    private Outcome applyPaymentEvent(String eventType, String razorpayPaymentId,
                                      PaymentWebhookEventEntity record) throws VeloriaException {
        if (razorpayPaymentId == null) {
            return note(record, "Event carried no payment id");
        }

        Optional<PaymentAttemptEntity> attempt = attemptRepo.findByRazorpayPaymentId(razorpayPaymentId);
        if (attempt.isEmpty()) {
            // The browser may not have reported back yet, so find the attempt
            // through the gateway order instead.
            attempt = Optional.ofNullable(record.getRazorpayOrderId())
                    .flatMap(attemptRepo::findByRazorpayOrderId);
        }
        if (attempt.isEmpty()) {
            log.warn("Webhook {} refers to payment {} which matches no local attempt",
                    eventType, razorpayPaymentId);
            return note(record, "No local payment attempt matches this event");
        }

        paymentService.applyGatewayTruth(attempt.get(), razorpayPaymentId, "webhook " + eventType);
        return Outcome.PROCESSED;
    }

    private Outcome note(PaymentWebhookEventEntity record, String why) {
        record.setNote(why);
        log.info("Webhook {} ({}): {}", record.getEventId(), record.getEventType(), why);
        return Outcome.IGNORED;
    }

    private static Optional<String> paymentIdOf(JSONObject body) {
        return entity(body, "payment").map(e -> e.optString("id", null));
    }

    private static Optional<String> orderIdOf(JSONObject body) {
        return entity(body, "payment").map(e -> e.optString("order_id", null));
    }

    /** Razorpay nests the subject under payload.&lt;type&gt;.entity. */
    private static Optional<JSONObject> entity(JSONObject body, String type) {
        JSONObject payload = body.optJSONObject("payload");
        if (payload == null) return Optional.empty();
        JSONObject wrapper = payload.optJSONObject(type);
        if (wrapper == null) return Optional.empty();
        return Optional.ofNullable(wrapper.optJSONObject("entity"));
    }
}
