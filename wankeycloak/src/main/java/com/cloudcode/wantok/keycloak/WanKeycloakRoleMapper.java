package com.cloudcode.wantok.keycloak;

import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public class WanKeycloakRoleMapper {
    /**
     * Extracts realm, client and scope roles from verified token claims.
     * Overrides must retain all three sources to preserve access decisions.
     *
     * @param claims verified JWT claims
     * @param clientId Keycloak client identifier
     * @return discovered Keycloak role names
     */
    @SuppressWarnings("unchecked")
    public Set<String> extractRoles(Map<String, Object> claims, String clientId) {
        Set<String> roles = new LinkedHashSet<>();
        Object realmAccess = claims.get("realm_access");
        if (realmAccess instanceof Map) {
            Object r = ((Map<String, Object>) realmAccess).get("roles");
            if (r instanceof Collection) {
                for (Object x : (Collection<?>) r) {
                    roles.add(String.valueOf(x));
                }
            }
        }
        Object resourceAccess = claims.get("resource_access");
        if (resourceAccess instanceof Map) {
            Object client = ((Map<String, Object>) resourceAccess).get(clientId);
            if (client instanceof Map) {
                Object r = ((Map<String, Object>) client).get("roles");
                if (r instanceof Collection) {
                    for (Object x : (Collection<?>) r) {
                        roles.add(String.valueOf(x));
                    }
                }
            }
        }
        Object scope = claims.get("scope");
        if (scope instanceof String) roles.addAll(Arrays.asList(((String) scope).split(" ")));
        return roles;
    }

    /**
     * Assigns tenant-local OFBiz security groups for mapped Keycloak roles.
     * Overrides must use the supplied tenant delegator and honor role mappings.
     *
     * @param delegator tenant-specific OFBiz delegator
     * @param cfg tenant OIDC configuration
     * @param userLoginId local OFBiz login ID
     * @param keycloakRoles verified Keycloak role names
     * @throws Exception on entity failures
     */
    public void syncSecurityGroups(Delegator delegator, OidcTenantConfig cfg,
            String userLoginId, Set<String> keycloakRoles) throws Exception {
        for (String kcRole : keycloakRoles) {
            GenericValue map = null;
            try {
                map = EntityQuery.use(delegator).from("WanKeycloakRoleMap")
                        .where("tenantId", cfg.tenantId, "keycloakRole", kcRole, "enabled", "Y").queryOne();
            } catch (Exception ignored) { }
            if (map == null) continue;
            String groupId = map.getString("securityGroupId");
            GenericValue existing = EntityQuery.use(delegator).from("UserLoginSecurityGroup")
                    .where("userLoginId", userLoginId, "groupId", groupId).filterByDate().queryFirst();
            if (existing == null) {
                GenericValue ug = delegator.makeValue("UserLoginSecurityGroup");
                ug.set("userLoginId", userLoginId);
                ug.set("groupId", groupId);
                ug.set("fromDate", UtilDateTime.nowTimestamp());
                ug.create();
            }
        }
    }
}
