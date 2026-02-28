package com.app.master.service.core.config;

import com.app.master.service.core.dto.constants.Constant;
import com.app.master.service.core.exception.VeloriaException;
import com.app.master.service.core.response.ResponseCode;
import com.app.master.service.core.service.AppService;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;

import java.net.URL;
import java.security.interfaces.RSAPublicKey;
import java.text.ParseException;
import java.util.Date;

public class ValidateToken extends AppService {

    @Value("${keycloak.base-url}")
    private String baseUrl;

    public ValidateToken(String baseurl) {
        this.baseUrl = baseurl;
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

        try {
            rsaKey = getPublicKeyFromKeycloakRealm(signedJWT.getHeader().getKeyID(), Constant.REALM);
        } catch (Exception e) {
            rsaKey = getPublicKeyFromKeycloakRealm(signedJWT.getHeader().getKeyID(), Constant.REALM);
        }

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

    private RSAKey getPublicKeyFromKeycloakRealm(String keyId, String realm) throws VeloriaException {

        String jwksUri = baseUrl + "/realms/" + realm + "/protocol/openid-connect/certs";

        try {
            JWKSet jwkSet = JWKSet.load(new URL(jwksUri));
            JWK jwk = jwkSet.getKeyByKeyId(keyId);

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