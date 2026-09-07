package com.app.master.service.service.admin.provider;

/**
 * Submits a prepared return to the GST portal (spec phase 21).
 *
 * The application prepares returns; it does not file them. This interface is
 * the seam where a real filing integration attaches. Nothing in the codebase
 * may produce an ARN or acknowledgement number except an implementation that
 * genuinely received one from the portal.
 */
public interface GstrFilingProvider {

    /** GSTR1 or GSTR3B */
    String returnType();

    String name();

    boolean isConfigured();

    /**
     * A filing outcome. FILED requires a real acknowledgement number; the
     * caller rejects the result otherwise.
     */
    record FilingResult(
            String status,
            String acknowledgementNumber,
            String filingReference,
            String error
    ) {
        public static FilingResult notConfigured(String detail) {
            return new FilingResult("FAILED", null, null, "EXTERNAL_DEPENDENCY: " + detail);
        }
    }

    FilingResult submit(String taxPeriod, String payload);
}
