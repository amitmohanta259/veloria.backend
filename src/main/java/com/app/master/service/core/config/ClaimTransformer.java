package com.app.master.service.core.config;

import com.app.master.service.core.dto.User;
import com.app.master.service.core.enums.RoleType;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTClaimsSetTransformer;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ClaimTransformer implements JWTClaimsSetTransformer<User> {

    @Override
    public User transform(JWTClaimsSet jwtClaimsSet) {

        User user = User.builder()
                .userIdentifier(jwtClaimsSet.getClaims().containsKey("preferred_username") ? jwtClaimsSet.getClaims().get("preferred_username").toString() : null)
                .email(jwtClaimsSet.getClaims().containsKey("email") ? jwtClaimsSet.getClaims().get("email").toString() : null)
                .emailVerified(Boolean.parseBoolean(jwtClaimsSet.getClaims().containsKey("email_verified") ? jwtClaimsSet.getClaims().get("email_verified").toString() : "false"))
                .active(Boolean.parseBoolean(jwtClaimsSet.getClaims().containsKey("active") ? jwtClaimsSet.getClaims().get("active").toString() : "false"))
                .iamId(jwtClaimsSet.getClaims().containsKey("sub") ? jwtClaimsSet.getClaims().get("sub").toString() : null)
                .roleType(jwtClaimsSet.getClaims().containsKey("category") ? RoleType.valueOf(jwtClaimsSet.getClaims().get("category").toString()) : null)
                .tenantKey(jwtClaimsSet.getClaims().containsKey("groups") ? jwtClaimsSet.getClaims().get("groups").toString() : null)
                .build();
        System.out.println("current user iam : " + user.getIamId());
        return user;
    }

}