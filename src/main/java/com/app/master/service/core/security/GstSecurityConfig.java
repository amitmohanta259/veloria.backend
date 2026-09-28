package com.app.master.service.core.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authentication and authorization for GST endpoints (spec sections 42, 43, 57).
 *
 * This chain is ordered ahead of the application's permissive default chain and
 * matches only GST paths, so enabling it does not change authentication for the
 * rest of the application.
 *
 * Token verification has two modes, selected by {@code veloria.gst.security.mode}:
 *   keycloak — verify against the Keycloak realm's JWKS (the production path)
 *   local    — verify an HMAC token minted by {@link LocalGstTokenController}
 *
 * The local mode exists so authorization is genuinely enforced and testable on a
 * developer machine that cannot reach Keycloak. It is refused outside the local
 * profile; see {@link LocalGstTokenController}.
 */
@Configuration
public class GstSecurityConfig {

    /** Paths that carry GST accounting authority. */
    static final String[] GST_PATHS = {
            "/api/master/gst/**",
            "/api/master/gst-accounting/**",
            "/api/master/returns/**"
    };

    /**
     * Engineering paths (P0-17).
     *
     * Authentication is enforced at the URL layer here, like {@link #GST_PATHS} and
     * deliberately unlike {@link #ADMIN_TOKEN_PATHS}: the permissive pattern relies
     * entirely on each method carrying {@code @PreAuthorize}, so a new endpoint that
     * forgets the annotation would be reachable by anyone on the network. For a
     * feature whose whole purpose is to expose where the data is wrong, the failure
     * mode has to be a 401, not an open endpoint.
     *
     * The method annotations stay as well — defence in depth, and they are what
     * distinguishes running a scan from reading one.
     */
    static final String[] ENGINEERING_PATHS = {
            "/api/master/engineering/**"
    };

    /**
     * Administrative paths whose <em>state-altering</em> endpoints are guarded by
     * {@code @PreAuthorize}.
     *
     * These are matched only so a bearer token is decoded into an
     * {@link org.springframework.security.core.Authentication}; without a chain
     * covering them no token is parsed at all and the method annotations would
     * deny every caller, including legitimate administrators.
     *
     * The URL layer here stays permissive on purpose. Authorization is decided
     * per method, so the read endpoints these controllers also expose keep
     * working exactly as before and only the mutations are gated.
     */
    static final String[] ADMIN_TOKEN_PATHS = {
            "/api/master/accounting/**",
            "/api/master/inventory-product/**",
            "/api/master/expense/**",
            "/api/master/salary-payment/**",
            // Order fulfilment: advancing, cancelling and reading orders. Until
            // P0-5A these were reachable by anyone on the network, which meant
            // an anonymous caller could mark an order delivered or cancel it.
            "/api/master/sales-order/**"
    };

    @Value("${veloria.gst.security.mode:keycloak}")
    private String mode;

    @Value("${veloria.gst.security.local-secret:}")
    private String localSecret;

    @Value("${keycloak.base-url:}")
    private String keycloakBaseUrl;

    @Value("${keycloak.realm:}")
    private String keycloakRealm;

    @Bean
    @Order(1)
    public SecurityFilterChain gstFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher(GST_PATHS)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(gstAuthenticationConverter())));
        return http.build();
    }

    /**
     * Engineering: authenticated at the URL layer, authorized per method.
     *
     * Ordered ahead of the admin-token chain so the stricter rule wins for
     * /engineering/**; an anonymous request is refused before it reaches a
     * controller.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain engineeringFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher(ENGINEERING_PATHS)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(gstAuthenticationConverter())));
        return http.build();
    }

    /**
     * Decodes a bearer token on the administrative paths so {@code @PreAuthorize}
     * has an authenticated principal to judge.
     *
     * Ordered after the GST chain and before the application-wide permissive
     * chain. A request with no token still reaches the controller as anonymous
     * and is refused by the method annotation on the mutating endpoints; a
     * request with a token is judged on the authorities it carries.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain adminTokenFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher(ADMIN_TOKEN_PATHS)
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .oauth2ResourceServer(oauth -> oauth
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(gstAuthenticationConverter())));
        return http.build();
    }

    /**
     * Expands the token's {@code gst_roles} claim into permission authorities so
     * endpoints can require the permission they actually need.
     *
     * Deliberately not a {@code @Bean}: a bare {@code Converter} bean is also
     * picked up by Spring MVC's conversion service, which cannot resolve the
     * generic types of a lambda and fails the whole context at startup.
     */
    private Converter<Jwt, AbstractAuthenticationToken> gstAuthenticationConverter() {
        return jwt -> {
            Set<String> roles = readRoles(jwt);
            Collection<GrantedAuthority> authorities = roles.stream()
                    .flatMap(role -> java.util.stream.Stream.concat(
                            java.util.stream.Stream.of("ROLE_" + role),
                            GstPermission.permissionsOf(role).stream()))
                    .distinct()
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());
            return new JwtAuthenticationToken(jwt, authorities);
        };
    }

    @Bean
    public JwtDecoder gstJwtDecoder() {
        if ("local".equalsIgnoreCase(mode)) {
            if (localSecret == null || localSecret.length() < 32) {
                throw new IllegalStateException(
                        "veloria.gst.security.local-secret must be at least 32 characters when mode=local");
            }
            SecretKeySpec key = new SecretKeySpec(
                    localSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            return NimbusJwtDecoder.withSecretKey(key)
                    .macAlgorithm(MacAlgorithm.HS256)
                    .build();
        }
        String jwks = keycloakBaseUrl + "/realms/" + keycloakRealm + "/protocol/openid-connect/certs";
        return NimbusJwtDecoder.withJwkSetUri(jwks).build();
    }

    @SuppressWarnings("unchecked")
    private Set<String> readRoles(Jwt jwt) {
        Object raw = jwt.getClaim("gst_roles");
        if (raw instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).collect(Collectors.toSet());
        }
        if (raw instanceof String s && !s.isBlank()) {
            return Set.of(s.split("\\s*,\\s*"));
        }
        return Set.of();
    }
}
