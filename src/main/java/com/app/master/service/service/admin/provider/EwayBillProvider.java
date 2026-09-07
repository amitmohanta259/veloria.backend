package com.app.master.service.service.admin.provider;

/**
 * Generates an e-way bill on the government portal (spec phase 8).
 *
 * As with e-invoicing, the application decides applicability from the invoice
 * value and the movement, and keeps the document record. The e-way bill number
 * and validity come from the portal or they do not exist — nothing here
 * fabricates one.
 */
public interface EwayBillProvider {

    /** NOT_APPLICABLE, PENDING, READY, SUBMITTED, FAILED, GENERATED */
    String NOT_APPLICABLE = "NOT_APPLICABLE";
    String PENDING        = "PENDING";
    String READY          = "READY";
    String SUBMITTED      = "SUBMITTED";
    String FAILED         = "FAILED";
    String GENERATED      = "GENERATED";

    /**
     * What the portal said. {@code GENERATED} requires a real bill number, which
     * {@link #isGenerated()} enforces before anything is persisted.
     */
    record Result(
            String status,
            String ewbNumber,
            String validUpto,
            String generatedDate,
            String providerReference,
            String error
    ) {
        public static Result notConfigured(String detail) {
            return new Result(FAILED, null, null, null, null, "EXTERNAL_DEPENDENCY: " + detail);
        }

        public static Result failed(String reason) {
            return new Result(FAILED, null, null, null, null, reason);
        }

        public boolean isGenerated() {
            return GENERATED.equals(status) && ewbNumber != null && !ewbNumber.isBlank();
        }
    }

    String name();

    /** True when this provider can actually reach the e-way bill portal. */
    boolean isConfigured();

    Result generate(Long salesInvoiceId, String payload);
}
