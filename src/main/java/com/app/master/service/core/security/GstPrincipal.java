package com.app.master.service.core.security;

import java.util.Set;

/**
 * The authenticated caller's GST context, resolved from the bearer token.
 *
 * Tenancy is carried here rather than read from request parameters: an
 * organization id supplied by the client would be trivially tampered with
 * (spec sections 58 and 74).
 */
public record GstPrincipal(
        String subject,
        String username,
        Long organizationId,
        Long gstRegistrationId,
        Set<String> roles,
        Set<String> permissions
) {
    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    /** Masked for logs — never log a full GSTIN or raw token subject. */
    public String describe() {
        return username + " (org=" + organizationId + ", reg=" + gstRegistrationId + ")";
    }
}
