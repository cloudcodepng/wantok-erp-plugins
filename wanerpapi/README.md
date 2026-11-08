# wanerpapi — Wantok ERP REST Integration Plugin

**Target:** Apache OFBiz `release24.09` / Cloudcode Wantok ERP fork  
**Demo tenant:** `CLOUDCODE`  
**Demo customer/project:** PNG National Energy Authority (NEA)  
**Version:** `1.0.0-nea-demo`

`wanerpapi` is the dedicated Wantok ERP integration plugin. It deliberately keeps cross-application REST/integration logic out of `wanerppos` and exposes narrow authenticated service contracts over native OFBiz Service Engine / Entity Engine operations.

The later Spring Boot facade at `api.nea.wantoksys.com` should call these services; APEX and other external clients should not depend directly on raw OFBiz service/entity structures.

## 1. Main 17 API services

| # | Service | Verb | Purpose |
|---|---|---|---|
| 1 | `wanerpApiHealth` | GET | Plugin/delegator/tenant sanity check |
| 2 | `wanerpApiGetProduct` | GET | Product ID / SKU / barcode lookup |
| 3 | `wanerpApiSearchProducts` | GET | Product search |
| 4 | `wanerpApiGetProductsByProductStore` | GET | ProductStore -> Catalog -> Category tree -> Products |
| 5 | `wanerpApiGetProductStore` | GET | ProductStore + catalog/facility relationships |
| 6 | `wanerpApiGetFacility` | GET | Facility lookup |
| 7 | `wanerpApiGetInventory` | GET | QOH / ATP / accounting quantity |
| 8 | `wanerpApiGetPrice` | GET | Native OFBiz price calculation |
| 9 | `wanerpApiGetParty` | GET | Person/company Party lookup |
| 10 | `wanerpApiCreateCustomer` | POST | Backward-compatible customer creation |
| 10a | `wanerpApiRegisterEndUserCustomer` | POST | Preferred `enduser.nea.wantoksys.com` registration -> OFBiz CUSTOMER |
| 11 | `wanerpApiGetOrder` | GET | Order + items + statuses + invoices + payments |
| 12 | `wanerpApiCreateSalesOrder` | POST | Controlled idempotent sales order |
| 13 | `wanerpApiCreateInvoiceForOrder` | POST | Invoice all order items |
| 14 | `wanerpApiCreatePayment` | POST | Record KumulPay/external incoming payment |
| 15 | `wanerpApiApplyPayment` | POST | Apply payment to invoice |
| 16 | `wanerpApiMarkOrderPaid` | POST | Reconcile native payment/invoice paid state |
| 17 | `wanerpApiCompletePaidSale` | POST | Composite order -> invoice -> payment -> application -> paid flow |

## 2. Setup/admin services

The plugin also contains 13 authenticated administrative services for building the demo data through API calls instead of manually inserting database rows:

- `wanerpAdminCreateCompany`
- `wanerpAdminCreateCatalog`
- `wanerpAdminCreateCategory`
- `wanerpAdminLinkCatalogCategory`
- `wanerpAdminLinkCategoryChild`
- `wanerpAdminCreateProduct`
- `wanerpAdminLinkProductCategory`
- `wanerpAdminCreateFacility`
- `wanerpAdminCreateProductStore`
- `wanerpAdminLinkStoreCatalog`
- `wanerpAdminSetProductPrice`
- `wanerpAdminSetOpeningInventory`
- `wanerpAdminBootstrapNeaDemo`

These are **administrative/demo APIs**. Keep them inaccessible to untrusted callers in production and put the future Spring Boot facade/security boundary in front of OFBiz.

## 3. One-call NEA bootstrap (minimal fallback)

`wanerpAdminBootstrapNeaDemo` idempotently creates/reuses the immediate demo master data:

