package com.cloudcode.wantok.keycloak;

import org.apache.ofbiz.base.util.UtilProperties;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;

/**
 * Loads OIDC configuration for one Wantok/OFBiz tenant.
 *
 * Important for multi-tenant mode:
 * - Prefer storing WanKeycloakTenantConfig inside the tenant database.
 * - Use wankeycloak.properties only as a local-development fallback.
 * - Do not commit real client secrets. Use the WANTOK_KEYCLOAK_CLIENT_SECRET environment variable.
 */
public final class WanKeycloakConfig {
    private static final String RESOURCE = "wankeycloak";

    private WanKeycloakConfig() {}

    public static OidcTenantConfig forTenant(Delegator delegator, String tenantId) throws Exception {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required for Keycloak/OIDC configuration");
        }

        OidcTenantConfig cfg = new OidcTenantConfig();
        cfg.tenantId = tenantId;

        GenericValue row = null;
        try {
            row = EntityQuery.use(delegator)
                    .from("WanKeycloakTenantConfig")
                    .where("tenantId", tenantId, "enabled", "Y")
                    .queryOne();
        } catch (Exception ignored) {
            // Allows first local test before custom entities/data are loaded.
        }

        if (row != null) {
            cfg.realmName = row.getString("realmName");
            cfg.organizationAlias = row.getString("organizationAlias");
            cfg.issuerUrl = row.getString("issuerUrl");
            cfg.authServerUrl = row.getString("authServerUrl");
            cfg.clientId = row.getString("clientId");
            cfg.clientSecretAlias = row.getString("clientSecretAlias");
            cfg.clientSecret = resolveClientSecret(cfg.clientSecretAlias);
            cfg.redirectUri = row.getString("redirectUri");
            cfg.postLoginPath = row.getString("postLoginPath");
            cfg.postLogoutUri = row.getString("postLogoutUri");
            cfg.defaultOrgPartyId = row.getString("defaultOrgPartyId");
            return cfg;
        }

        cfg.realmName = UtilProperties.getPropertyValue(RESOURCE, "wankeycloak.defaultRealm", "wantok-saas");
        cfg.organizationAlias = UtilProperties.getPropertyValue(RESOURCE, "wankeycloak.defaultOrganizationAlias", "cloudcode");
        cfg.issuerUrl = UtilProperties.getPropertyValue(RESOURCE, "wankeycloak.defaultIssuer", "http://localhost:8180/realms/wantok-saas");
        if (cfg.issuerUrl.contains("/realms/")) {
            cfg.authServerUrl = cfg.issuerUrl.substring(0, cfg.issuerUrl.indexOf("/realms/"));
        } else {
            cfg.authServerUrl = UtilProperties.getPropertyValue(RESOURCE, "wankeycloak.defaultAuthServerUrl", "http://localhost:8180");
        }
        cfg.clientId = UtilProperties.getPropertyValue(RESOURCE, "wankeycloak.defaultClientId", "wantok-erp-web");
        cfg.clientSecretAlias = UtilProperties.getPropertyValue(RESOURCE, "wankeycloak.defaultClientSecretAlias", "WANTOK_KEYCLOAK_CLIENT_SECRET_CLOUDCODE_WEB");
        cfg.clientSecret = resolveClientSecret(cfg.clientSecretAlias);
        cfg.redirectUri = UtilProperties.getPropertyValue(RESOURCE, "wankeycloak.defaultRedirectUri", "http://localhost:8080/wankeycloak/control/oidcCallback");
        cfg.postLoginPath = UtilProperties.getPropertyValue(RESOURCE, "wankeycloak.defaultPostLoginPath", "/partymgr/control/main");
        cfg.postLogoutUri = UtilProperties.getPropertyValue(RESOURCE, "wankeycloak.defaultPostLogoutUri", "http://localhost:8080/wankeycloak/control/loggedOut");
        cfg.defaultOrgPartyId = UtilProperties.getPropertyValue(RESOURCE, "wankeycloak.defaultOrgPartyId", "CLOUDCODE_PNG");
        return cfg;
    }

    private static String resolveClientSecret(String alias) {
        if (alias != null && !alias.isBlank()) {
            String byAlias = System.getenv(alias);
            if (byAlias != null && !byAlias.isBlank()) {
                return byAlias;
            }
        }
        String generic = System.getenv("WANTOK_KEYCLOAK_CLIENT_SECRET");
        if (generic != null && !generic.isBlank()) {
            return generic;
        }
        return UtilProperties.getPropertyValue(RESOURCE, "wankeycloak.defaultClientSecret");
    }
}
