package com.cloudcode.wantok.keycloak;

import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

import java.sql.Timestamp;
import java.util.Map;

public class WanKeycloakUserMapper {
    public GenericValue ensureUserLogin(Delegator delegator, OidcTenantConfig cfg, Map<String,Object> claims) throws Exception {
        String sub = WanKeycloakUtil.stringClaim(claims, "sub");
        String username = WanKeycloakUtil.stringClaim(claims, "preferred_username");
        String email = WanKeycloakUtil.stringClaim(claims, "email");
        if (username == null || username.isBlank()) username = email != null ? email : sub;
        String userLoginId = username.toLowerCase().replace(' ', '.');

        GenericValue user = EntityQuery.use(delegator).from("UserLogin").where("userLoginId", userLoginId).queryOne();
        if (user == null) {
            user = delegator.makeValue("UserLogin");
            user.set("userLoginId", userLoginId);
            user.set("enabled", "Y");
            user.set("currentPassword", "KEYCLOAK_SSO_ONLY_" + WanKeycloakUtil.randomUrlToken(18));
            user.create();
        }
        try {
            GenericValue map = EntityQuery.use(delegator).from("WanKeycloakUserMap").where("tenantId", cfg.tenantId, "keycloakSubject", sub).queryOne();
            if (map == null) {
                map = delegator.makeValue("WanKeycloakUserMap");
                map.set("tenantId", cfg.tenantId);
                map.set("keycloakSubject", sub);
            }
            map.set("userLoginId", userLoginId);
            map.set("preferredUsername", username);
            map.set("emailAddress", email);
            map.set("lastLoginStamp", new Timestamp(System.currentTimeMillis()));
            map.set("enabled", "Y");
            delegator.createOrStore(map);
        } catch (Exception ignored) { }
        return user;
    }
}
