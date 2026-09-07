package com.app.master.service.core.security;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads the current GST principal out of the security context.
 *
 * Services call this rather than accepting an organization id as a parameter,
 * so a caller cannot address another tenant's data by changing a request field.
 */
@Component
public class GstSecurityContext {

    public Optional<GstPrincipal> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken jwtAuth) || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        Jwt jwt = jwtAuth.getToken();

        Set<String> roles = claimSet(jwt, "gst_roles");
        Set<String> permissions = roles.stream()
                .flatMap(r -> GstPermission.permissionsOf(r).stream())
                .collect(Collectors.toSet());

        return Optional.of(new GstPrincipal(
                jwt.getSubject(),
                jwt.getClaimAsString("preferred_username") != null
                        ? jwt.getClaimAsString("preferred_username")
                        : jwt.getSubject(),
                claimLong(jwt, "organization_id"),
                claimLong(jwt, "gst_registration_id"),
                roles,
                permissions));
    }

    /** The principal, or a 401-shaped error when the request is unauthenticated. */
    public GstPrincipal require() throws VeloriaException {
        return current().orElseThrow(() ->
                new VeloriaException(ResponseCode.UNAUTHORIZED, "Authentication required for GST operations"));
    }

    /** The caller's organization, used to scope every GST query. */
    public Long organizationId() throws VeloriaException {
        Long org = require().organizationId();
        if (org == null) {
            throw new VeloriaException(ResponseCode.ACCESS_DENIED,
                    "Token carries no organization; GST data cannot be scoped");
        }
        return org;
    }

    public Long gstRegistrationId() throws VeloriaException {
        return require().gstRegistrationId();
    }

    /** Username for audit records. */
    public String actor() {
        return current().map(GstPrincipal::username).orElse("SYSTEM");
    }

    @SuppressWarnings("unchecked")
    private Set<String> claimSet(Jwt jwt, String name) {
        Object raw = jwt.getClaim(name);
        if (raw instanceof java.util.Collection<?> c) {
            return c.stream().map(String::valueOf).collect(Collectors.toSet());
        }
        if (raw instanceof String s && !s.isBlank()) {
            return Set.of(s.split("\\s*,\\s*"));
        }
        return Set.of();
    }

    private Long claimLong(Jwt jwt, String name) {
        Object raw = jwt.getClaim(name);
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s && !s.isBlank()) {
            try { return Long.parseLong(s.trim()); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }
}