| Object | Value |
|---|---|
| Company / PartyGroup | `NEA` — National Energy Authority |
| Catalog | `NEA_ONLINE_CATALOG` |
| Browse-root category | `NEA_CAT_ONLINE_ROOT` |
| ProductStore | `NEA_ONLINE_STORE` |
| Inventory facility | `NEA_HQ_WAREHOUSE` |
| Product | `NEA-TEST-001` |
| SKU/barcode | `NEA0001` (`GoodIdentification` type `SKU`) |
| Default price | `PGK 10.00` |
| Opening inventory | `10` |

The bootstrap does **not** reset existing stock on reruns unless `resetExistingInventory=true`. This prevents an accidental API retry from restoring inventory after test sales.

`NEA_POS_01` is intentionally not fabricated here because a POS terminal is not a guaranteed core OFBiz entity. If your `wanerppos` plugin has a custom terminal entity/service, create `NEA_POS_01` there or add a small adapter after confirming that entity definition.

## 4. Installation into your source tree

From your framework root:

```bash
cd ~/Working/Projects/Cloudcode/GENERIC/source/wantok-erp/wantok-erp-framework

# The ZIP contains a top-level wanerpapi/ directory.
unzip /path/to/wanerpapi-ofbiz24.09-nea-demo.zip -d plugins/

find plugins/wanerpapi -maxdepth 6 -type f | sort
./gradlew processResources classes
```

Do **not** run `cleanAll`, `loadAll`, tenant bootstrap, or recreate PostgreSQL/Keycloak just to install this source plugin.

### Custom Wantok tenant-component registration

Your Wantok fork has a customized tenant-component resolver. If it requires explicit registration, add the plugin to `CLOUDCODE` the same way you registered `wankeycloak` and other tenant-aware components:

```sql
INSERT INTO tenant_component
  (tenant_id, component_name, sequence_num,
   created_stamp, created_tx_stamp, last_updated_stamp, last_updated_tx_stamp)
VALUES
  ('CLOUDCODE','wanerpapi',230,now(),now(),now(),now())
ON CONFLICT (component_name, tenant_id)
DO UPDATE SET sequence_num=EXCLUDED.sequence_num,
              last_updated_stamp=now(),
              last_updated_tx_stamp=now();
```

Then start Wantok ERP normally:

```bash
./gradlew ofbiz
```

or recreate **only** the `wantok-erp` container when testing the Docker image.

## 5. REST authentication

The plugin uses your already-working `plugins/rest-api` authentication flow.

```bash
BASE='http://localhost:8080'

TOKEN=$(
  curl -sS -X POST "$BASE/rest/auth/token" \
    -u 'admin:ofbiz' \
    -H 'Accept: application/json' \
  | jq -r '.data.access_token'
)
```

List services:

```bash
curl -sS "$BASE/rest/services" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Accept: application/json' \
| jq -r '.data[] | [.name,.link.type,.link.href] | @tsv' \
| grep -E 'wanerp(Api|Admin)'
```

`admin/ofbiz` is for controlled demo testing only; replace it with a least-privilege integration account later.

## 6. Bootstrap demo data

Your current REST plugin has already been proven with `inParams` URL/form encoding, so this is the safest test form:

```bash
curl -sS -X POST -G \
  "$BASE/rest/services/wanerpAdminBootstrapNeaDemo" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Accept: application/json' \
  --data-urlencode 'inParams={"resetExistingInventory":false}' \
| jq
```

To deliberately restore test stock to exactly 10:

```bash
curl -sS -X POST -G \
  "$BASE/rest/services/wanerpAdminBootstrapNeaDemo" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"resetExistingInventory":true}' \
| jq
```

## 7. Read-side demo curls

### Product by productId

```bash
curl -sS -G "$BASE/rest/services/wanerpApiGetProduct" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"idToFind":"NEA-TEST-001"}' | jq
```

### Product by SKU/barcode

```bash
curl -sS -G "$BASE/rest/services/wanerpApiGetProduct" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"idToFind":"NEA0001","goodIdentificationTypeId":"SKU"}' | jq
```

### All products available through ProductStore

