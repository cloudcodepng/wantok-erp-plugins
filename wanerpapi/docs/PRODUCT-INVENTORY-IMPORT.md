# Product and inventory import services

Generic OFBiz service `wanerpAdminImportProductInventory` extends existing wanerpapi services without altering their behavior. Java: `org.apache.ofbiz.wanerpapi.api.ProductInventoryImportServices`. A read-only image service is separately registered. The existing `WanErpApiServices.java` and all other services remain unchanged.

The Quarkus facade authenticates tenant-specific incoming requests and calls this service via its existing OFBiz JWT gateway. The OFBiz method checks the selected tenant delegator, product/store/facility/company, permission set, and performs native inventory item/detail writes.

Opening stock in SHONG uses facility 10000. If any item already exists for a product at the facility, OPENING_IF_EMPTY skips stock. RECEIPT_ADD requires a batchId and still does not provide multi-node exactly-once uniqueness.

Java compilation, OFBiz service-model validity, native service invocation and the live tenant import require confirmation on Kubuntu, as set out in the standalone v0.6 guide. The service is not yet production-certified.
