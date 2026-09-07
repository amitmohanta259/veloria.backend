package com.app.master.service.service.admin.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The e-way bill provider used until portal credentials exist. Always returns
 * EXTERNAL_DEPENDENCY rather than a fabricated bill number.
 */
@Component
@Slf4j
public class UnconfiguredEwayBillProvider implements EwayBillProvider {

    @Value("${veloria.gst.ewaybill.base-url:}")
    private String baseUrl;

    @Value("${veloria.gst.ewaybill.username:}")
    private String username;

    @Override
    public String name() {
        return "UNCONFIGURED";
    }

    @Override
    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank()
                && username != null && !username.isBlank();
    }

    @Override
    public Result generate(Long salesInvoiceId, String payload) {
        log.info("e-way bill requested for invoice {} but no portal is configured", salesInvoiceId);
        return Result.notConfigured(
                "No e-way bill portal is configured. Set veloria.gst.ewaybill.base-url and "
                + "credentials, and supply a real portal client, before a bill number can be obtained.");
    }
}
