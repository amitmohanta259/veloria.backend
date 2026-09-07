package com.app.master.service.service.admin.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * GSTR-1 filing.
 *
 * EXTERNAL DEPENDENCY — not implemented. Filing needs GST portal credentials,
 * an API agreement and the current official return schema, none of which this
 * deployment has. Returns FAILED with an explanation rather than inventing an
 * acknowledgement.
 */
@Component
@Slf4j
public class Gstr1FilingProvider implements GstrFilingProvider {

    @Value("${veloria.gst.filing.gstr1.api-url:}")
    private String apiUrl;

    @Value("${veloria.gst.filing.gstr1.api-key:}")
    private String apiKey;

    @Override public String returnType() { return "GSTR1"; }
    @Override public String name() { return "GSTR1_PORTAL"; }

    @Override
    public boolean isConfigured() {
        return apiUrl != null && !apiUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public FilingResult submit(String taxPeriod, String payload) {
        if (!isConfigured()) {
            return FilingResult.notConfigured(
                    "GSTR-1 filing is not configured. Requires GST portal credentials and the "
                    + "current official return schema. Record a manual filing reference instead.");
        }
        log.warn("GSTR-1 filing requested but no portal client is implemented");
        return FilingResult.notConfigured("no filing client is implemented for GSTR-1");
    }
}
