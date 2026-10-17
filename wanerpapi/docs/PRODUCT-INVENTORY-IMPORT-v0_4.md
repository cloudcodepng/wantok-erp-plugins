# Wantok ERP - Generic Product + Inventory Import v0.4.0

## Design

- OFBiz: `ProductInventoryImportServices.java` (plugin `wanerpapi`)
- Quarkus: `ProductInventoryImportResource.java`
- OFBiz services: `wanerpAdminImportProductInventory`, `wanerpAdminGetProductImage`, `wanerpApiGetProductImage`
- Quarkus operation: `IMPORT_PRODUCT_INVENTORY`
- Facade route: `POST /api/v1/admin/products/import-xlsx` (same route)
- Quarkus image route: `POST /api/v1/products/image`
- Product IDs, suppliers, catalog, company, stock facility, and currency are tenant-relative.
- Tenant import settings are maintained server-side in `tenants.json` (NOT the XLSX).

## Tenant-specific import settings (example, insert property within SHONG tenant in your existing tenants.json)

```json
"productImport": {
  "enabled": true,
  "companyPartyId": "SHONG",
  "facilityId": "10000",
  "productStoreId": "10000",
  "prodCatalogId": "SHONG_GEREHU_CATALOG",
  "currencyUomId": "PGK"
}
```

Configure a different `productImport` block per tenant. Do **not** replace your existing tenants.json
or put passwords into version-controlled files. No tenant may import unless its admin API and
productImport are both enabled. The facade authenticates the tenant and overwrites tenant-scoped
workbook values. The OFBiz service double-checks the tenant delegator suffix.

## Install / upgrade

1. Back up your current OFBiz plugin and Quarkus source (`git status`, commit/stash first).
2. The `wanerpapi/` directory is a complete updated plugin; copy into `plugins/wanerpapi/`,
   preserving your local environment configuration. **Remove** any previously installed
   `ShongProductImportServices.java` file and replace the old `servicedef/services*.xml`
   registrations. Never leave both registrations/classes installed.
3. The `wantoksys-api-facade/` directory contains a *clean source-only* version of the Quarkus
   project built from your uploaded baseline (excludes .gradle/build caches and tenants.json).
   Merge its source files and build.gradle; remove `ShongProductImportResource.java` if installed.
   Preserve your real config, including tenant credentials; add per-tenant productImport config.
4. Build from host source (not existing Docker container):
   - OFBiz: `./gradlew processResources classes --no-daemon`
   - Quarkus: `./gradlew :cloudcode-wantoksys-api-facade:quarkusBuild --no-daemon`
5. Test with dryRun=true first; then import a SHONG database-backup-safe test workbook.

## Optional price-column naming

Generic tenant workbooks use `retail_price` and `unit_cost`, in the configured tenant currency.
For backwards compatibility only, the SHONG v1.7 workbook columns `retail_price_pgk` and
`unit_cost_pgk` are accepted when the tenant currency is PGK. No currency conversion is
performed. Product ID, SKU, product name stay mandatory; the other fields remain optional.

## Request example

```bash
curl -u "$FACADE_USER:$FACADE_PASSWORD" \
  -F tenantId=SHONG -F dryRun=true -F inventoryMode=OPENING_IF_EMPTY \
  -F "file=@SHONG_Product_Import_15_Products_v1_7_With_Gerehu_Opening_Inventory.xlsx" \
  "$FACADE_URL/api/v1/admin/products/import-xlsx"
```

**Safety constraints:** Each product row is a separate OFBiz service transaction. The importer
preflights all rows, but a later row can still fail after earlier rows committed. Opening inventory
mode skips any product that already has an inventory item in the configured facility.
Receipt batches use an audit attribute but are not yet concurrency-proof across cluster instances.
Avoid concurrent imports and backup tenant DB before a commit. Do not confuse this with posted
sales/payments. `sample_only` and `sellable=N` do not activate products.

**Unverified:** These Java sources have not passed a full Gradle build against your exact OFBiz
fork or Quarkus environment. Run host compilation before any live import.
