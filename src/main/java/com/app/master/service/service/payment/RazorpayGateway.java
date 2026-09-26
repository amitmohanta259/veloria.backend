package com.app.master.service.service.payment;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.payment.RazorpayProperties;
import com.app.master.service.core.response.ResponseCode;
import com.razorpay.Order;
import com.razorpay.Payment;
import com.razorpay.Refund;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import com.razorpay.Utils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.stereotype.Component;

/**
 * Everything this application says to Razorpay, and the only place it does.
 *
 * <p>Keeping the vendor call sites together means the credential is read in one
 * place, the failure modes are translated in one place, and the rest of the
 * payment service can be tested without a network.
 *
 * <p>Signature checks use the SDK's own {@code Utils} rather than a hand-written
 * HMAC. The algorithm is simple enough to reimplement and that is exactly why it
 * should not be: a comparison that is subtly non-constant-time, or a payload
 * assembled in the wrong order, fails open.
 */
@Component
@Slf4j
public class RazorpayGateway {

    private final RazorpayProperties properties;

    public RazorpayGateway(RazorpayProperties properties) {
        this.properties = properties;
    }

    /** Refuses early and clearly rather than failing somewhere inside the SDK. */
    private RazorpayClient client() throws VeloriaException {
        if (!properties.isConfigured()) {
            throw new VeloriaException(ResponseCode.INTERNAL_ERROR,
                    "Online payment is not available at the moment. Please try again later.");
        }
        try {
            return new RazorpayClient(properties.getKeyId(), properties.getKeySecret());
        } catch (RazorpayException e) {
            throw gatewayFailure("open a connection to the payment provider", e);
        }
    }

    /**
     * Creates the gateway order that Checkout will be opened against.
     *
     * @param amountPaise integer paise, computed by this application from the
     *                    order's own figures — never from the browser
     * @param receipt     a stable local identifier, so a Razorpay order can
     *                    always be traced back to the attempt that created it
     */
    public String createOrder(long amountPaise, String currency, String receipt) throws VeloriaException {
        // Razorpay rejects anything under one rupee. Catching it here gives the
        // customer a sensible message instead of a gateway error code.
        if (amountPaise < 100) {
            throw new VeloriaException(ResponseCode.BAD_REQUEST,
                    "This order is below the minimum amount that can be paid online.");
        }
        JSONObject request = new JSONObject()
                .put("amount", amountPaise)
                .put("currency", currency)
                .put("receipt", receipt)
                // Capture automatically: an authorized-but-uncaptured payment
                // expires and has to be reconciled by hand.
                .put("payment_capture", 1);
        try {
            Order order = client().orders.create(request);
            String id = order.get("id");
            log.info("Razorpay order {} created for receipt {} ({} paise)", id, receipt, amountPaise);
            return id;
        } catch (RazorpayException e) {
            throw gatewayFailure("create a payment order", e);
        }
    }

    /**
     * Fetches a payment from Razorpay so its real state can be checked.
     *
     * <p>This is the step that makes verification trustworthy. A valid signature
     * proves the callback came from Razorpay; it does not prove the payment was
     * captured, that it was for the right amount, or that it belongs to this
     * order. Only the gateway's own record of the payment shows that.
     */
    public PaymentView fetchPayment(String razorpayPaymentId) throws VeloriaException {
        try {
            Payment p = client().payments.fetch(razorpayPaymentId);
            return new PaymentView(
                    p.get("id"),
                    p.has("order_id") ? p.get("order_id") : null,
                    p.get("status"),
                    ((Number) p.get("amount")).longValue(),
                    p.get("currency"),
                    p.has("method") ? p.get("method") : null,
                    p.has("error_code") ? String.valueOf(p.get("error_code")) : null,
                    p.has("error_description") ? String.valueOf(p.get("error_description")) : null,
                    p.has("error_source") ? String.valueOf(p.get("error_source")) : null,
                    feePaise(p));
        } catch (RazorpayException e) {
            throw gatewayFailure("check the payment with the provider", e);
        }
    }

    /** Asks Razorpay to return money for a captured payment. */
    public String createRefund(String razorpayPaymentId, long amountPaise, String localRefundReference)
            throws VeloriaException {
        JSONObject request = new JSONObject()
                .put("amount", amountPaise)
                .put("speed", "normal")
                // Razorpay deduplicates on this, so a retried refund request
                // does not return the money twice.
                .put("receipt", localRefundReference);
        try {
            Refund refund = client().payments.refund(razorpayPaymentId, request);
            String id = refund.get("id");
            log.info("Razorpay refund {} created against payment {} ({} paise)",
                    id, razorpayPaymentId, amountPaise);
            return id;
        } catch (RazorpayException e) {
            throw gatewayFailure("process the refund", e);
        }
    }

    // ── signatures ───────────────────────────────────────────────────────────

    /**
     * Whether a Checkout callback really came from Razorpay.
     *
     * <p>Verifies {@code HMAC-SHA256(razorpay_order_id|razorpay_payment_id)}
     * against the key secret. True here means "genuinely from Razorpay" and
     * nothing more — the caller still has to establish what the payment is.
     */
    public boolean isCallbackSignatureValid(String razorpayOrderId, String razorpayPaymentId, String signature) {
        if (razorpayOrderId == null || razorpayPaymentId == null || signature == null
                || !properties.isConfigured()) {
            return false;
        }
        try {
            JSONObject payload = new JSONObject()
                    .put("razorpay_order_id", razorpayOrderId)
                    .put("razorpay_payment_id", razorpayPaymentId)
                    .put("razorpay_signature", signature);
            return Utils.verifyPaymentSignature(payload, properties.getKeySecret());
        } catch (RazorpayException e) {
            // A malformed signature is a failed check, not an error to surface.
            log.warn("Payment signature could not be verified for order {}", razorpayOrderId);
            return false;
        }
    }

    /** Whether a webhook body really came from Razorpay, using the webhook secret. */
    public boolean isWebhookSignatureValid(String rawBody, String signature) {
        if (rawBody == null || signature == null || !properties.isWebhookConfigured()) {
            return false;
        }
        try {
            return Utils.verifyWebhookSignature(rawBody, signature, properties.getWebhookSecret());
        } catch (RazorpayException e) {
            log.warn("Webhook signature could not be verified");
            return false;
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The fee Razorpay charged, when it has told us. Never inferred from a rate. */
    private static Long feePaise(Payment p) {
        try {
            Object fee = p.get("fee");
            return fee == null ? null : ((Number) fee).longValue();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Turns a gateway failure into something safe to show a customer.
     *
     * <p>The provider's own message can carry account and request detail, so it
     * is logged and not returned.
     */
    private VeloriaException gatewayFailure(String what, RazorpayException e) {
        log.error("Razorpay could not {}: {}", what, e.getMessage());
        return new VeloriaException(ResponseCode.INTERNAL_ERROR,
                "We could not " + what + ". Your money has not been taken. Please try again.");
    }

    /** A payment as Razorpay currently reports it. */
    public record PaymentView(
            String id,
            String orderId,
            String status,
            long amountPaise,
            String currency,
            String method,
            String errorCode,
            String errorDescription,
            String errorSource,
            Long feePaise) {

        public boolean isCaptured() { return "captured".equalsIgnoreCase(status); }
        public boolean isAuthorized() { return "authorized".equalsIgnoreCase(status); }
        public boolean isFailed() { return "failed".equalsIgnoreCase(status); }
    }
}
