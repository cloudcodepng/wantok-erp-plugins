# Setup/admin cURL examples

Assumes:

```bash
BASE='http://localhost:8080'
TOKEN='REPLACE_WITH_TOKEN'
```

All examples use the same `inParams` convention already proven by the Wantok ERP REST plugin.

## Company

```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpAdminCreateCompany" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"partyId":"NEA","groupName":"National Energy Authority","preferredCurrencyUomId":"PGK"}' | jq
```

## Catalog

```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpAdminCreateCatalog" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"prodCatalogId":"NEA_ONLINE_CATALOG","catalogName":"NEA Online Services Catalog"}' | jq
```

## Root category

```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpAdminCreateCategory" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"productCategoryId":"NEA_CAT_ONLINE_ROOT","categoryName":"NEA Online Services","productCategoryTypeId":"CATALOG_CATEGORY"}' | jq
```

## Link category as catalog browse root

```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpAdminLinkCatalogCategory" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"prodCatalogId":"NEA_ONLINE_CATALOG","productCategoryId":"NEA_CAT_ONLINE_ROOT","prodCatalogCategoryTypeId":"PCCT_BROWSE_ROOT"}' | jq
```

## Create a child category

```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpAdminCreateCategory" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"productCategoryId":"NEA_CAT_PERMITS","categoryName":"NEA Permits","productCategoryTypeId":"CATALOG_CATEGORY"}' | jq
```

## Link child category beneath root

```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpAdminLinkCategoryChild" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"parentProductCategoryId":"NEA_CAT_ONLINE_ROOT","productCategoryId":"NEA_CAT_PERMITS"}' | jq
```

## Facility

```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpAdminCreateFacility" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"facilityId":"NEA_HQ_WAREHOUSE","facilityName":"NEA Headquarters Warehouse","ownerPartyId":"NEA","facilityTypeId":"WAREHOUSE"}' | jq
```

## ProductStore

```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpAdminCreateProductStore" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"productStoreId":"NEA_ONLINE_STORE","storeName":"NEA Online Store","payToPartyId":"NEA","defaultCurrencyUomId":"PGK","inventoryFacilityId":"NEA_HQ_WAREHOUSE"}' | jq
```

## Link store to catalog

```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpAdminLinkStoreCatalog" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"productStoreId":"NEA_ONLINE_STORE","prodCatalogId":"NEA_ONLINE_CATALOG"}' | jq
```

## Product + barcode/SKU

```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpAdminCreateProduct" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"productId":"NEA-TEST-001","internalName":"NEA API Test Item","productName":"NEA API Test Item","description":"NEA / KumulPay integration demo product","productTypeId":"FINISHED_GOOD","requireInventory":"Y","barcode":"NEA0001","goodIdentificationTypeId":"SKU"}' | jq
```

## Link product to category

```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpAdminLinkProductCategory" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"productId":"NEA-TEST-001","productCategoryId":"NEA_CAT_ONLINE_ROOT"}' | jq
```

Or place the product in the child category:

```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpAdminLinkProductCategory" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"productId":"NEA-TEST-001","productCategoryId":"NEA_CAT_PERMITS"}' | jq
```

## Product price

```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpAdminSetProductPrice" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"productId":"NEA-TEST-001","price":10.00,"currencyUomId":"PGK","productPriceTypeId":"DEFAULT_PRICE","productPricePurposeId":"PURCHASE","productStoreGroupId":"_NA_"}' | jq
```

## Opening inventory

First-time/non-destructive call:

```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpAdminSetOpeningInventory" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"productId":"NEA-TEST-001","facilityId":"NEA_HQ_WAREHOUSE","ownerPartyId":"NEA","quantity":10,"resetExistingQuantity":false}' | jq
```

Deliberately reset total stock to exactly 10:

```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpAdminSetOpeningInventory" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"productId":"NEA-TEST-001","facilityId":"NEA_HQ_WAREHOUSE","ownerPartyId":"NEA","quantity":10,"resetExistingQuantity":true}' | jq
```

## One-call demo bootstrap

```bash
curl -sS -X POST -G "$BASE/rest/services/wanerpAdminBootstrapNeaDemo" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"resetExistingInventory":false}' | jq
```


## Admin/test: create an end-user customer

This is only a manual/admin test alias. The end-user browser should use the future Spring Boot facade, which calls `wanerpApiRegisterEndUserCustomer`.

```bash
curl -sS -X POST -G \
  "$BASE/rest/services/wanerpAdminRegisterEndUserCustomer" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"firstName":"Meri","lastName":"Demo","emailAddress":"meri.demo@example.com","preferredCurrencyUomId":"PGK","externalId":"keycloak-sub-nea-demo-001","idempotencyKey":"enduser-register-keycloak-sub-nea-demo-001"}' | jq
```
