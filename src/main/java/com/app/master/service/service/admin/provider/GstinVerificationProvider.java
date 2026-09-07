package com.app.master.service.service.admin.provider;

/**
 * Checks a GSTIN (spec phase 25).
 *
 * Two implementations exist and they are not interchangeable:
 * {@code LocalFormatValidator} can only confirm the string is well-formed, and
 * a government provider can confirm the registration actually exists. Passing a
 * regex is never reported as VERIFIED.
 */
public interface GstinVerificationProvider {

    /** UNVERIFIED, FORMAT_VALID, VERIFIED, INVALID, VERIFICATION_FAILED */
    record Result(
            String status,
            String legalName,
            String tradeName,
            String stateCode,
            String registrationStatus,
            String providerReference,
            String error
    ) {
        public static Result formatValid(String stateCode) {
            return new Result("FORMAT_VALID", null, null, stateCode, null, null, null);
        }
        public static Result invalid(String reason) {
            return new Result("INVALID", null, null, null, null, null, reason);
        }
        public static Result failed(String reason) {
            return new Result("VERIFICATION_FAILED", null, null, null, null, null, reason);
        }
    }

    String name();

    /** True when this provider can actually reach the authority it claims to. */
    boolean isConfigured();

    Result verify(String gstin);
}
