package com.app.master.service.core.payment;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Razorpay credentials, read from the environment and nowhere else.
 *
 * <p>The three values are supplied as {@code RAZORPAY_KEY_ID},
 * {@code RAZORPAY_KEY_SECRET} and {@code RAZORPAY_WEBHOOK_SECRET}. None of them
 * has a default, appears in a configuration file, or is written to a log. Only
 * the key id is ever sent to a browser; the other two never leave this process.
 *
 * <p>The application starts without them so that the rest of the system remains
 * usable on a machine with no gateway credentials — but every payment path
 * refuses to run, loudly, rather than pretending to work. That is deliberate:
 * a payment endpoint that silently no-ops is worse than one that fails.
 *
 * <p><b>Live keys are refused.</b> A key id beginning {@code rzp_live_} is
 * rejected at startup. This integration is sandbox-only until a separate
 * production review says otherwise, and the safest place to enforce that is
 * where the credential is read.
 */
@Component
@Slf4j
@Getter
public class RazorpayProperties {

    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;

    public RazorpayProperties(
            @Value("${RAZORPAY_KEY_ID:}") String keyId,
            @Value("${RAZORPAY_KEY_SECRET:}") String keySecret,
            @Value("${RAZORPAY_WEBHOOK_SECRET:}") String webhookSecret) {

        this.keyId = trimmed(keyId);
        this.keySecret = trimmed(keySecret);
        this.webhookSecret = trimmed(webhookSecret);

        if (this.keyId != null && this.keyId.startsWith("rzp_live_")) {
            throw new IllegalStateException(
                    "RAZORPAY_KEY_ID is a live key. This integration is approved for test mode only; "
                    + "production use requires a separate review.");
        }

        if (!isConfigured()) {
            log.warn("Razorpay is not configured (RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET absent). "
                   + "Online payment endpoints will refuse requests until they are set.");
        } else {
            // The id is public and identifies the account being used; the
            // secrets are never logged, not even their length.
            log.info("Razorpay configured for key {} (webhook secret {})",
                    this.keyId, this.webhookSecret == null ? "ABSENT" : "present");
        }
    }

    /** Whether online payment can operate at all. */
    public boolean isConfigured() {
        return keyId != null && keySecret != null;
    }

    /** Whether webhooks can be verified. Without this, webhooks must be refused, not trusted. */
    public boolean isWebhookConfigured() {
        return webhookSecret != null;
    }

    private static String trimmed(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * Never let a secret reach a log or an error message through this object.
     */
    @Override
    public String toString() {
        return "RazorpayProperties{keyId=" + keyId + ", secrets=REDACTED}";
    }
}
