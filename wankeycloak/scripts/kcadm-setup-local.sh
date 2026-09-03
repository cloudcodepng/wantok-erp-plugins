#!/usr/bin/env bash
set -euo pipefail

# LOCALHOST-ONLY Keycloak 26 bootstrap for Wantok ERP development.
# Production intentionally uses separate per-tenant/per-application clients whose client IDs are the FQDNs.

KC_CONTAINER=${KC_CONTAINER:-keycloak-26}
KC_ADMIN=${KC_ADMIN:-kcadmin}
KC_ADMIN_PASSWORD=${KC_ADMIN_PASSWORD:?Set KC_ADMIN_PASSWORD}
REALM=${REALM:-wantok-saas}

kcadm() {
  docker exec "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"
}

kcadm config credentials --server http://localhost:8080 --realm master --user "$KC_ADMIN" --password "$KC_ADMIN_PASSWORD"

kcadm get realms/$REALM >/dev/null 2>&1 || \
  kcadm create realms -s realm=$REALM -s enabled=true -s sslRequired=none

for r in cloudcode-platform-admin cloudcode-tenant-admin tenant-admin erp-admin finance-user accounting-ap accounting-ar pos-cashier pos-supervisor inventory-user readonly-user; do
  kcadm create roles -r "$REALM" -s name="$r" >/dev/null 2>&1 || true
done

create_client_if_missing() {
  local client_id="$1"; shift
  if kcadm get clients -r "$REALM" -q clientId="$client_id" | grep -q '"clientId"'; then
    echo "Client already exists: $client_id"
  else
    kcadm create clients -r "$REALM" -s clientId="$client_id" "$@"
  fi
}

# Wantok ERP/OFBiz web login client used by wankeycloak locally.
create_client_if_missing wantok-erp-web \
  -s enabled=true -s protocol=openid-connect -s publicClient=false \
  -s standardFlowEnabled=true -s directAccessGrantsEnabled=false \
  -s serviceAccountsEnabled=false \
  -s 'redirectUris=["http://localhost:8080/wankeycloak/control/oidcCallback"]' \
  -s 'webOrigins=["http://localhost:8080"]' \
  -s 'attributes."post.logout.redirect.uris"="http://localhost:8080/wankeycloak/control/loggedOut*"'

# Local API audience/resource client. Browser redirect is not required.
create_client_if_missing wantok-api \
  -s enabled=true -s protocol=openid-connect -s publicClient=false \
  -s standardFlowEnabled=false -s directAccessGrantsEnabled=false \
  -s serviceAccountsEnabled=true

# Local Oracle APEX clients. Replace the redirect URIs with the exact URL returned by
# APEX_AUTHENTICATION.GET_CALLBACK_URL for your local ORDS/APEX environment before testing.
for cid in wantok-apex-pos wantok-apex-public-portal wantok-apex-staff-portal; do
  create_client_if_missing "$cid" \
    -s enabled=true -s protocol=openid-connect -s publicClient=false \
    -s standardFlowEnabled=true -s directAccessGrantsEnabled=false \
    -s serviceAccountsEnabled=false
 done

create_client_if_missing wantok-admin-console \
  -s enabled=true -s protocol=openid-connect -s publicClient=false \
  -s standardFlowEnabled=true -s directAccessGrantsEnabled=false \
  -s serviceAccountsEnabled=false

echo
printf '%s\n' "LOCAL bootstrap complete." \
  "1) Enable Organizations in realm $REALM." \
  "2) Create organization alias cloudcode and add tenant_id=CLOUDCODE." \
  "3) Add user attribute tenant_id=CLOUDCODE and token mapper." \
  "4) Use organization:cloudcode scope for CLOUDCODE application logins." \
  "5) Do NOT copy localhost clients to production unchanged."
