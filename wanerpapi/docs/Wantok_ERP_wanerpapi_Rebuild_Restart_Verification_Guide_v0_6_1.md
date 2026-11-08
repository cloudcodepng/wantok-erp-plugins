# Wantok ERP / OFBiz 24.09 — Rebuild `wanerpapi` after Product + Inventory Import Changes

**Supplement to:** `Wantok_Generic_Product_Inventory_Importer_v0_6_Build_Test_Guide.md`, especially Section 4.  
**Target:** Kubuntu host source checkout, Apache OFBiz 24.09 / Wantok ERP, JDK 21, tenant SHONG (`wanerp_shong`), PostgreSQL container `postgresql-db` (previously host port 5438).  
**Important:** This guide does not alter source code, Git metadata, other tenants, or the databases. A successful build is not proof of a successful live import.

## 1. `.patch` files: purpose and when to use them

A `.patch` is a text representation of *changes* to a source tree. It is not a complete application, compiled plugin, executable, Docker image, or database migration. The importer patches extend the **original source baseline** with new Java implementations and service registrations. They do not require deleting `.git` or existing tenant files.

The `wanerpapi` v0.6 package includes `wanerpapi/PATCHES/PRODUCT_INVENTORY_IMPORT_v0_6.patch`. The Quarkus package has its own patch. **Use patches only if you have not already installed the corresponding changes.** Applying the same patch twice is wrong; applying it to a different baseline can fail or conflict.

Example when installing into an *unchanged matching original* plugin checkout:

```bash
cd "$HOME/Working/Projects/Cloudcode/GENERIC/source/wantok-erp/wantok-erp-framework/plugins/wanerpapi"

# Inspect, do not apply blindly
head -40 /path/to/PRODUCT_INVENTORY_IMPORT_v0_6.patch
git status --short
git apply --check /path/to/PRODUCT_INVENTORY_IMPORT_v0_6.patch
# ONLY if the check succeeds AND the feature is not already installed:
git apply /path/to/PRODUCT_INVENTORY_IMPORT_v0_6.patch
```

If `git apply --check` fails because the importer is already installed, **do not force it**. Check the actual files and proceed to compilation. If `wanerpapi` is not its own Git repository, its parent OFBiz checkout may own the files; `git apply` will operate in that checkout. If the plugin is untracked, use a staging copy and manually merge the exact importer files instead of destructive Git operations.

**Never** use `rsync --delete`, `git reset --hard`, `git clean -fdx`, a wholesale ZIP extraction over your working checkout, or replace an existing `.git` directory. Preserve `CLOUDCODE` and all other tenants.

## 2. Confirm the feature is installed before rebuilding

From the framework root:

```bash
export ERP_ROOT="$HOME/Working/Projects/Cloudcode/GENERIC/source/wantok-erp/wantok-erp-framework"
cd "$ERP_ROOT"

# New class must exist
ls -l plugins/wanerpapi/src/main/java/org/apache/ofbiz/wanerpapi/api/ProductInventoryImportServices.java

# Service models must refer to the generic class
rg -n 'wanerpAdminImportProductInventory|wanerpAdminGetProductImage|wanerpApiGetProductImage' \
    plugins/wanerpapi/servicedef

# The unchanged component descriptor must load both files
rg -n 'servicedef/services(_admin)?\.xml' plugins/wanerpapi/ofbiz-component.xml

# Review local changes without discarding anything
git status --short
```

Expected: `ProductInventoryImportServices.java` exists, `wanerpAdminImportProductInventory` is registered in `servicedef/services_admin.xml`, `wanerpApiGetProductImage` in `servicedef/services.xml`, and `ofbiz-component.xml` loads both. **No need to load new OFBiz seed data or create a second warehouse.** The feature uses existing OFBiz entities and native inventory services.

## 3. Check the Java/Gradle toolchain

```bash
cd "$ERP_ROOT"
java -version
./gradlew --version
```

Use the existing, working **host** toolchain (JDK 21). Do **not** compile inside the old `wantok-erp` container: the intended runtime for this SHONG development stage is host OFBiz. PostgreSQL remains in Docker.

## 4. Compile updated OFBiz and `wanerpapi`

```bash
cd "$ERP_ROOT"

# Fast compile / resource processing first; records the actual compiler error, if any
./gradlew --no-daemon processResources classes --stacktrace \
  2>&1 | tee /tmp/wantok-ofbiz-import-compile.log
```

**Confirm the command ends in `BUILD SUCCESSFUL`.** If it fails, search the log for the first `error:` or `Execution failed for task` rather than attempting an import. Where applicable, check that the new plugin `.class` was emitted:

```bash
find plugins/wanerpapi/build build -type f \
  -name 'ProductInventoryImportServices.class' -print 2>/dev/null
```

If no class file is found despite an apparently successful *root* `classes` task, inspect available Gradle tasks and the current plugin build configuration; do not assume the plugin was compiled merely because the root project succeeded:

```bash
./gradlew tasks --all | grep -Ei 'compileJava|classes|build' | head -60
```

After compiling the plugin, run the full build if appropriate for your current checked-out fork:

