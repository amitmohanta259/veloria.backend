package com.app.master.service.service.impl;

import com.app.master.service.core.constant.ResponseError;
import com.app.master.service.core.constant.ServiceConstants;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.request.AuthRequest;
import com.app.master.service.core.request.RegisterRequest;
import com.app.master.service.core.response.AuthResponse;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.service.AppService;
import com.app.master.service.service.AuthService;
import javax.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AuthServiceImpl extends AppService implements AuthService {

    @Value("${keycloak.base-url}")
    private String baseUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    private final RestTemplate restTemplate;
    private final Keycloak keycloak;

    public AuthServiceImpl(RestTemplate restTemplate, Keycloak keycloak) {
        this.restTemplate = restTemplate;
        this.keycloak = keycloak;
    }

    @Override
    public AuthResponse login(AuthRequest request) throws VeloriaException {

        String tokenUri = baseUrl + "/realms/" + realm + "/protocol/openid-connect/token";

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("username", request.getUsername());
        form.add("password", request.getPassword());
        form.add(ServiceConstants.SCOPE, ServiceConstants.OPENID);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        try {
            Map<String, Object> token = restTemplate.postForObject(tokenUri, new HttpEntity<>(form, headers), Map.class);

            if (token == null || token.get("access_token") == null) {
                throwError(ResponseCode.UNAUTHORIZED, ResponseError.INVALID_CREDENTIAL);
            }

            return AuthResponse.builder()
                    .accessToken((String) token.get("access_token"))
                    .refreshToken((String) token.get("refresh_token"))
                    .tokenType((String) token.get("token_type"))
                    .expiresIn(((Number) token.get("expires_in")).intValue())
                    .build();

        } catch (HttpClientErrorException e) {
            // Keycloak returns 400/401 for both "no such user" and "wrong password" -
            // deliberately kept generic here so the API can't be used to enumerate
            // valid usernames. The real reason is logged server-side only.
            log.warn("Keycloak rejected login attempt: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            throwError(ResponseCode.UNAUTHORIZED, ResponseError.INVALID_CREDENTIAL);
        } catch (VeloriaException e) {
            throw e;
        } catch (Exception e) {
            log.error("Keycloak token request failed", e);
            throwError(ResponseCode.IAM_ERROR, "Unable to reach the authentication service. Please try again.");
        }

        return null;
    }

    @Override
    public void register(RegisterRequest request) throws VeloriaException {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(request.getPassword());
        credential.setTemporary(false);

        UserRepresentation user = new UserRepresentation();
        user.setUsername(request.getEmail());
        user.setEmail(request.getEmail());
        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setEnabled(true);
        user.setEmailVerified(true);
        user.setCredentials(List.of(credential));
        user.setAttributes(Map.of("phone", List.of(request.getPhone())));

        try (Response response = keycloak.realm(realm).users().create(user)) {
            if (response.getStatus() == 201) {
                log.info("Keycloak user created: {}", request.getEmail());
            } else if (response.getStatus() == 409) {
                throwError(ResponseCode.BAD_REQUEST, "An account with this email already exists.");
            } else {
                log.error("Keycloak user creation failed: {} {}", response.getStatus(), response.readEntity(String.class));
                throwError(ResponseCode.IAM_ERROR, "Registration failed. Please try again.");
            }
        } catch (VeloriaException e) {
            throw e;
        } catch (Exception e) {
            log.error("Registration error", e);
            throwError(ResponseCode.IAM_ERROR, "Unable to complete registration. Please try again.");
        }
    }

}
