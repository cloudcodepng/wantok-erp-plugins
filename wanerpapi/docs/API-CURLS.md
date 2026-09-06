# All 17 operational API cURL examples

```bash
TOKEN=$(
  curl -sS -X POST \
    'http://localhost:8080/rest/auth/token' \
    -u 'admin:ofbiz' \
    -H 'Accept: application/json' \
  | jq -r '.data.access_token'
)
```

```bash
BASE='http://localhost:8080'
TOKEN='REPLACE_WITH_TOKEN'
CUSTOMER='REPLACE_WITH_CUSTOMER_PARTY_ID'
```

## 1. wanerpApiHealth
```bash
curl -sS -G "$BASE/rest/services/wanerpApiHealth" -H "Authorization: Bearer $TOKEN" --data-urlencode 'inParams={}' | jq
```

## 2. wanerpApiGetProduct
```bash
curl -sS -G "$BASE/rest/services/wanerpApiGetProduct" -H "Authorization: Bearer $TOKEN" --data-urlencode 'inParams={"idToFind":"NEA-TEST-001"}' | jq
```

## 3. wanerpApiSearchProducts
```bash
curl -sS -G "$BASE/rest/services/wanerpApiSearchProducts" -H "Authorization: Bearer $TOKEN" --data-urlencode 'inParams={"q":"National Energy Authority","limit":50}' | jq
```

## 4. wanerpApiGetProductsByProductStore
```bash
curl -sS -G "$BASE/rest/services/wanerpApiGetProductsByProductStore" -H "Authorization: Bearer $TOKEN" --data-urlencode 'inParams={"productStoreId":"NEA_ONLINE_STORE"}' | jq
```

## 5. wanerpApiGetProductStore
```bash
curl -sS -G "$BASE/rest/services/wanerpApiGetProductStore" -H "Authorization: Bearer $TOKEN" --data-urlencode 'inParams={"productStoreId":"NEA_ONLINE_STORE"}' | jq
```

## 6. wanerpApiGetFacility
```bash
curl -sS -G "$BASE/rest/services/wanerpApiGetFacility" -H "Authorization: Bearer $TOKEN" --data-urlencode 'inParams={"facilityId":"NEA_HQ_WAREHOUSE"}' | jq
```

## 7. wanerpApiGetInventory
```bash
curl -sS -G "$BASE/rest/services/wanerpApiGetInventory" -H "Authorization: Bearer $TOKEN" --data-urlencode 'inParams={"productId":"NEA-TEST-001","facilityId":"NEA_HQ_WAREHOUSE"}' | jq
```

## 8. wanerpApiGetPrice
```bash
curl -sS -G "$BASE/rest/services/wanerpApiGetPrice" -H "Authorization: Bearer $TOKEN" --data-urlencode 'inParams={"productId":"NEA-TEST-001","productStoreId":"NEA_ONLINE_STORE","prodCatalogId":"NEA_ONLINE_CATALOG","quantity":1,"currencyUomId":"PGK"}' | jq
```

## 9. wanerpApiGetParty
```bash
curl -sS -G "$BASE/rest/services/wanerpApiGetParty" -H "Authorization: Bearer $TOKEN" --data-urlencode 'inParams={"partyId":"NEA"}' | jq
```

## 10. wanerpApiRegisterEndUserCustomer (preferred)
```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpApiRegisterEndUserCustomer" -H "Authorization: Bearer $TOKEN" --data-urlencode 'inParams={"firstName":"Meri","lastName":"Demo","emailAddress":"meri.demo@example.com","preferredCurrencyUomId":"PGK","externalId":"keycloak-sub-nea-demo-001","idempotencyKey":"enduser-register-keycloak-sub-nea-demo-001"}' | jq
```

## 11. wanerpApiGetOrder
```bash
ORDER_ID='REPLACE_WITH_ORDER_ID'
curl -sS -G "$BASE/rest/services/wanerpApiGetOrder" -H "Authorization: Bearer $TOKEN" --data-urlencode "inParams={\"orderId\":\"$ORDER_ID\"}" | jq
```

