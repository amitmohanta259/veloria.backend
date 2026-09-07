package com.app.master.service.core.security;

import com.app.master.service.core.controller.AppController;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.Response;
import com.app.master.service.core.response.ResponseCode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mints GST bearer tokens for local development only.
 *
 * This exists because Keycloak is not reachable from a developer machine, and
 * the alternative — leaving GST endpoints open — is the security hole this work
 * is closing. Authorization is therefore genuinely enforced and testable here.
 *
 * Three guards keep it out of production:
 *   1. {@code @Profile("local")} — the bean does not exist under any other profile
 *   2. it refuses to start unless {@code veloria.gst.security.mode} is {@code local}
 *   3. the signing secret has no default and must be supplied explicitly
 *
 * This endpoint must never be exposed on a deployed environment. The production
 * path is Keycloak, configured through {@code veloria.gst.security.mode=keycloak}.
 */
@RestController
@RequestMapping("/api/master/dev-auth")
@Profile("local")
@Slf4j
public class LocalGstTokenController extends AppController {

    @Value("${veloria.gst.security.mode:keycloak}")
    private String mode;

    @Value("${veloria.gst.security.local-secret:}")
    private String secret;

    @Value("${veloria.gst.security.local-token-minutes:480}")
    private long tokenMinutes;

    /**
     * Issues a token for a named role set and tenant.
     *
     * <pre>
     * POST /api/master/dev-auth/token
     * { "username": "amit", "roles": ["GST_ADMIN"],
     *   "organizationId": 1, "gstRegistrationId": 1 }
     * </pre>
     */
    @PostMapping("/token")
    public ResponseEntity<Response> token(@RequestBody TokenRequest request) throws Exception {
        if (!"local".equalsIgnoreCase(mode)) {
            throw new VeloriaException(ResponseCode.ACCESS_DENIED,
                    "Local token issuance is disabled; veloria.gst.security.mode is " + mode);
        }
        if (secret == null || secret.length() < 32) {
            throw new VeloriaException(ResponseCode.INTERNAL_ERROR,
                    "veloria.gst.security.local-secret is not configured");
        }

        List<String> roles = request.getRoles() == null || request.getRoles().isEmpty()
                ? List.of(GstPermission.GST_VIEWER)
                : request.getRoles();

        for (String r : roles) {
            if (GstPermission.permissionsOf(r).isEmpty()) {
                throw new VeloriaException(ResponseCode.BAD_REQUEST,
                        "Unknown GST role '" + r + "'. Valid roles: " + GstPermission.ALL_ROLES);
            }
        }

        String username = request.getUsername() != null ? request.getUsername() : "local-dev";
        Instant now = Instant.now();
        Instant exp = now.plus(tokenMinutes, ChronoUnit.MINUTES);

        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("local-" + username)
                .claim("preferred_username", username)
                .claim("gst_roles", roles)
                .claim("organization_id", request.getOrganizationId() != null ? request.getOrganizationId() : 1L)
                .claim("gst_registration_id", request.getGstRegistrationId())
                .issuer("veloria-local")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(exp))
                .build();

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            JWSSigner signer = new MACSigner(secret.getBytes(StandardCharsets.UTF_8));
            jwt.sign(signer);
        } catch (JOSEException e) {
            throw new VeloriaException(ResponseCode.INTERNAL_ERROR, "Could not sign local token");
        }

        // Never log the token itself.
        log.info("Local GST token issued for {} with roles {}", username, roles);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accessToken", jwt.serialize());
        payload.put("tokenType", "Bearer");
        payload.put("expiresAt", exp.toString());
        payload.put("roles", roles);
        payload.put("permissions", roles.stream()
                .flatMap(r -> GstPermission.permissionsOf(r).stream()).distinct().sorted().toList());
        return data(ResponseCode.OK, "Local GST token issued", payload);
    }

    /** The role catalogue, so the dev UI can offer a role picker. */
    @GetMapping("/roles")
    public ResponseEntity<Response> roles() {
        List<Map<String, Object>> out = GstPermission.ALL_ROLES.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", r);
            m.put("permissions", GstPermission.permissionsOf(r).stream().sorted().toList());
            return m;
        }).toList();
        return data(ResponseCode.FETCHED, "GST roles fetched", out);
    }

    @lombok.Data
    public static class TokenRequest {
        private String username;
        private List<String> roles;
        private Long organizationId;
        private Long gstRegistrationId;
    }
}
