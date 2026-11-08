#!/usr/bin/env bash
set -euo pipefail

# CLOUD/PRODUCTION TEMPLATE.
# Creates ONE Keycloak client PER TENANT PER APPLICATION.
# The client ID is the application's FQDN (without https://), using <app>.<tenant>.<base-domain>, so DNS/FQDN, app identity,
# redirect URIs, audit trail, and secret ownership all align.
# Review every URI before running against production.

KC_CONTAINER=${KC_CONTAINER:-keycloak-26}
KC_ADMIN=${KC_ADMIN:-kcadmin}
KC_ADMIN_PASSWORD=${KC_ADMIN_PASSWORD:?Set KC_ADMIN_PASSWORD}
REALM=${REALM:-wantok-saas}
TENANT_ALIAS=${TENANT_ALIAS:?Example: cloudcode}
TENANT_ID=${TENANT_ID:?Example: CLOUDCODE}
BASE_DOMAIN=${BASE_DOMAIN:-wantoksys.com}

ERP_FQDN=${ERP_FQDN:-erp.${TENANT_ALIAS}.${BASE_DOMAIN}}
API_FQDN=${API_FQDN:-api.${TENANT_ALIAS}.${BASE_DOMAIN}}
POS_FQDN=${POS_FQDN:-pos.${TENANT_ALIAS}.${BASE_DOMAIN}}
PORTAL_FQDN=${PORTAL_FQDN:-portal.${TENANT_ALIAS}.${BASE_DOMAIN}}
STAFF_FQDN=${STAFF_FQDN:-staff.${TENANT_ALIAS}.${BASE_DOMAIN}}
ADMIN_FQDN=${ADMIN_FQDN:-admin.${TENANT_ALIAS}.${BASE_DOMAIN}}

kcadm() { docker exec "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh "$@"; }
kcadm config credentials --server http://localhost:8080 --realm master --user "$KC_ADMIN" --password "$KC_ADMIN_PASSWORD"

create_client_if_missing() {
  local client_id="$1"; shift
  if kcadm get clients -r "$REALM" -q clientId="$client_id" | grep -q '"clientId"'; then
    echo "Client already exists: $client_id"
  else
    echo "Creating client: $client_id"
    kcadm create clients -r "$REALM" -s clientId="$client_id" "$@"
  fi
}

# ERP/OFBiz tenant client: client ID is ERP FQDN.
create_client_if_missing "$ERP_FQDN" \
  -s enabled=true -s protocol=openid-connect -s publicClient=false \
  -s standardFlowEnabled=true -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=false \
  -s "rootUrl=https://${ERP_FQDN}" \
  -s "baseUrl=https://${ERP_FQDN}/partymgr/control/main" \
  -s "redirectUris=[\"https://${ERP_FQDN}/wankeycloak/control/oidcCallback\"]" \
  -s "webOrigins=[\"https://${ERP_FQDN}\"]" \
  -s "attributes.\"post.logout.redirect.uris\"=\"https://${ERP_FQDN}/wankeycloak/control/loggedOut\""

# API resource client: no browser redirects required.
create_client_if_missing "$API_FQDN" \
  -s enabled=true -s protocol=openid-connect -s publicClient=false \
  -s standardFlowEnabled=false -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=true

# Oracle APEX clients. Exact callback values should be confirmed from each APEX application.
for fqdn in "$POS_FQDN" "$PORTAL_FQDN" "$STAFF_FQDN"; do
  create_client_if_missing "$fqdn" \
    -s enabled=true -s protocol=openid-connect -s publicClient=false \
    -s standardFlowEnabled=true -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=false \
    -s "rootUrl=https://${fqdn}" \
    -s "redirectUris=[\"https://${fqdn}/ords/apex_authentication.callback\",\"https://${fqdn}/ords/apex_authentication.callback2\"]" \
    -s "webOrigins=[\"https://${fqdn}\"]"
done

create_client_if_missing "$ADMIN_FQDN" \
  -s enabled=true -s protocol=openid-connect -s publicClient=false \
  -s standardFlowEnabled=true -s directAccessGrantsEnabled=false -s serviceAccountsEnabled=false \
  -s "rootUrl=https://${ADMIN_FQDN}" -s "webOrigins=[\"https://${ADMIN_FQDN}\"]"

echo "Created/verified production tenant clients for $TENANT_ID ($TENANT_ALIAS):"
printf '  %s\n' "$ERP_FQDN" "$API_FQDN" "$POS_FQDN" "$PORTAL_FQDN" "$STAFF_FQDN" "$ADMIN_FQDN"
echo "IMPORTANT: verify APEX callback URLs and configure tenant-specific secrets before go-live."
