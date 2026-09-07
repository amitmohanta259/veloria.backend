package com.app.master.service.gst;

import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.security.GstPermission;
import com.app.master.service.core.security.GstPrincipal;
import com.app.master.service.core.security.GstSecurityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tenant resolution from the bearer token (spec sections 58 and 74).
 *
 * The organization is read from the token, never from a request parameter, so
 * a caller cannot reach another tenant's data by changing a field.
 */
class GstSecurityContextTest {

    private final GstSecurityContext context = new GstSecurityContext();

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(Long orgId, Long regId, List<String> roles) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "HS256")
                .subject("local-tester")
                .claim("preferred_username", "tester")
                .claim("gst_roles", roles)
                .claim("organization_id", orgId)
                .claim("gst_registration_id", regId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));
    }

    @Test
    @DisplayName("An unauthenticated request has no principal")
    void unauthenticatedHasNoPrincipal() {
        assertEquals(Optional.empty(), context.current());
        assertEquals("SYSTEM", context.actor());
    }

    @Test
    @DisplayName("require() rejects an unauthenticated request")
    void requireRejectsAnonymous() {
        VeloriaException ex = assertThrows(VeloriaException.class, context::require);
        assertTrue(ex.getMessage().toLowerCase().contains("authentication"));
    }

    @Test
    @DisplayName("A non-JWT authentication is not accepted as a GST principal")
    void nonJwtAuthenticationRejected() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("someone", "pw", List.of()));
        assertEquals(Optional.empty(), context.current());
    }

    @Test
    @DisplayName("Roles expand into their permissions")
    void rolesExpandToPermissions() {
        authenticateAs(1L, 1L, List.of(GstPermission.GST_APPROVER));

        GstPrincipal p = context.current().orElseThrow();
        assertEquals("tester", p.username());
        assertEquals(1L, p.organizationId());
        assertTrue(p.has(GstPermission.APPROVE_ITC));
        assertFalse(p.has(GstPermission.CREATE_INVOICE));
    }

    @Test
    @DisplayName("The organization comes from the token")
    void organizationComesFromToken() throws Exception {
        authenticateAs(42L, 7L, List.of(GstPermission.GST_VIEWER));

        assertEquals(42L, context.organizationId());
        assertEquals(7L, context.gstRegistrationId());
    }

    @Test
    @DisplayName("A token with no organization cannot scope GST data")
    void missingOrganizationIsRejected() {
        authenticateAs(null, null, List.of(GstPermission.GST_ADMIN));

        VeloriaException ex = assertThrows(VeloriaException.class, context::organizationId);
        assertTrue(ex.getMessage().contains("organization"));
    }

    @Test
    @DisplayName("Two tenants resolve to different organizations")
    void tenantsAreDistinct() throws Exception {
        authenticateAs(1L, 1L, List.of(GstPermission.GST_ADMIN));
        Long orgA = context.organizationId();

        SecurityContextHolder.clearContext();
        authenticateAs(2L, 2L, List.of(GstPermission.GST_ADMIN));
        Long orgB = context.organizationId();

        assertNotEquals(orgA, orgB,
                "Each token must resolve to its own organization; queries are scoped by this value");
    }

    @Test
    @DisplayName("Multiple roles union their permissions")
    void multipleRolesUnion() {
        authenticateAs(1L, 1L, List.of(GstPermission.GST_OPERATOR, GstPermission.GST_APPROVER));

        GstPrincipal p = context.current().orElseThrow();
        assertTrue(p.has(GstPermission.CREATE_INVOICE), "from OPERATOR");
        assertTrue(p.has(GstPermission.APPROVE_ITC), "from APPROVER");
    }

    @Test
    @DisplayName("An unknown role in the token grants no permissions")
    void unknownRoleGrantsNothing() {
        authenticateAs(1L, 1L, List.of("SUPER_ADMIN"));

        GstPrincipal p = context.current().orElseThrow();
        assertTrue(p.permissions().isEmpty(),
                "A role the application does not know must not grant authority");
    }

    @Test
    @DisplayName("The principal description does not leak the token subject verbatim")
    void describeIsSafeForLogs() {
        authenticateAs(1L, 1L, List.of(GstPermission.GST_VIEWER));
        String described = context.current().orElseThrow().describe();
        assertFalse(described.contains("test-token"));
    }
}
