package com.app.master.service.service.admin.provider;

/**
 * Registers an invoice with the Invoice Registration Portal (spec phase 7).
 *
 * The application decides whether an invoice needs an IRN and keeps the record;
 * it does not mint one. An IRN, acknowledgement number, acknowledgement date,
 * signed payload and QR string may only ever come from an IRP response — there
 * is no code path in this application that generates any of them.
 *
 * When no credentials are configured the only honest answer is
 * {@code EXTERNAL_DEPENDENCY}, which is what {@link Result#notConfigured} says.
 */
public interface EinvoiceProvider {

    /** NOT_APPLICABLE, PENDING, READY, SUBMITTED, FAILED, GENERATED */
    String NOT_APPLICABLE = "NOT_APPLICABLE";
    String PENDING        = "PENDING";
    String READY          = "READY";
    String SUBMITTED      = "SUBMITTED";
    String FAILED         = "FAILED";
    String GENERATED      = "GENERATED";

    /**
     * What the IRP said.
     *
     * {@code GENERATED} is only valid alongside both an IRN and an
     * acknowledgement number; {@link #isGenerated()} is what callers check
     * before persisting, so a partial response cannot be stored as a success.
     */
    record Result(
            String status,
            String irn,
            String acknowledgementNumber,
            String acknowledgementDate,
            String signedInvoice,
            String signedQrCode,
            String providerReference,
            String error
    ) {
        public static Result notConfigured(String detail) {
            return new Result(FAILED, null, null, null, null, null, null,
                    "EXTERNAL_DEPENDENCY: " + detail);
        }

        public static Result failed(String reason) {
            return new Result(FAILED, null, null, null, null, null, null, reason);
        }

        /** True only for a response carrying the identifiers an IRN record needs. */
        public boolean isGenerated() {
            return GENERATED.equals(status)
                    && irn != null && !irn.isBlank()
                    && acknowledgementNumber != null && !acknowledgementNumber.isBlank();
        }
    }

    String name();

    /** True when this provider can actually reach the IRP it claims to. */
    boolean isConfigured();

    /** Submits the invoice payload for registration. */
    Result register(Long salesInvoiceId, String payload);
}
