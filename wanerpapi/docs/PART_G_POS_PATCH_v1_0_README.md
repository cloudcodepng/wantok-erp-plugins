# PART G — additive generic Wantok POS OFBiz extension v1.0 (review/UAT candidate)

**Input baseline:** user-uploaded `wanerpapi(4).zip`, 18 Oct 2026. Intended OFBiz: 24.09 host build (JDK 21). This patch does **not** alter existing `WanErpApiServices.java`, `ProductInventoryImportServices.java`, `services.xml`, `services_admin.xml`, `/api/v1/admin/products/import-xlsx`, `/api/v1/products/image`, existing data, or framework code.

## Critical scope and readiness

The patch adds POS-specific entity/service contracts **without pretending that financial writes are safe**. Authoritative native `Product`, `ProductPrice`, `InventoryItem`, `OrderHeader`, `Payment`, tax/accounting and `UserLogin` remain in OFBiz. 22 new OFBiz services and 8 new POS support entities are defined (only one existing XML descriptor gains registration lines). **This is a staged implementation, NOT sale-ready.** Run offline source validation first, then a backed-up sandbox tenant and controlled negative tests. Do not allow production cashier sales via these services until the financial paths are completed and certified.

**Implemented business logic (subject to local OFBiz compile/integration tests)**

- `wanerpPosAuthenticate`: internal gateway-only OFBiz `userLogin` password check; verifies tenant, login/party state, effective group/permission memberships; returns only `userLoginId`, `partyId`, group names, POS permissions and effective store grants. `auth=true`, `export=true`; requires service integration user with `WANERP_POS_BRIDGE`. Caller credentials must not be browser-accessible. Rate limiting/TLS/short JWT/lockout enforced by trusted Quarkus plus OFBiz.
- `wanerpPosAllowedStores`, `wanerpPosAuthorize`: signed actor proof plus **live** effective UserLogin permission, per-user/tenant ProductStore and Facility validation, terminal and optional shift checks. No trust in client supplied groups/store IDs.
- `wanerpPosGrantStore`, `wanerpPosRevokeStore`: authorized manager/admin store grants with audited changes; **bootstrap prerequisite** below.
- `wanerpPosSaveTerminal`, `wanerpPosGetTerminal`, `wanerpPosSaveDevice`: bound hardware configuration; drawer forced `MANUAL`, no automatic cash drawer kick enabled.
- `wanerpPosGetShift`, `wanerpPosLookupProduct`, `wanerpPosQuoteSale`, `wanerpPosGetSale`, `wanerpPosSearchAudit` — protected read and indicative quote paths. Product prices read using native `calculateProductPrice`; inventory ATP using native `getInventoryAvailableByFacility`. **Quotes deliberately exclude finalized GST/discount policy** and contain `readyToComplete=false`.
- `wanerpPosGetScaleProfile`, `wanerpPosSaveScaleProfile`, `wanerpPosSaveScalePlu`, `wanerpPosGetScalePlu`: administrative-only profiles/PLU mapping. Profiles are always saved with `validated=N`; no unverified scale label can trigger a sale. No duplicate `ProductPrice`.

**Registered but deliberately deny business writes**

- `wanerpPosOpenShift`: requires proven row-level lane lock + DB partial uniqueness.
- `wanerpPosCloseShift`: requires proven tender/cash reconciliation.
- `wanerpPosCompleteSale`: requires a verified native one-transaction order+tax+payment+stock process and **database-backed idempotency under concurrent lanes**.
- `wanerpPosVoidSale`, `wanerpPosCreateReturn`: require authoritative original receipt, native reversal and manager/refund approval semantics.

Do **not** turn these guards off to simulate operational success. The existing generic `wanerpApiCompletePaidSale` is not a substitute for the required seven-lane multi-line cash/EFTPOS transaction: it was built for a different KumulPay-oriented flow. The upcoming Quarkus implementation must treat fail-closed responses as errors and **must not** create a parallel payment/inventory process in Oracle.

## Identity, bridge and assertion

1. Create `WANERP_POS_BRIDGE` **SecurityPermission** master in the relevant tenant, and assign it ONLY to a dedicated, restricted integration UserLogin security group. Do not grant it to `SHONG_CASHIER`, `SHONG_MANAGER` or browser users. Do not use `SUPER` as your everyday integration actor. All POS services require authenticated OFBiz bridge account. The `wanerpPosAuthenticate` method is **not auth=false**.
2. Cashier/manager permissions are tenant-prefixed, e.g. `SHONG_POS_LOGIN`, `SHONG_POS_SALE`, `SHONG_POS_RETURN`, `SHONG_POS_REFUND`, `SHONG_POS_TERMINAL_VIEW`, `SHONG_POS_TERMINAL_ADMIN`, `SHONG_POS_SHIFT_CLOSE`, `SHONG_POS_REPORT`. These are individual full SecurityPermission IDs, not PartyRole names. Register any missing ID and grant deliberately (no auto-permissions in this patch). For additional tenants use `<TENANT>_POS_*`.
3. Start OFBiz with an **OS environment secret** unique per tenant: `WANERP_POS_BRIDGE_SECRET_SHONG` (minimum 32 unpredictable characters). Configure the same securely in Quarkus, NEVER in Oracle APEX JavaScript, source control, logs, or shared handoffs. Other tenants use `WANERP_POS_BRIDGE_SECRET_<TENANT>`.
4. All services except `wanerpPosAuthenticate` require signed actor parameters: `actorUserLoginId`, `issuedAtMillis` (JSON number, 64-bit), `requestId` (unique limited ASCII), `actorProof` (base64url, unpadded HMAC SHA256). Signature message (UTF-8 exactly): `tenantId + "\n" + actorUserLoginId + "\n" + JavaServiceInvokeName + "\n" + requestId + "\n" + issuedAtMillis`. Validity window 45 seconds. **Invoke name**, not service name (e.g. `allowedStores`, `lookupProduct`). Quarkus only may mint assertions from a fully validated JWT principal. Never accept a browser actor claim as authoritative. All service calls also require current POS permissions and effective store grants. No self-service assertion creation API exists.
5. Proof reuse within 45 seconds is not fully replay-proof for administrative writes. Future Quarkus patch must assign one `requestId` per operation, reject duplicates and add a durable nonce/command ledger before live financial writes. **Do not enable financial operations on this basis alone.** Restrict network access to the gateway.