## 12. wanerpApiCreateSalesOrder
```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpApiCreateSalesOrder" -H "Authorization: Bearer $TOKEN" --data-urlencode "inParams={\"partyId\":\"$CUSTOMER\",\"productStoreId\":\"NEA_ONLINE_STORE\",\"facilityId\":\"NEA_HQ_WAREHOUSE\",\"productId\":\"NEA-TEST-001\",\"quantity\":1,\"currencyUomId\":\"PGK\",\"prodCatalogId\":\"NEA_ONLINE_CATALOG\",\"idempotencyKey\":\"NEA-ORDER-000001\"}" | jq
```

## 13. wanerpApiCreateInvoiceForOrder
```bash
ORDER_ID='REPLACE_WITH_ORDER_ID'
curl -sS -X POST -G "$BASE/rest/services/wanerpApiCreateInvoiceForOrder" -H "Authorization: Bearer $TOKEN" --data-urlencode "inParams={\"orderId\":\"$ORDER_ID\"}" | jq
```

## 14. wanerpApiCreatePayment
```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpApiCreatePayment" -H "Authorization: Bearer $TOKEN" --data-urlencode "inParams={\"partyIdFrom\":\"$CUSTOMER\",\"partyIdTo\":\"NEA\",\"amount\":10.00,\"currencyUomId\":\"PGK\",\"paymentMethodTypeId\":\"EXT_OFFLINE\",\"paymentTypeId\":\"CUSTOMER_PAYMENT\",\"statusId\":\"PMNT_RECEIVED\",\"paymentRefNum\":\"KP-TXN-NEA-000001\",\"idempotencyKey\":\"KP-TXN-NEA-000001\"}" | jq
```

## 15. wanerpApiApplyPayment
```bash
PAYMENT_ID='REPLACE_WITH_PAYMENT_ID'
INVOICE_ID='REPLACE_WITH_INVOICE_ID'
curl -sS -X POST -G "$BASE/rest/services/wanerpApiApplyPayment" -H "Authorization: Bearer $TOKEN" --data-urlencode "inParams={\"paymentId\":\"$PAYMENT_ID\",\"invoiceId\":\"$INVOICE_ID\",\"amountApplied\":10.00}" | jq
```

## 16. wanerpApiMarkOrderPaid
```bash
ORDER_ID='REPLACE_WITH_ORDER_ID'
PAYMENT_ID='REPLACE_WITH_PAYMENT_ID'
INVOICE_ID='REPLACE_WITH_INVOICE_ID'
curl -sS -X POST -G "$BASE/rest/services/wanerpApiMarkOrderPaid" -H "Authorization: Bearer $TOKEN" --data-urlencode "inParams={\"orderId\":\"$ORDER_ID\",\"paymentId\":\"$PAYMENT_ID\",\"invoiceId\":\"$INVOICE_ID\"}" | jq
```

## 17. wanerpApiCompletePaidSale
```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpApiCompletePaidSale" -H "Authorization: Bearer $TOKEN" --data-urlencode "inParams={\"partyId\":\"$CUSTOMER\",\"productStoreId\":\"NEA_ONLINE_STORE\",\"facilityId\":\"NEA_HQ_WAREHOUSE\",\"productId\":\"NEA-TEST-001\",\"quantity\":1,\"currencyUomId\":\"PGK\",\"prodCatalogId\":\"NEA_ONLINE_CATALOG\",\"kumulPayReference\":\"KP-TXN-NEA-000002\",\"paymentMethodTypeId\":\"EXT_OFFLINE\",\"idempotencyKey\":\"NEA-PAID-SALE-000002\"}" | jq
```


### Backward-compatible alias

`wanerpApiCreateCustomer` remains available and invokes the same implementation. New end-user integration should use `wanerpApiRegisterEndUserCustomer`.

### Verify idempotency

Run the registration cURL twice. The second call should return the same `partyIdOut`, `created=false`, `duplicate=true`.
