package com.app.master.service.core.config;

import com.app.master.service.core.dto.constants.Constant;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.service.AppService;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.Date;
import java.util.List;

public class ValidateToken extends AppService {

    @Value("${keycloak.base-url}")
    private String baseUrl;

    // Cached against Keycloak's JWKS endpoint: served from cache on every call,
    // and only re-fetched (rate-limited) when a key id isn't found in the cache -
    // i.e. after Keycloak rotates its signing keys. See RemoteJWKSet/DefaultJWKSetCache.
    private final JWKSource<SecurityContext> jwkSource;

    public ValidateToken(String baseurl, String realm) {
        this.baseUrl = baseurl;
        this.jwkSource = buildJwkSource(baseurl, realm);
    }

    private static JWKSource<SecurityContext> buildJwkSource(String baseUrl, String realm) {
        try {
            String jwksUri = baseUrl + "/realms/" + realm + "/protocol/openid-connect/certs";
            return new RemoteJWKSet<>(new URL(jwksUri));
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Invalid Keycloak JWKS URL", e);
        }
    }

    public boolean isAuthorize(String accessToken) throws VeloriaException {
        //Parse jwt token
        SignedJWT signedJWT = null;
        try {
            signedJWT = SignedJWT.parse(accessToken);
        } catch (ParseException e) {
            throwError(ResponseCode.IAM_ERROR, e.getMessage());
        }

        //Get public key from keycloak realm by parsing key id.
        assert signedJWT != null;
        RSAKey rsaKey;

        rsaKey = getPublicKeyFromKeycloakRealm(signedJWT.getHeader().getKeyID());

        if (verifySignature(signedJWT, rsaKey)) {
            Date expirationDate = null;
            Date now = new Date();
            try {
                expirationDate = signedJWT.getJWTClaimsSet().getExpirationTime();
            } catch (ParseException e) {
                throwError(ResponseCode.UNAUTHORIZED, e.getMessage());
            }

            boolean authenticated = expirationDate != null && expirationDate.after(now);
            if (authenticated) {
                System.out.println("User authenticated from validate token.");
                return authenticated;
            } else {
                System.out.println("user token is expired.");
                throwError(ResponseCode.UNAUTHORIZED, "user token is expired.");
                return authenticated;
            }
        } else {
            System.out.println("user not verified");
            return false;
        }
    }

    private RSAKey getPublicKeyFromKeycloakRealm(String keyId) throws VeloriaException {

        try {
            JWKSelector selector = new JWKSelector(new JWKMatcher.Builder().keyID(keyId).build());
            List<JWK> matches = jwkSource.get(selector, null);
            JWK jwk = matches.isEmpty() ? null : matches.get(0);

            // Convert to RSAKey
            if (jwk instanceof RSAKey) {
                return (RSAKey) jwk;
            } else {
                throwError(ResponseCode.UNAUTHORIZED, "Not an instance of RSA key.");
            }
        } catch (Exception e) {
            throwError(ResponseCode.UNAUTHORIZED, e.getMessage());
        }
        return null;
    }

    private boolean verifySignature(SignedJWT signedJWT, RSAKey rsaKey) throws VeloriaException {
        RSAPublicKey publicKey = null;
        try {
            publicKey = rsaKey.toRSAPublicKey();
        } catch (JOSEException e) {
            throwError(ResponseCode.UNAUTHORIZED, e.getMessage());
        }

        // Create a verifier for the RSA public key
        JWSVerifier verifier = new RSASSAVerifier(publicKey);

        // Verify the signature
        try {
            return signedJWT.verify(verifier);
        } catch (JOSEException e) {
            throwError(ResponseCode.UNAUTHORIZED, e.getMessage());
        }

        return false;
    }

}