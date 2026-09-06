# CLOUDCODE application reset + NEA demo seed

## Why the reset is done this way

Do **not** try to delete every record loaded by OFBiz `seed` or `seed-initial`.
Those readers contain framework/application reference data such as statuses, types,
roles, UOMs and accounting metadata required for OFBiz to function.

The correct clean-demo pattern is:

1. Preserve the tenant master/config database (`wanerptenant`) and the CLOUDCODE
   domain/component mappings in it.
2. Back up and recreate **only** `wanerp_cloudcode`.
3. Load `seed,seed-initial` into `default#CLOUDCODE` — explicitly omit `demo`.
4. Recreate the local test admin in the tenant application DB.
5. Load only `wanerpapi`'s `ext-demo` reader (`data/neademodata.xml`).

This leaves CLOUDCODE tenant routing/configuration in `wanerptenant` untouched while replacing stock
OFBiz demo business data with NEA-specific business data. The script also takes a read-only backup of
`wanerptenant` before doing anything destructive.

**Important boundary:** the full-reset mode recreates `wanerp_cloudcode`. Therefore any manually-entered
custom configuration that lives only inside `wanerp_cloudcode` (rather than in `wanerptenant` or a plugin
`seed`/`seed-initial` file) will be present in the pre-reset backup but will not magically survive the rebuild.
Registered component seed/config files are reloaded by the `seed,seed-initial` step. If you have hand-maintained
CLOUDCODE configuration tables inside the application DB, preserve/reseed those explicitly before using full-reset
mode. The script never drops `wanerptenant` or its tenant-domain/component mappings.

## One-command reset

From the framework root, after installing this plugin:

```bash
chmod +x plugins/wanerpapi/scripts/reset-cloudcode-and-seed-nea.sh
CONFIRM_RESET=RESET-CLOUDCODE-NEA   plugins/wanerpapi/scripts/reset-cloudcode-and-seed-nea.sh
```

Defaults:
- Tenant: `CLOUDCODE`
- Application DB: `wanerp_cloudcode`
- PostgreSQL container: `postgresql-db`
- PostgreSQL superuser: `postgres`
- local test admin: `admin` (password from the standard OFBiz AdminUserLoginData template, normally temporary `ofbiz`)

The script first creates `pg_dump -Fc` backups of both `wanerp_cloudcode` and (read-only safety copy) `wanerptenant` under:

`runtime/backups/wanerpapi-cloudcode-reset/`

It refuses to run unless the explicit confirmation value is provided.

## What it does NOT reset

It does not drop or recreate:
- `wanerptenant`
- `wanerp`
- `wanerpolap`
- `wanerpolap_cloudcode`
- `keycloak`
- any CLOUDCODE hostname/domain mapping
- `tenant_component` rows
- PostgreSQL itself

`wanerpolap_cloudcode` is intentionally left alone in this version because the NEA API demo uses the transactional tenant database. Rebuild the OLAP tenant only when you intentionally refresh analytics/ETL.

## Load/reload only NEA data without DB reset

```bash
plugins/wanerpapi/scripts/load-nea-demo-data.sh
```

Equivalent command:

```bash
./gradlew "ofbiz --load-data delegator=default#CLOUDCODE readers=ext-demo component=wanerpapi"
```

## NEA dataset summary

Organization: `NEA` / National Energy Authority

Cost centres (OFBiz internal sub-organizations):
- `NEA_CC_EXEC`
- `NEA_CC_FIN`
- `NEA_CC_LIC`
- `NEA_CC_INSP`
- `NEA_CC_CUST`

Stores:
- `NEA_ONLINE_STORE`
- `NEA_POS_STORE`

Catalogs:
- `NEA_ONLINE_CATALOG`
- `NEA_POS_CATALOG`

Facility:
- `NEA_HQ_WAREHOUSE`

Categories:
- `NEA_CAT_LICENSE`
- `NEA_CAT_PERMITS`
- `NEA_CAT_INSPECT`
- `NEA_CAT_REGISTER`
- `NEA_CAT_EQUIP`

Deterministic API test item:
- product: `NEA-TEST-001`
- barcode/SKU: `NEA0001`
- price: K10.00
- QOH/ATP: 10 / 10 before sales

Other sample NEA service/product records include licence applications/renewals,
permits, inspections, safety certificates, contractor/electrician registrations,
a smart-meter demo item and inspection seals.

Employees:
- `NEA_EMP_001` ... `NEA_EMP_006` (fictional demo people)

Accounting:
- `PartyAcctgPreference` for `NEA`, base currency PGK
- NEA error journal
- starter chart of accounts `NEA1000` ... `NEA6200`
- `GlAccountOrganization` assignments
- AR/AP/inventory/COGS/sales/expense defaults
- sales invoice item GL mappings
- customer payment mapping
- `EXT_OFFLINE` payment-method GL mapping (used for the current KumulPay demo abstraction)

## Important accounting note

This is a **starter demo GL**, designed to let the NEA order/invoice/payment API
flow be exercised. It is not intended to represent NEA's approved statutory chart
of accounts. Replace/extend it with NEA Finance's real COA and mapping rules before
production use.
