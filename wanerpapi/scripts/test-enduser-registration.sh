#!/usr/bin/env bash
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
OFBIZ_USER="${OFBIZ_USER:-admin}"
OFBIZ_PASS="${OFBIZ_PASS:-ofbiz}"
REG_ID="${REG_ID:-nea-enduser-runtime-test-001}"
EMAIL="${EMAIL:-nea.enduser.runtime.test@example.com}"

TOKEN="$(
  curl -fsS -X POST "$BASE/rest/auth/token" \
    -u "$OFBIZ_USER:$OFBIZ_PASS" \
    -H 'Accept: application/json' \
  | jq -r '.data.access_token'
)"

if [[ -z "$TOKEN" || "$TOKEN" == "null" ]]; then
  echo 'Unable to obtain OFBiz REST token.' >&2
  exit 1
fi

PARAMS="$(jq -nc \
  --arg firstName 'Meri' \
  --arg lastName 'Demo' \
  --arg email "$EMAIL" \
  --arg externalId "$REG_ID" \
  --arg idem "enduser-register-$REG_ID" \
  '{firstName:$firstName,lastName:$lastName,emailAddress:$email,preferredCurrencyUomId:"PGK",externalId:$externalId,idempotencyKey:$idem,countryCode:"675",contactNumber:"71234567",address1:"Section 1 Lot 1",city:"Port Moresby",postalCode:"111",countryGeoId:"PNG"}')"

register() {
  curl -fsS -X POST -G \
    "$BASE/rest/services/wanerpApiRegisterEndUserCustomer" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Accept: application/json' \
    --data-urlencode "inParams=$PARAMS"
}

echo '===== first registration ====='
FIRST="$(register)"
echo "$FIRST" | jq
PARTY1="$(echo "$FIRST" | jq -r '.data.partyIdOut // .partyIdOut // empty')"
[[ -n "$PARTY1" ]] || { echo 'First registration did not return partyIdOut.' >&2; exit 1; }

echo '===== retry same registration ====='
SECOND="$(register)"
echo "$SECOND" | jq
PARTY2="$(echo "$SECOND" | jq -r '.data.partyIdOut // .partyIdOut // empty')"
[[ "$PARTY1" == "$PARTY2" ]] || { echo "Idempotency failure: $PARTY1 != $PARTY2" >&2; exit 1; }

echo '===== read customer back ====='
PARTY="$(curl -fsS -G \
  "$BASE/rest/services/wanerpApiGetParty" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Accept: application/json' \
  --data-urlencode "inParams={\"partyId\":\"$PARTY1\"}")"
echo "$PARTY" | jq

echo "ENDUSER_REGISTRATION_RUNTIME_TEST=PASS partyId=$PARTY1"