```bash
cd "$ERP_ROOT"
./gradlew --no-daemon build --stacktrace \
  2>&1 | tee /tmp/wantok-ofbiz-import-build.log
```

`build` compiles/packages/tests according to your Gradle setup; it does **not** automatically start OFBiz, rebuild a Docker image, or initialize any SHONG records. Avoid `clean` during an active host runtime unless needed and planned.

## 5. Restart host OFBiz to load the new Java classes and service XML

A running JVM will not normally reload the newly compiled Java class and service models just because Gradle compiled them. **You must restart the running host OFBiz process** to load them.

1. Confirm the PostgreSQL container is still running: `docker ps --filter name=postgresql-db`.
2. Verify that the **host** SHONG JDBC settings still use the validated host-reachable address/port (previously `localhost:5438`) rather than Docker-only `postgresql-db:5432`, unless you have explicitly configured host resolution/routing.
3. Stop the **existing host OFBiz process** using the way you started it (e.g., Ctrl+C in its running terminal or your existing service-management procedure). Do **not** stop or delete PostgreSQL, and do not start a second OFBiz instance on the same ports.
4. Restart from the framework root:

```bash
cd "$ERP_ROOT"
./gradlew ofbiz
```

5. Inspect startup logs for `wanerpapi` component/service model load errors, class loading exceptions, authentication errors and SHONG datasource errors. Use your existing log location and `grep`/`rg` to locate `wanerpAdminImportProductInventory`, `ProductInventoryImportServices`, and `wanerpapi`.
6. Using your working JWT-authenticated OFBiz REST setup, confirm the new service is discoverable/accepts a **dry-run** request through Quarkus. A running HTTP port alone does not prove registration.

**Do not run:** `loadAll`, `createTenant`, `loadData`, a database reset, or a fresh setup. The SHONG organization, catalog, suppliers and Gerehu facility `10000` already exist.

## 6. Only if you later deploy OFBiz from Docker instead of the host

The host build **does not change an already-built or running Docker image**. If you subsequently switch to image deployment, build a **new** image using the **actual Dockerfile and Compose service name in your repository**, and redeploy that image with the existing database volume/network/tenant settings. Check the image includes the compiled updated `wanerpapi` classes and registered XML services. Do not guess a `docker compose build` service name or run a data initialization task. This is **not required** for the current host-run SHONG test.

## 7. Run the importer through Quarkus only after both applications pass checks

First ensure Quarkus builds and starts using its existing configuration/tenant registry:

```bash
cd "$HOME/Working/Projects/Cloudcode/GENERIC/source/wantok-erp/wantoksys-api-facade"
./gradlew :cloudcode-wantoksys-api-facade:test \
  :cloudcode-wantoksys-api-facade:quarkusBuild --no-daemon --stacktrace
./scripts/run-dev.sh
```

Use the **existing SHONG tenant Basic/UUID credential** in the runtime tenant registry and the configured SHONG OFBiz integration password. Never paste them in logs or Git. In a separate terminal, run a **dry run first**:

```bash
export FACADE_URL='http://localhost:9022'  # use your actual permitted facade Host
export XLSX="$HOME/Downloads/SHONG_Product_Import_15_Products_v1_7_With_Gerehu_Opening_Inventory.xlsx"
read -rs -p 'SHONG API UUID password: ' FACADE_PASSWORD; echo

curl --fail-with-body -sS -u "SHONG:${FACADE_PASSWORD}" \
  -F 'tenantId=SHONG' \
  -F 'dryRun=true' \
  -F 'inventoryMode=OPENING_IF_EMPTY' \
  -F "file=@${XLSX}" \
  "$FACADE_URL/api/v1/admin/products/import-xlsx" \
  | tee /tmp/shong-import-dry-run.json | python3 -m json.tool
```

Check **every one of the 15 row results**, not just HTTP 200. Do not submit `dryRun=false` until the dry run passes, SHONG-specific authorization and database isolation are confirmed, and `wanerp_shong` has been backed up. The committed importer **will post inventory** through `createInventoryItem` / `createInventoryItemDetail`. Final cash/EFTPOS and POS sales tests remain separate.

## 8. Troubleshooting

| Symptom | Check |
|---|---|
| `ProductInventoryImportServices` class not found | Wrong build/output location or old host JVM; confirm class file and restart |
| OFBiz says service not found | `ofbiz-component.xml` references, service XML syntax, plugin loaded at startup |
| Compiler errors in new Java class | Capture the actual `javac` error; don't change unrelated modules |
| 401/403 on import | SHONG API UUID/allowed Host, OFBiz JWT user and CATALOG/FACILITY permissions |
| Other tenant records affected | Stop immediately; inspect tenant routing/delegator before any more writes |
| PostgreSQL connection fails from host | Check the existing host JDBC URL (previous host port `5438`), not Docker DNS |
| Dry run succeeds but inventory remains unchanged | Expected: a dry run never writes |
| Repeat opening upload skips inventory | Expected for `OPENING_IF_EMPTY` when any item exists at facility; not a quantity reconciliation |

For complete tenant setup, detailed workbook constraints, stock quantities and SQL verification, see `Wantok_Generic_Product_Inventory_Importer_v0_6_Build_Test_Guide.md`, Sections 2–10.
