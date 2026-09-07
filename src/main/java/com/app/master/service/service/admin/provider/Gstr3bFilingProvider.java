package com.app.master.service.service.admin.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GSTR-3B filing.
 *
 * EXTERNAL DEPENDENCY — not implemented, for the same reasons as GSTR-1.
 */
@Component
@Slf4j
public class Gstr3bFilingProvider implements GstrFilingProvider {

    @Value("${veloria.gst.filing.gstr3b.api-url:}")
    private String apiUrl;

    @Value("${veloria.gst.filing.gstr3b.api-key:}")
    private String apiKey;

    @Override public String returnType() { return "GSTR3B"; }
    @Override public String name() { return "GSTR3B_PORTAL"; }

    @Override
    public boolean isConfigured() {
        return apiUrl != null && !apiUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public FilingResult submit(String taxPeriod, String payload) {
        if (!isConfigured()) {
            return FilingResult.notConfigured(
                    "GSTR-3B filing is not configured. Requires GST portal credentials and the "
                    + "current official return schema. Record a manual filing reference instead.");
        }
        log.warn("GSTR-3B filing requested but no portal client is implemented");
        return FilingResult.notConfigured("no filing client is implemented for GSTR-3B");
    }
}
