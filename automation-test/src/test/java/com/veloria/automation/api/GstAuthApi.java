package com.veloria.automation.api;

import java.util.List;
import java.util.Map;

/**
 * The dev-only issuer at /dev-auth/token. It exists only under the local
 * profile, which is the profile these tests run against; in a deployed
 * environment the GST token would come from Keycloak instead.
 */
public final class GstAuthApi {
    private GstAuthApi() {}

    public static String token(String role) {
        Http.Response r = Http.post("/dev-auth/token", Map.of(
                "username", "automation",
                "roles", List.of(role),
                "organizationId", 1,
                "gstRegistrationId", 1), null);
        if (!r.ok() || r.data() == null || !r.data().hasNonNull("accessToken")) {
            throw new IllegalStateException("GST dev token unavailable: HTTP " + r.status() + " " + r.raw());
        }
        return r.data().get("accessToken").asText();
    }
}
