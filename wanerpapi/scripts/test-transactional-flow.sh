#!/usr/bin/env bash
set -euo pipefail

BASE="${BASE:-http://cloudcode.localhost:8080}"
OFBIZ_USER="${OFBIZ_USER:-admin}"
OFBIZ_PASS="${OFBIZ_PASS:-ofbiz}"

CUSTOMER_ID="${CUSTOMER_ID:-10012}"
STORE_ID="${STORE_ID:-NEA_ONLINE_STORE}"
CATALOG_ID="${CATALOG_ID:-NEA_ONLINE_CATALOG}"
FACILITY_ID="${FACILITY_ID:-NEA_HQ_WAREHOUSE}"
PRODUCT_ID="${PRODUCT_ID:-NEA-TEST-001}"
QUANTITY="${QUANTITY:-1}"

STAMP="$(date +%m%d%H%M%S)"
ORDER_KEY="${ORDER_KEY:-ORD${STAMP}}"
KUMULPAY_REF="${KUMULPAY_REF:-KP${STAMP}}"

for cmd in curl jq; do
  command -v "$cmd" >/dev/null 2>&1 || {
    echo "Required command missing: $cmd" >&2
    exit 1
  }
done

echo "BASE=$BASE"
echo "CUSTOMER_ID=$CUSTOMER_ID"
echo "ORDER_KEY=$ORDER_KEY"
echo "KUMULPAY_REF=$KUMULPAY_REF"

TOKEN_RESPONSE="$(
  curl -sS -X POST "$BASE/rest/auth/token" \
    -u "$OFBIZ_USER:$OFBIZ_PASS" \
    -H 'Accept: application/json'
)"
TOKEN="$(jq -r '.data.access_token // empty' <<<"$TOKEN_RESPONSE")"
[[ -n "$TOKEN" ]] || {
  echo "Could not obtain OFBiz token." >&2
  echo "$TOKEN_RESPONSE" | jq . >&2 || true
  exit 1
}

post_json() {
  local service="$1"
  local body="$2"
  curl -sS -X POST "$BASE/rest/services/$service" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Accept: application/json' \
    -H 'Content-Type: application/json' \
    --data-raw "$body"
}

get_json() {
  local service="$1"
  local params="$2"
  curl -sS -G "$BASE/rest/services/$service" \
    -H "Authorization: Bearer $TOKEN" \
    -H 'Accept: application/json' \
    --data-urlencode "inParams=$params"
}

require_200() {
  local name="$1"
  local json="$2"
  if ! jq -e '.statusCode == 200' >/dev/null 2>&1 <<<"$json"; then
    echo "ERROR: $name failed." >&2
    echo "$json" | jq . >&2 || printf '%s\n' "$json" >&2
    exit 1
  fi
}

echo
echo "===== 1. Verify customer ====="
R="$(get_json wanerpApiGetParty "$(jq -cn --arg partyId "$CUSTOMER_ID" '{partyId:$partyId}')")"
echo "$R" | jq .
require_200 "wanerpApiGetParty" "$R"

echo
echo "===== 2. Verify product/store price ====="
R="$(get_json wanerpApiGetPrice "$(jq -cn \
  --arg productId "$PRODUCT_ID" \
  --arg productStoreId "$STORE_ID" \
  --arg prodCatalogId "$CATALOG_ID" \
  --argjson quantity "$QUANTITY" \
  '{productId:$productId,productStoreId:$productStoreId,prodCatalogId:$prodCatalogId,quantity:$quantity,currencyUomId:"PGK"}')")"
echo "$R" | jq .
require_200 "wanerpApiGetPrice" "$R"

