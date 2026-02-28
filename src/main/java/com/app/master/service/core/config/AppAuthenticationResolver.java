package com.app.master.service.core.config;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.authentication.OpaqueTokenAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.introspection.NimbusOpaqueTokenIntrospector;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

import java.util.Objects;

import static com.app.master.service.core.constant.RegexConstant.X_TENANT_ID;

@Configuration
@Slf4j
public class AppAuthenticationResolver implements AuthenticationManagerResolver<HttpServletRequest> {

    private String baseUrl;
    private String clientId;
    private String clientSecret;


    private String realm;

    public AppAuthenticationResolver(String baseUrl, String clientId, String clientSecret, String realm) {
        this.baseUrl = baseUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.realm = realm;
    }

    public AppAuthenticationResolver() {
    }

    @Override
    public AuthenticationManager resolve(HttpServletRequest context) {
        String tenantId = context.getHeader(X_TENANT_ID);
        tenantId = Objects.isNull(tenantId) ? realm : tenantId.toLowerCase().trim();
        OpaqueTokenIntrospector opaqueTokenIntrospector;
        String url = baseUrl + "/realms/" + tenantId + "/protocol/openid-connect/token/introspect";
        opaqueTokenIntrospector = new NimbusOpaqueTokenIntrospector(
                url,
                clientId, //Master's client id
                clientSecret); //master's client secret
        return new OpaqueTokenAuthenticationProvider(opaqueTokenIntrospector)::authenticate;
    }

}
