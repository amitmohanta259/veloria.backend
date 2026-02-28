package com.app.master.service.core.service;

import com.app.master.service.core.config.ClaimTransformer;
import com.app.master.service.core.config.TenantContextHolder;
import com.app.master.service.core.dto.User;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.nimbusds.jwt.SignedJWT;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
public abstract class AppService {

    @Autowired
    private MessageService messageService;

    private static String clientId;

    @Value("${keycloak.client-id}")
    private void setClientId(String id) {
        clientId = id;
    }

    protected final Logger logger = LoggerFactory.getLogger(this.getClass());

    protected void throwError(ResponseCode code, String message) throws VeloriaException {
        log.error("Custom Exception : [{}] {}", code, message);
        throw new VeloriaException(code, message);
    }

    protected void throwError(Exception exception) throws VeloriaException {
        log.error("Handled Exception : {}", exception.getMessage());
        throwError(new VeloriaException(exception));
    }

    protected VeloriaException throwException(ResponseCode code, String... args) {
        return new VeloriaException(code, messageService.getMessage(code, args));
    }

    protected static User getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (Objects.isNull(auth)) return null;
        try {
            SignedJWT signedJWT = SignedJWT.parse(auth.getPrincipal().toString());
            User user = signedJWT.getJWTClaimsSet().toType(new ClaimTransformer());
            return user;
        } catch (Exception e) {
            log.error("Exception occurred while parsing token: ", e);
            return User.builder().iamId("created from local").build();
        }
    }

    public static List<String> getRoles(Map<String, Object> attributes) {

        Map<String, Object> resourceAccess = (Map<String, Object>) attributes.get("resource_access");
        Map<String, Object> realAccess = (Map<String, Object>) attributes.get("realm_access");
        Map<String, Object> clientRole = (Map<String, Object>) attributes.get(clientId);

        return new ArrayList<String>(clientRole != null ? (List<String>) clientRole.get("roles") : (List<String>) realAccess.get("roles"));
    }

    //todo: this is for Audit
    protected static String getCurrentIamIdForAudit() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (Objects.isNull(auth)) return null;
        try {
            SignedJWT signedJWT = SignedJWT.parse(auth.getCredentials().toString());
            User user = signedJWT.getJWTClaimsSet().toType(new ClaimTransformer());
            return user.getIamId();
        } catch (Exception e) {
            log.error("Exception occurred while parsing token for audit: ", e);
            return null;
        }
    }

    protected static String getCurrentProviderGroupForAudit() {
        String schema = new TenantContextHolder().getTenant();
        if (schema == null || schema.isBlank()) {
            return "Public";
        } else return schema;
    }

}