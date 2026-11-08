# Validation report

This package was checked before ZIP creation as follows:

- XML well-formedness: PASS (`ofbiz-component.xml`, `services.xml`, `services_admin.xml`)
- Operational REST service count: PASS — 18
- Setup/admin service count: PASS — 14
- XML `invoke` -> Java static method mapping: PASS for every service
- Java syntax/internal type consistency: PASS using compile-time stubs for the verified OFBiz release24.09 API signatures used by this class
- `ShoppingCart.addItemToEnd(...)` call shape: aligned with release24.09 javadocs/source
- `CheckOutHelper.createOrder(GenericValue)` call shape: aligned with release24.09
- Inventory guard: PASS — no `effectiveDate` is supplied to the release24.09 `createInventoryItemDetail` service
- Shell syntax: PASS for `scripts/test-local.sh`
- Setup IDs are deterministic/idempotent where required by the NEA demo

## Runtime validation boundary

The actual Cloudcode Wantok ERP source tree/database is not mounted in the artifact-generation environment, so the final decisive test must still be run in your source tree:

```bash
./gradlew processResources classes
./gradlew ofbiz
BASE=http://localhost:8080 plugins/wanerpapi/scripts/test-local.sh
```

This matters because your Wantok ERP is a customized release24.09 fork with custom tenant routing/component behavior. The package avoids modifications to core OFBiz source and keeps all changes isolated under `plugins/wanerpapi`.

- End-user registration contract: PASS — runtime `wanerpApiRegisterEndUserCustomer`, backward-compatible `wanerpApiCreateCustomer`, and admin/test alias `wanerpAdminRegisterEndUserCustomer` map to the same implementation.
- Registration logic test harness: first registration creates a Person/CUSTOMER/contact records; repeated registration with the same external identity returns the same Party without duplicate email/phone/address records; identity collision returns an error.

### End-user registration harness results

```text
REGISTRATION_FIRST_CREATE=PASS
REGISTRATION_IDEMPOTENT_RETRY=PASS
REGISTRATION_CONTACTS=PASS
REGISTRATION_IDENTITY_CONFLICT=PASS
CREATE_CUSTOMER_ALIAS=PASS
GET_PARTY_CONTACT_READBACK=PASS
```

The harness compiles the complete `WanErpApiServices.java` against test stubs matching the OFBiz signatures used by this plugin, then exercises the registration/retry/conflict/contact-readback logic in memory. The final authoritative runtime test remains compilation and execution in the customized Wantok ERP release24.09 source tree.
