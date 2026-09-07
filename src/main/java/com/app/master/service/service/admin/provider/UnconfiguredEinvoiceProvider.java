package com.app.master.service.service.admin.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The e-invoice provider used until IRP credentials exist.
 *
 * It never succeeds, and that is the point: an unconfigured integration returns
 * EXTERNAL_DEPENDENCY so no invoice can be marked registered on the strength of
 * a stub. Replace this bean with a real IRP client; no caller changes.
 */
@Component
@Slf4j
public class UnconfiguredEinvoiceProvider implements EinvoiceProvider {

    @Value("${veloria.gst.einvoice.base-url:}")
    private String baseUrl;

    @Value("${veloria.gst.einvoice.username:}")
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
    public Result register(Long salesInvoiceId, String payload) {
        log.info("e-invoice registration requested for invoice {} but no IRP is configured",
                salesInvoiceId);
        return Result.notConfigured(
                "No Invoice Registration Portal is configured. Set veloria.gst.einvoice.base-url "
                + "and credentials, and supply a real IRP client, before an IRN can be obtained.");
    }
}