echo
echo "===== 3. Create sales order BEFORE KumulPay redirect ====="
ORDER_BODY="$(jq -cn \
  --arg partyId "$CUSTOMER_ID" \
  --arg productStoreId "$STORE_ID" \
  --arg prodCatalogId "$CATALOG_ID" \
  --arg facilityId "$FACILITY_ID" \
  --arg productId "$PRODUCT_ID" \
  --arg idempotencyKey "$ORDER_KEY" \
  --argjson quantity "$QUANTITY" \
  '{
    partyId:$partyId,
    productStoreId:$productStoreId,
    prodCatalogId:$prodCatalogId,
    facilityId:$facilityId,
    productId:$productId,
    quantity:$quantity,
    currencyUomId:"PGK",
    idempotencyKey:$idempotencyKey
  }')"

ORDER_RESPONSE="$(post_json wanerpApiCreateSalesOrder "$ORDER_BODY")"
echo "$ORDER_RESPONSE" | jq .
require_200 "wanerpApiCreateSalesOrder" "$ORDER_RESPONSE"

ORDER_ID="$(jq -r '.data.orderId // empty' <<<"$ORDER_RESPONSE")"
GRAND_TOTAL="$(jq -r '.data.grandTotal // empty' <<<"$ORDER_RESPONSE")"
[[ -n "$ORDER_ID" && -n "$GRAND_TOTAL" ]] || {
  echo "ERROR: orderId/grandTotal missing." >&2
  exit 1
}

echo "ORDER_ID=$ORDER_ID"
echo "GRAND_TOTAL=$GRAND_TOTAL"

echo
echo "===== 4. Create invoice BEFORE KumulPay redirect ====="
INVOICE_BODY="$(jq -cn --arg orderId "$ORDER_ID" '{orderId:$orderId}')"
INVOICE_RESPONSE="$(post_json wanerpApiCreateInvoiceForOrder "$INVOICE_BODY")"
echo "$INVOICE_RESPONSE" | jq .
require_200 "wanerpApiCreateInvoiceForOrder" "$INVOICE_RESPONSE"

INVOICE_ID="$(jq -r '.data.invoiceId // empty' <<<"$INVOICE_RESPONSE")"
[[ -n "$INVOICE_ID" ]] || {
  echo "ERROR: invoiceId missing." >&2
  exit 1
}
echo "INVOICE_ID=$INVOICE_ID"

echo
echo "===== 5. Read order before payment ====="
R="$(get_json wanerpApiGetOrder "$(jq -cn --arg orderId "$ORDER_ID" '{orderId:$orderId}')")"
echo "$R" | jq .
require_200 "wanerpApiGetOrder pre-payment" "$R"

echo
echo "----------------------------------------------------------------"
echo "SIMULATED KUMULPAY SUCCESS"
echo "In production, stop here, redirect to KumulPay, and continue only"
echo "after the Spring facade has VERIFIED the KumulPay success response."
echo "KumulPay reference used for this local test: $KUMULPAY_REF"
echo "----------------------------------------------------------------"

echo
echo "===== 6. Create received payment AFTER verified KumulPay success ====="
PAYMENT_BODY="$(jq -cn \
  --arg partyIdFrom "$CUSTOMER_ID" \
  --arg partyIdTo "NEA" \
  --arg paymentRefNum "$KUMULPAY_REF" \
  --arg idempotencyKey "$KUMULPAY_REF" \
  --arg comments "Local KumulPay success simulation for order $ORDER_ID" \
  --argjson amount "$GRAND_TOTAL" \
  '{
    partyIdFrom:$partyIdFrom,
    partyIdTo:$partyIdTo,
    amount:$amount,
    currencyUomId:"PGK",
    paymentTypeId:"CUSTOMER_PAYMENT",
    paymentMethodTypeId:"EXT_OFFLINE",
    statusId:"PMNT_RECEIVED",
    paymentRefNum:$paymentRefNum,
    idempotencyKey:$idempotencyKey,
    comments:$comments
  }')"

PAYMENT_RESPONSE="$(post_json wanerpApiCreatePayment "$PAYMENT_BODY")"
echo "$PAYMENT_RESPONSE" | jq .
require_200 "wanerpApiCreatePayment" "$PAYMENT_RESPONSE"

