package com.app.master.service.core.config;

import com.app.master.service.core.service.AppService;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableMethodSecurity(securedEnabled = true, jsr250Enabled = true)
@EnableAsync
public class WebSecurityConfig extends AppService {

    @Autowired
    private Keycloak keycloak;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.base-url}")
    private String baseurl;

    @Bean
    public WebSecurityCustomizer ignoreResources() {
        return (webSecurity) -> webSecurity
                .ignoring()
                .requestMatchers(
                        "/api/master/**",
                        "/api/master/api-docs/**",
                        "/api/master/swagger-ui/**",
                        "/api/master/swagger-ui.html",
                        "/api/master/api-docs",

                        //Template download
                        "/api/master/data-import/sample/*",

                        //With new end-points Master service
                        "/api/master/login",
                        "/api/master/reset-password",
                        "/api/master/forgot-password",
                        "/api/master/forgot-password/resend",
                        "/api/master/forgot-password/verify",
                        "/api/master/verify-link",
                        "/api/master/set-password",
                        "/api/master/resend-link",
                        "/api/master/refresh-token",
                        "/api/master/auth/patient",
                        "/api/master/provider/username",
                        "/api/master/patient/add-card",
                        "/api/master/user/{uuid}",
                        "api/master/user/email",
                        "/api/master/reset-password",
                        "/api/master/event/subscribe/{eventKey}",
                        "/api/master/forgot-password-otp/verify/{email}",
                        "/api/master/data-import/download/icd",
                        "/api/master/data-import/download/drug",
                        "/api/master/data-import/download/clinician",
                        "/api/master/stripe/public-key"
                );
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                .csrf().disable()
                .cors().configurationSource(corsConfigurationSource()).and()
                .authorizeHttpRequests()
                .anyRequest()
                .authenticated().and()
                .oauth2ResourceServer()
                .accessDeniedHandler(accessDeniedHandler())
                .opaqueToken().authenticationManager(new CustomAuthenticationManager(baseurl));

        return http.build();
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler() {
        return new CustomAccessDeniedHandler();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

}