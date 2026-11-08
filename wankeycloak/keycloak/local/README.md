# Keycloak local setup notes

Recommended local Keycloak URLs:

- Admin console: http://localhost:8180/admin
- Realm issuer: http://localhost:8180/realms/wantok-saas
- OFBiz redirect URI: http://localhost:8080/wankeycloak/control/oidcCallback

After creating the `wantok-saas` realm, enable Organizations and create:

- CLOUDCODE
- NEA

Add protocol mappers to include these token claims:

- `tenant_id`
- `organization_code`
- `default_org_party_id`

For CLOUDCODE users, set user attributes:

- tenant_id=CLOUDCODE
- organization_code=CLOUDCODE
- default_org_party_id=CLOUDCODE_PNG
