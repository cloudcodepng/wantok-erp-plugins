package com.cloudcode.wantok.keycloak;

public class OidcTenantConfig {
    // ALLOW PUBLIC FIELDS
    // This is an existing mutable configuration DTO. Keeping its field-based API
    // avoids changing any callers of the Keycloak tenant configuration.
    public String tenantId;
    public String realmName;
    public String organizationAlias;
    public String issuerUrl;
    public String authServerUrl;
    public String clientId;
    public String clientSecretAlias;
    public String clientSecret;
    public String redirectUri;
    public String postLoginPath;
    public String postLogoutUri;
    public String defaultOrgPartyId;
    // FORBID PUBLIC FIELDS
}
