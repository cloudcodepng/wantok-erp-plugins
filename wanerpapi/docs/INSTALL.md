# Local installation / test sequence

1. Extract into your Wantok ERP `plugins/` directory.
2. Run `plugins/wanerpapi/scripts/validate-source.sh`.
3. Register `wanerpapi` for tenant `CLOUDCODE` only if your custom tenant component registry requires it.
4. From the framework root run `./gradlew processResources classes`.
5. Start with `./gradlew ofbiz` or recreate only the `wantok-erp` container.
6. Obtain a REST token from `/rest/auth/token`.
7. Verify `wanerpApiHealth` appears in `/rest/services`.
8. Run `BASE=http://localhost:8080 plugins/wanerpapi/scripts/test-local.sh`.
9. Register a demo end-user customer with `wanerpApiRegisterEndUserCustomer`.
10. Test `wanerpApiCreateSalesOrder` first.
11. Verify the order in Wantok ERP UI.
12. Test invoice/payment APIs individually.
13. Finally test `wanerpApiCompletePaidSale` using a unique KumulPay reference and idempotency key.

Do not reload or recreate tenant databases for this plugin installation.


For end-user registration testing see `docs/ENDUSER-CUSTOMER-REGISTRATION.md`.
