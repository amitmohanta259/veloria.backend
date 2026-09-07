package com.app.master.service.service.admin.provider;

import com.app.master.service.service.admin.GstIdentityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Offline GSTIN check. Confirms the format and the embedded state code only.
 *
 * Deliberately returns FORMAT_VALID, never VERIFIED — this provider has no way
 * to know whether the registration exists or is active.
 */
@Component
@RequiredArgsConstructor
public class LocalFormatValidator implements GstinVerificationProvider {

    private final GstIdentityService identityService;

    @Override public String name() { return "LOCAL_FORMAT"; }

    /** Always available; it needs nothing external. */
    @Override public boolean isConfigured() { return true; }

    @Override
    public Result verify(String gstin) {
        if (gstin == null || gstin.isBlank()) {
            return Result.invalid("No GSTIN supplied");
        }
        if (!identityService.isValidGstin(gstin)) {
            return Result.invalid("GSTIN does not match the expected format");
        }
        String stateCode = identityService.stateCodeOf(gstin);
        if (!identityService.isKnownStateCode(stateCode)) {
            return Result.invalid("GSTIN declares an unknown state code: " + stateCode);
        }
        return Result.formatValid(stateCode);
    }
}