```bash
curl -sS -G "$BASE/rest/services/wanerpApiGetProductsByProductStore" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"productStoreId":"NEA_ONLINE_STORE"}' | jq
```

### Inventory

```bash
curl -sS -G "$BASE/rest/services/wanerpApiGetInventory" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"productId":"NEA-TEST-001","facilityId":"NEA_HQ_WAREHOUSE"}' | jq
```

### Price

```bash
curl -sS -G "$BASE/rest/services/wanerpApiGetPrice" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={"productId":"NEA-TEST-001","productStoreId":"NEA_ONLINE_STORE","prodCatalogId":"NEA_ONLINE_CATALOG","quantity":1,"currencyUomId":"PGK"}' | jq
```

## 8. Register a test end-user customer

```bash
curl -sS -X POST -G \
  "$BASE/rest/services/wanerpApiRegisterEndUserCustomer" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode 'inParams={
    "firstName":"Meri",
    "lastName":"Demo",
    "emailAddress":"meri.demo@example.com",
    "preferredCurrencyUomId":"PGK",
    "externalId":"keycloak-sub-nea-demo-001",
    "idempotencyKey":"enduser-register-keycloak-sub-nea-demo-001"
  }' | jq
```

Save the returned `partyIdOut`. Repeat the same request to confirm idempotency: the same Party should be returned with `created=false` and `duplicate=true`.

## 9. Create an order

```bash
CUSTOMER='REPLACE_WITH_PARTY_ID'

curl -sS -X POST -G \
  "$BASE/rest/services/wanerpApiCreateSalesOrder" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode "inParams={
    \"partyId\":\"$CUSTOMER\",
    \"productStoreId\":\"NEA_ONLINE_STORE\",
    \"facilityId\":\"NEA_HQ_WAREHOUSE\",
    \"productId\":\"NEA-TEST-001\",
    \"quantity\":1,
    \"currencyUomId\":\"PGK\",
    \"prodCatalogId\":\"NEA_ONLINE_CATALOG\",
    \"idempotencyKey\":\"NEA-ORDER-000001\"
  }" | jq
```

Reuse the same idempotency key and the service returns the original order with `duplicate=true` instead of creating a second order.

## 10. Complete a paid sale after KumulPay success

This is the intended write API for the later Spring Boot facade **after** it has independently verified a successful KumulPay transaction/callback.

```bash
CUSTOMER='REPLACE_WITH_PARTY_ID'

curl -sS -X POST -G \
  "$BASE/rest/services/wanerpApiCompletePaidSale" \
  -H "Authorization: Bearer $TOKEN" \
  --data-urlencode "inParams={
    \"partyId\":\"$CUSTOMER\",
    \"productStoreId\":\"NEA_ONLINE_STORE\",
    \"facilityId\":\"NEA_HQ_WAREHOUSE\",
    \"productId\":\"NEA-TEST-001\",
    \"quantity\":1,
    \"currencyUomId\":\"PGK\",
    \"prodCatalogId\":\"NEA_ONLINE_CATALOG\",
    \"kumulPayReference\":\"KP-TXN-NEA-000001\",
    \"paymentMethodTypeId\":\"EXT_OFFLINE\",
    \"idempotencyKey\":\"NEA-PAID-SALE-000001\"
  }" | jq
```

The response contains:

- `orderId`
- `invoiceId`
- `paymentId`
- `paymentApplicationId`
- `grandTotal`
- `paid`
- `duplicate`

## 11. What “paid” means

The plugin does **not** invent an `ORDER_PAID` status.

The native OFBiz sequence is:

1. create the invoice with `createInvoiceForOrderAllItems`;
2. create the incoming payment with `createPayment`;
3. ensure payment status `PMNT_RECEIVED` using `setPaymentStatus` if required;
4. apply it with `createPaymentApplication`;
5. call `checkPaymentInvoices`;
6. report `paid=true` when the invoice is `INVOICE_PAID`.

This keeps invoice/payment accounting state native to OFBiz.

## 12. Accounting prerequisite

