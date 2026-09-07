package com.app.master.service.service.admin.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Portal-backed GSTIN verification.
 *
 * EXTERNAL DEPENDENCY — not implemented. Verifying a GSTIN against the GST
 * portal needs credentials and an API agreement this deployment does not have.
 * The adapter exists so the integration can be added without touching callers,
 * and it reports itself as unconfigured so nothing mistakes it for working.
 */
@Component
@Slf4j
public class GovernmentGstinVerificationProvider implements GstinVerificationProvider {

    @Value("${veloria.gst.gstin-verification.api-url:}")
    private String apiUrl;

    @Value("${veloria.gst.gstin-verification.api-key:}")
    private String apiKey;

    @Override public String name() { return "GOVERNMENT_PORTAL"; }

    @Override
    public boolean isConfigured() {
        return apiUrl != null && !apiUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public Result verify(String gstin) {
        if (!isConfigured()) {
            return Result.failed("EXTERNAL_DEPENDENCY: GSTIN verification is not configured. "
                    + "Set veloria.gst.gstin-verification.api-url and .api-key to enable it.");
        }
        // Intentionally not implemented. Wiring a real call here is the only
        // thing that should ever produce a VERIFIED result.
        log.warn("GSTIN verification requested for a configured provider, but no client is implemented");
        return Result.failed("EXTERNAL_DEPENDENCY: no verification client is implemented for this provider");
    }
}