## Bootstrap first store grant (manual supervised operation)

There is intentionally **no automatic all-store superuser grant**. In a test SHONG tenant with current store `10000` / facility `10000`, use the native Web Tools Entity Data editor (as privileged admin) to create exactly one `WanPosUserStoreGrant`: unique `grantId`; `userLoginId=shong.admin01` or a designated admin (not service account); `productStoreId=10000`; `facilityId=10000` or blank only if policy approved; `accessCode=MANAGE`; `fromDate` current date/time; `thruDate` empty; `grantedByUserLoginId` your administrator; `reason=Approved bootstrap for Gerehu`. Save and re-query to confirm one row. Do not create this in `wanerptenant` or CLOUDCODE. After bootstrap, use `wanerpPosGrantStore` to provision explicit cashiers' `SALE`, managers' `MANAGE`, store-scoped `VIEW` grants, with separation of duties. **No customer sale until those store grants have been fully verified.**

## Files added

- `entitydef/entitymodel_pos.xml` — 8 POS support entities, distinct from OFBiz native finance/product tables.
- `entitydef/entitygroup_pos.xml` — mapped to `org.apache.ofbiz` so `default#SHONG` uses `wanerp_shong`.
- `servicedef/services_pos.xml` — 22 new Java service signatures (all `auth=true`, `export=true`, POST), no changes to existing services.
- `src/main/java/org/apache/ofbiz/wanerpapi/api/WanErpPosServices.java` — new isolated class.
- `docs/PART_G_POS_PATCH_v1_0_README.md` — this runbook.
- `ofbiz-component.xml` — three additive resource registration lines; all preexisting lines retained.

## Safe apply / rollback from host source

```bash
ERP_HOME="$HOME/Working/Projects/Cloudcode/GENERIC/source/wantok-erp/wantok-erp-framework"
cd "$ERP_HOME"
# NEVER copy the ZIP over a running plugin / never discard Git changes.
git -C plugins status --short  # plugin repository may be nested
# Determine repository root BEFORE applying anything:
git -C plugins rev-parse --show-toplevel
# Inspect patch text and check applying BEFORE any modifications.
```

**Preferred repo-root patch command:** the patch paths begin `plugins/wanerpapi/...`; from `$ERP_HOME` run `git apply --check /absolute/path/to/....patch` **only if** `$ERP_HOME` is the Git root that owns `plugins/`; if `plugins` is a separate Git repository use `git -C plugins apply --check -p2 ...` (strip `a/plugins/` or `b/plugins/` prefixes) and then `git -C plugins apply -p2 ...`. Inspect `git -C plugins rev-parse --show-toplevel` first. Never run both command variants.

1. Verified, timestamped SHONG-only **post-import** PostgreSQL backup and checksum; no data migrations on other tenants.
2. Confirm current plugin file checksums match uploaded `wanerpapi(4).zip`. Read the patch and `git apply --check` against your actual working tree.
3. Apply patch to your actual plugin Git repo, check Git diff, then run `./gradlew --no-daemon processResources classes --stacktrace` from host OFBiz source. Resolve any fork-specific compile/schema-model differences and `./gradlew --no-daemon build --stacktrace` before restart.
4. Restart **host** OFBiz process under a controlled maintenance window. Inspect logs for new `WanPos*` entity model registration, missing table creation, Java service wiring errors, and tenant delegators. Actual model/table creation policy varies by fork; do not issue `loadAll`/`loadTenant`/`createTenant` or blindly hand-create tables.
5. Recheck **existing** `wanerpAdminImportProductInventory` and `wanerpApiGetProductImage` service registration, and use a health/image READ smoke test; do not recommit opening inventory.
6. Test the bridge-only auth with a valid staff login, bad password, locked/disabled login and unknown tenant. Test auth without bridge grant → denied.
7. Test signed actions, wrong signature/expired timestamp, revoked user/store, a second unauthorized store, disabled terminal, incorrect terminal facility and other-tenant access. No cashier JWT/token goes into Oracle browser-visible JavaScript.
8. Only after passing OFBiz source/build/scope tests patch Quarkus; then PL/SQL API and APEX. **Do not issue a live POS sale as a patch verification.**

### Rollback

Stop the OFBiz host instance; revert only the new POS source/service/model registration changes using `git apply -R` for the same patch, then rebuild/restart. **Database tables created by the POS entity model are NOT automatically removed on source rollback**; preserve them and restore SHONG-only database state from a verified backup only if a DBA explicitly authorizes a full tenant restore. Never restore an older pre-import SHONG snapshot inadvertently.

### Incomplete validations

This artifact was source- and static-validated against the attached plugin ZIP, **not compiled or run against your workstation's customized framework, PostgreSQL or tenant registry**. No source for the full framework, Quarkus POS adapter, APEX package or live PostgreSQL schema was supplied. `LoginServices.userLogin`, Security permissions and local service models are aligned with OFBiz 24.09 documentation but must pass your host build and integration tests. Further work is required for transaction atomicity, tax, discounts, tenders, refunds, offline EFTPOS receipt signing, scale-label verification, cash-shift concurrency, and receipt numbering.