The master-data/read/order endpoints are useful before full accounting setup. The invoice/payment flow can require normal OFBiz accounting configuration for the ProductStore pay-to organization (`NEA`), such as accounting preferences and GL mappings.

The authoritative `ext-demo` seed now includes a **starter NEA demo chart of accounts**, `PartyAcctgPreference`, organization account assignments, invoice-item mappings, customer-payment mapping and an `EXT_OFFLINE` cash/bank mapping so the order/invoice/payment API lifecycle can be exercised. This is demo configuration only—not an approved statutory NEA chart of accounts. Replace or extend it with NEA Finance's real COA and posting rules before production use. If your customized OFBiz fork requires additional GL mappings, use the Accounting setup screens/service errors to identify the missing organization-specific mapping and add it to the seed before deployment.

## 13. Inventory semantics

Order creation uses OFBiz `ShoppingCart` / `CheckOutHelper`, so normal reservation behavior applies. A sale can therefore affect ATP through order reservation. The plugin does not fake shipment/issuance just to force QOH down. If your final NEA point-of-sale flow needs physical stock issuance/fulfilment immediately after payment, add the proper OFBiz shipment/issuance step after testing the exact WebPOS behaviour in your fork.

## 14. Source validation included in this package

Run:

```bash
cd plugins/wanerpapi
./scripts/validate-source.sh
```

Then, from the OFBiz framework root, the decisive compatibility check is:

```bash
./gradlew processResources classes
```

and finally runtime REST smoke tests:

```bash
BASE=http://localhost:8080 ./plugins/wanerpapi/scripts/test-local.sh
```

## 15. Production hardening after the demo

- Make the Spring Boot facade the external contract.
- Replace `admin/ofbiz` with a dedicated integration identity.
- Restrict direct `/rest` access at reverse proxy/security-group level.
- Block or strongly authorize `wanerpAdmin*` endpoints outside administration.
- Validate KumulPay callbacks before calling the paid-sale operation.
- Add correlation IDs, audit events and idempotency persistence in the facade.
- Do not expose `performFindList` or raw `storeOrder` as public API contracts.


## Authoritative CLOUDCODE NEA seed dataset (recommended)

For a clean demo, use the OFBiz `ext-demo` dataset in `data/neademodata.xml` rather than relying only on the REST bootstrap. It contains NEA organization/cost centres, fictional employees, online/POS stores, catalogs/categories, products/services, opening inventory, and starter GL mappings.

To replace the stock OFBiz demo business data while preserving CLOUDCODE tenant configuration, read `docs/CLOUDCODE-RESET-AND-NEA-SEED.md` and run:

```bash
CONFIRM_RESET=RESET-CLOUDCODE-NEA plugins/wanerpapi/scripts/reset-cloudcode-and-seed-nea.sh
```

The reset script never writes to the `wanerptenant` database. It recreates only `wanerp_cloudcode`, loads `seed,seed-initial` without the `demo` reader, creates the local test admin, then loads `wanerpapi`'s `ext-demo` NEA data.


## End-user registration integration (v1.3.0)

When a person registers in `enduser.nea.wantoksys.com`, the trusted backend/future Spring Boot facade should call `wanerpApiRegisterEndUserCustomer`. The service creates/reconciles an OFBiz Person Party, ensures the `CUSTOMER` role, creates the primary email and optional phone/postal contact mechanisms, and returns `partyIdOut`.

Use the identity-provider subject (recommended: Keycloak `sub`) as `externalId`. This is the stable ERP-to-identity link and makes retries idempotent. Do not pass the user's password to OFBiz.

See `docs/ENDUSER-CUSTOMER-REGISTRATION.md` for the full sequence and cURLs. `wanerpApiCreateCustomer` remains a backward-compatible alias. A manual `wanerpAdminRegisterEndUserCustomer` alias is provided for controlled testing only.


Runtime end-user registration smoke test:

```bash
BASE=http://localhost:8080 plugins/wanerpapi/scripts/test-enduser-registration.sh
```