PAYMENT_ID="$(jq -r '.data.paymentId // empty' <<<"$PAYMENT_RESPONSE")"
[[ -n "$PAYMENT_ID" ]] || {
  echo "ERROR: paymentId missing." >&2
  exit 1
}
echo "PAYMENT_ID=$PAYMENT_ID"

echo
echo "===== 7. Apply payment to invoice ====="
APPLICATION_BODY="$(jq -cn \
  --arg paymentId "$PAYMENT_ID" \
  --arg invoiceId "$INVOICE_ID" \
  --argjson amountApplied "$GRAND_TOTAL" \
  '{paymentId:$paymentId,invoiceId:$invoiceId,amountApplied:$amountApplied}')"

APPLICATION_RESPONSE="$(post_json wanerpApiApplyPayment "$APPLICATION_BODY")"
echo "$APPLICATION_RESPONSE" | jq .
require_200 "wanerpApiApplyPayment" "$APPLICATION_RESPONSE"

PAYMENT_APPLICATION_ID="$(jq -r '.data.paymentApplicationId // empty' <<<"$APPLICATION_RESPONSE")"
echo "PAYMENT_APPLICATION_ID=$PAYMENT_APPLICATION_ID"

echo
echo "===== 8. Reconcile paid state ====="
PAID_BODY="$(jq -cn \
  --arg orderId "$ORDER_ID" \
  --arg invoiceId "$INVOICE_ID" \
  --arg paymentId "$PAYMENT_ID" \
  '{orderId:$orderId,invoiceId:$invoiceId,paymentId:$paymentId}')"

PAID_RESPONSE="$(post_json wanerpApiMarkOrderPaid "$PAID_BODY")"
echo "$PAID_RESPONSE" | jq .
require_200 "wanerpApiMarkOrderPaid" "$PAID_RESPONSE"

echo
echo "===== 9. Final order read-back ====="
FINAL_RESPONSE="$(get_json wanerpApiGetOrder "$(jq -cn --arg orderId "$ORDER_ID" '{orderId:$orderId}')")"
echo "$FINAL_RESPONSE" | jq .
require_200 "wanerpApiGetOrder final" "$FINAL_RESPONSE"

if ! jq -e '.data.paid == true' >/dev/null 2>&1 <<<"$FINAL_RESPONSE"; then
  echo "ERROR: final order read-back does not report paid=true." >&2
  exit 1
fi

echo
echo "===== 10. Idempotency replay checks ====="

ORDER_REPLAY="$(post_json wanerpApiCreateSalesOrder "$ORDER_BODY")"
echo "$ORDER_REPLAY" | jq .
require_200 "wanerpApiCreateSalesOrder replay" "$ORDER_REPLAY"

PAYMENT_REPLAY="$(post_json wanerpApiCreatePayment "$PAYMENT_BODY")"
echo "$PAYMENT_REPLAY" | jq .
require_200 "wanerpApiCreatePayment replay" "$PAYMENT_REPLAY"

APPLICATION_REPLAY="$(post_json wanerpApiApplyPayment "$APPLICATION_BODY")"
echo "$APPLICATION_REPLAY" | jq .
require_200 "wanerpApiApplyPayment replay" "$APPLICATION_REPLAY"

echo
echo "PASS: transactional flow completed."
echo "ORDER_ID=$ORDER_ID"
echo "INVOICE_ID=$INVOICE_ID"
echo "PAYMENT_ID=$PAYMENT_ID"
echo "PAYMENT_APPLICATION_ID=$PAYMENT_APPLICATION_ID"
echo "GRAND_TOTAL=$GRAND_TOTAL"
echo "KUMULPAY_REF=$KUMULPAY_REF"
echo
echo "UI:"
echo "$BASE/ordermgr/control/orderview?orderId=$ORDER_ID"
echo "$BASE/accounting/control/invoiceOverview?invoiceId=$INVOICE_ID"
echo "$BASE/accounting/control/paymentOverview?paymentId=$PAYMENT_ID"
