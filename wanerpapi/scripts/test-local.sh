#!/usr/bin/env bash
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
OFBIZ_USER="${OFBIZ_USER:-admin}"
OFBIZ_PASS="${OFBIZ_PASS:-ofbiz}"

TOKEN="$(
  curl -fsS -X POST "$BASE/rest/auth/token" \
    -u "$OFBIZ_USER:$OFBIZ_PASS" \
    -H 'Accept: application/json' \
  | jq -r '.data.access_token'
)"

if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
  echo 'Could not obtain OFBiz REST token.' >&2
  exit 1
fi

get_api() {
  local service="$1" params="$2"
  echo
  echo "===== $service ====="
  curl -fsS -G "$BASE/rest/services/$service" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Accept: application/json' \
    --data-urlencode "inParams=$params" | jq
}

post_api() {
  local service="$1" params="$2"
  echo
  echo "===== $service ====="
  curl -fsS -X POST -G "$BASE/rest/services/$service" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Accept: application/json' \
    --data-urlencode "inParams=$params" | jq
}

get_api  wanerpApiHealth '{}'
get_api  wanerpApiGetParty '{"partyId":"NEA"}'
get_api  wanerpApiGetProduct '{"idToFind":"NEA-TEST-001"}'
get_api  wanerpApiGetProduct '{"idToFind":"NEA0001","goodIdentificationTypeId":"SKU"}'
get_api  wanerpApiSearchProducts '{"q":"NEA","limit":20}'
get_api  wanerpApiGetProductsByProductStore '{"productStoreId":"NEA_ONLINE_STORE"}'
get_api  wanerpApiGetProductStore '{"productStoreId":"NEA_ONLINE_STORE"}'
get_api  wanerpApiGetFacility '{"facilityId":"NEA_HQ_WAREHOUSE"}'
get_api  wanerpApiGetInventory '{"productId":"NEA-TEST-001","facilityId":"NEA_HQ_WAREHOUSE"}'
get_api  wanerpApiGetPrice '{"productId":"NEA-TEST-001","productStoreId":"NEA_ONLINE_STORE","prodCatalogId":"NEA_ONLINE_CATALOG","quantity":1,"currencyUomId":"PGK"}'

echo
echo 'NEA ext-demo read/API smoke suite completed.'
echo 'Next: create a customer, then run wanerpApiCreateSalesOrder or wanerpApiCompletePaidSale.'
echo 'If these records are missing, first run plugins/wanerpapi/scripts/load-nea-demo-data.sh.'

get_api wanerpApiGetProductsByProductStore '{"productStoreId":"NEA_POS_STORE"}'


echo
echo '===== end-user registration / idempotency ====='
REG='{"firstName":"Meri","lastName":"Demo","emailAddress":"meri.demo@example.com","preferredCurrencyUomId":"PGK","externalId":"keycloak-sub-nea-smoke-001","idempotencyKey":"enduser-register-keycloak-sub-nea-smoke-001"}'
post_api wanerpApiRegisterEndUserCustomer "$REG"
post_api wanerpApiRegisterEndUserCustomer "$REG"

echo
echo 'NEA read/setup + end-user registration smoke suite completed.'
