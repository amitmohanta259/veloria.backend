package com.app.master.service.core.config;

import com.app.master.service.core.exception.VeloriaException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

public class CustomAuthenticationManager implements AuthenticationManager {

    // Built once and reused for every request so its cached JWKS lookup
    // (see ValidateToken) actually persists across requests instead of
    // re-fetching Keycloak's signing keys on every single call.
    private final ValidateToken validateToken;

    public CustomAuthenticationManager(String baseurl, String realm) {
        this.validateToken = new ValidateToken(baseurl, realm);
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        System.out.println("Authentication get request to verify the token.");
        System.out.println("Token Validated.");
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = null;
        if (attributes != null) {
            // Access the HttpServletRequest
            request = attributes.getRequest();
        }

        try {
            if (validateToken.isAuthorize(request.getHeader("Authorization").replace("Bearer ", ""))) {
                authentication.setAuthenticated(true);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } else {
                authentication.setAuthenticated(false);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        } catch (VeloriaException e) {
            authentication.setAuthenticated(false);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            return authentication;
        }

        return authentication;
    }
}