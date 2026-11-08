# wankeycloak - Wantok ERP / Keycloak 26 integration (v3.1)

This plugin is the OFBiz-side OIDC bridge for Wantok ERP. It is designed for Apache OFBiz 24.09 multitenancy and Keycloak 26.

## Two deliberately separate deployment modes

### Local Kubuntu development
- Keycloak: `http://localhost:8180`
- Wantok ERP: `http://localhost:8080`
- Tenant: `CLOUDCODE`
- Local ERP Keycloak client: `wantok-erp-web`
- `wankeycloak.localMode=Y`
- Tenant can be selected by `?tenantId=CLOUDCODE` / hidden `userTenantId` during local testing.

### Cloud/production
- `wankeycloak.localMode=N`
- OFBiz/FQDN tenant mapping resolves the tenant before wankeycloak trusts any browser query parameter.
- Create a **separate Keycloak client per tenant per application**.
- Production FQDN convention is **`<app>.<tenant>.<base-domain>`**. For CLOUDCODE, the tenant namespace is `cloudcode.wantoksys.com`, and application hosts sit beneath it such as `erp.cloudcode.wantoksys.com` and `pos.cloudcode.wantoksys.com`.
- This hostname order is an operational DNS/TLS/client-governance standard, not an OIDC protocol requirement. Keycloak SSO/SLO still depends on the configured client ID and exact redirect/post-logout URIs matching.
- Use the **application FQDN as the Keycloak client ID**, e.g.:
  - `erp.cloudcode.wantoksys.com` - Wantok ERP/OFBiz
  - `api.cloudcode.wantoksys.com` - Wantok API
  - `pos.cloudcode.wantoksys.com` - Oracle APEX POS
  - `portal.cloudcode.wantoksys.com` - public portal
  - `staff.cloudcode.wantoksys.com` - staff portal
  - `admin.cloudcode.wantoksys.com` - tenant admin app
- Future tenant example: `erp.nea.wantoksys.com`, `pos.nea.wantoksys.com`, `api.nea.wantoksys.com`, etc.
- Use exact redirect/logout URIs; do not use cross-tenant wildcards.

## Security rules implemented
- Explicit tenant delegator: `default#<TENANT_ID>` before user/role writes.
- OIDC Authorization Code Flow with server-side `state` + `nonce` validation.
- ID token RS256/JWKS signature, issuer, audience, expiry, and nonce checks.
- Required `tenant_id` (or compatibility `wantokTenantId`) claim must equal resolved OFBiz tenant.
- Keycloak organization scope is tenant-specific: `organization:<alias>`.
- Production tenant resolution prioritizes OFBiz host/FQDN-derived request attributes/delegator over query parameters.
- Tenant configuration stores a `clientSecretAlias`; the plugin resolves that exact environment variable first.

## Important limitation
`WanKeycloakApiFilter` is still a placeholder. Do not treat it as production API bearer-token protection until access-token validation, audience enforcement, tenant claim checks, and failure responses are completed and tested.

## Scripts
- `scripts/kcadm-setup-local.sh` - localhost-only client/role bootstrap.
- `scripts/kcadm-create-production-tenant-clients.sh` - production template that creates FQDN client IDs per tenant/application.

Compile and test against the exact Wantok ERP/OFBiz branch before production deployment.

## OFBiz release24.09 compile compatibility fix (v3.1.1)

For Apache OFBiz release24.09, plugin dependencies must be exposed through the
`pluginLibsCompile` configuration because the root OFBiz `compileJava` task consumes
that configuration from plugin subprojects. The browser event/filter imports use
`javax.servlet.*`, matching the Tomcat 9 / Servlet 4 stack used by OFBiz release24.09.
The wankeycloak web.xml likewise uses the Java EE Servlet 4.0 deployment descriptor.

## OFBiz 24.09 compile integration note (v3.1.2)

The plugin `build.gradle` intentionally does **not** apply Gradle's standalone
`java` plugin. Apache OFBiz 24.09 discovers active component Java sources and
compiles them through the root `:compileJava` task. The plugin declares only
its additional Nimbus JOSE/JWT dependency through OFBiz's `pluginLibsCompile`
configuration.

Do not add `plugins { id 'java' }` or a plugin-local `implementation` block to
this component. Doing so creates an independent
`:plugins:wankeycloak:compileJava` task that cannot see the normal OFBiz
framework/application classpath.

For a quiet verification build that suppresses the existing Apache OFBiz
`-Xlint` warnings and Gradle warning summary, run from the OFBiz framework root:

```bash
./gradlew --stop
./gradlew -PXlint:none --warning-mode none compileJava
```

For diagnostic builds, omit those suppression switches:

```bash
./gradlew compileJava
```

The quiet flags suppress existing upstream OFBiz compiler warnings; they do
not change the underlying Apache OFBiz source code.


## v3.1.3 OFBiz 24.09 runtime/schema cleanup

This maintenance revision keeps all v3.1.2 authentication, authorization, tenant and FQDN behavior unchanged and only corrects two OFBiz 24.09 integration details:

1. `ofbiz-component.xml` now declares both `entity-resource` elements before `service-resource`, matching the OFBiz component XSD sequence and eliminating the component XML validation error.
2. `WanKeycloakSessionAudit.message` was renamed to `auditMessage` because `MESSAGE` is treated as a reserved database word by the OFBiz entity checker. The service input parameter remains named `message`; `WanKeycloakServices` maps it to the entity field `auditMessage`.

For an existing local database created by v3.1.2, rename the PostgreSQL column `message` to `audit_message` before starting OFBiz with this revision.
