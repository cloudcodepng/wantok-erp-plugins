package org.apache.ofbiz.wanerpapi.api;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.transaction.TransactionUtil;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/** Tenant-scoped product, image and inventory import. One service invocation = one transactional row. */
public final class ProductInventoryImportServices {
    private static final Pattern PRODUCT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,19}");
    private static final Pattern SKU = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,59}");
    private static final Pattern SUPPLIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,19}");
    private static final int MAX_IMAGE_BYTES = 512 * 1024;

    // Import is intentionally restricted to non-serialized opening stock and explicit receipts.
    private static final String OPENING_IF_EMPTY = "OPENING_IF_EMPTY";
    private static final String RECEIPT_ADD = "RECEIPT_ADD";
    private static final String STOCK_NONE = "NONE";
    private static final Pattern STOCK_BATCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{3,59}");

    private ProductInventoryImportServices() { }

    private static String string(Map<String, ?> c, String name) {
        Object v = c.get(name);
        return v == null ? "" : v.toString().trim();
    }

    private static void check(boolean good, String message) {
        if (!good) {
            throw new IllegalArgumentException(message);
        }
    }

    private static Map<String, Object> attrs(Object... entries) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < entries.length; i += 2) {
            if (entries[i + 1] != null) {
                m.put((String) entries[i], entries[i + 1]);
            }
        }
        return m;
    }

    private static Map<String, Object> call(LocalDispatcher dispatcher, String name,
            Map<String, Object> params) throws Exception {
        Map<String, Object> result = dispatcher.runSync(name, params);
        if (ServiceUtil.isError(result) || ServiceUtil.isFailure(result)) {
            throw new IllegalStateException(name + ": " + ServiceUtil.getErrorMessage(result));
        }
        return result;
    }

    private static GenericValue find(Delegator d, String type, String field, Object value) throws Exception {
        return EntityQuery.use(d).from(type).where(field, value).queryOne();
    }

    // OFBiz DataResource IDs are short; use a stable content-independent digest key.
    // Tenant isolation is provided by the OFBiz tenant delegator/database.
    private static String imageId(String productId) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(productId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder("WPI");
        for (int i = 0; i < 8; i++) {
            b.append(String.format("%02x", digest[i] & 0xff));
        }
        return b.toString();
    }

    // Supplier purchase agreements are not a prerequisite for uploading products.
    // The workbook may still retain agreement IDs for a future purchasing workflow.
    private static BigDecimal optionalAmount(Map<String, ?> p, String key) {
        String raw = string(p, key);
        if (raw.isBlank()) {
            return null;
        }
        BigDecimal amount = new BigDecimal(raw);
        check(amount.signum() >= 0 && amount.scale() <= 2 && amount.precision() <= 14,
                key + " must be a non-negative amount with at most 2 decimals");
        return amount;
    }

    private static boolean validCheckDigit(String barcode) {
        int sum = 0;
        for (int i = barcode.length() - 2, pos = 0; i >= 0; i--, pos++) {
            int digit = barcode.charAt(i) - '0';
            sum += digit * (pos % 2 == 0 ? 3 : 1);
        }
        return (10 - sum % 10) % 10 == barcode.charAt(barcode.length() - 1) - '0';
    }

    private static String contentMime(byte[] bytes) {
        if (bytes.length > 3 && (bytes[0] & 255) == 0xff && (bytes[1] & 255) == 0xd8) {
            return "image/jpeg";
        }
        if (bytes.length > 7 && (bytes[0] & 255) == 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4e && bytes[3] == 0x47) {
            return "image/png";
        }
        throw new IllegalArgumentException("Image must be JPEG or PNG");
    }

    /**
     * Combined tenant product + inventory master import. A positive initial quantity creates
     * one non-serialized InventoryItem and a matching InventoryItemDetail (QOH/ATP).
     * A repeated OPENING_IF_EMPTY import will NOT add stock again. Additional receipts
     * require RECEIPT_ADD with an explicit batch ID and are audited with an item attribute.
     * Each OFBiz service invocation is its own transaction; this is NOT whole-workbook atomic.
     */
    public static synchronized Map<String, Object> importItem(DispatchContext ctx, Map<String, Object> p) {
        try {
            Delegator d = ctx.getDelegator();
            GenericValue user = (GenericValue) p.get("userLogin");
            check(user != null, "Authenticated OFBiz user required");
            String tenantId = string(p, "tenantId");
            check(tenantId.matches("[A-Za-z0-9_-]{1,40}"), "Invalid tenantId");
            check(d.getDelegatorName().endsWith("#" + tenantId),
                    "Tenant identity does not match the OFBiz delegator");
            String ownerPartyId = string(p, "companyPartyId");
            String facilityId = string(p, "facilityId");
            String currencyId = string(p, "currencyUomId");
            check(!ownerPartyId.isBlank() && !facilityId.isBlank() && !currencyId.isBlank(),
                    "Tenant companyPartyId, facilityId and currencyUomId are required");
            check(ctx.getSecurity().hasEntityPermission("CATALOG", "_CREATE", user), "CATALOG_CREATE required");
            check(ctx.getSecurity().hasEntityPermission("CATALOG", "_UPDATE", user), "CATALOG_UPDATE required");
            String id = string(p, "productId");
            String sku = string(p, "sku");
            String name = string(p, "productName");
            String barcode = string(p, "barcode");
            String barType = string(p, "barcodeType");
            String category = string(p, "categoryId");
            String supplier = string(p, "supplierPartyId");
            String brand = string(p, "brandName");
            BigDecimal retailPrice = optionalAmount(p, "retailPrice");
            BigDecimal unitCost = optionalAmount(p, "unitCost");
            BigDecimal openingQty = optionalStockQuantity(p);
            String inventoryMode = string(p, "inventoryMode");
            if (inventoryMode.isBlank()) {
                inventoryMode = OPENING_IF_EMPTY;
            }
            String inventoryBatchId = string(p, "inventoryBatchId");
            check(STOCK_NONE.equals(inventoryMode) || OPENING_IF_EMPTY.equals(inventoryMode)
                    || RECEIPT_ADD.equals(inventoryMode), "Unsupported inventory mode");
            if (RECEIPT_ADD.equals(inventoryMode) && openingQty.signum() > 0) {
                check(STOCK_BATCH.matcher(inventoryBatchId).matches(),
                        "RECEIPT_ADD requires a stable inventoryBatchId (4-60 safe characters)");
            }
            String uom = string(p, "sellUnit");
            String store = string(p, "productStoreId");
            String catalog = string(p, "prodCatalogId");
            boolean dryRun = Boolean.TRUE.equals(p.get("dryRun"));
            boolean allowDraft = Boolean.TRUE.equals(p.get("allowDraft"));
            check(PRODUCT_ID.matcher(id).matches(), "Invalid product ID (1-20 safe characters)");
            check(SKU.matcher(sku).matches(), "Invalid SKU (1-60 safe characters)");
            check(!name.isBlank() && name.length() <= 250, "Product name missing/too long");
            check(!name.equalsIgnoreCase("null"), "Invalid name");
            check(!store.isBlank() && !catalog.isBlank(), "Store and catalog are required");
            if (!category.isBlank()) {
                check(PRODUCT_ID.matcher(category).matches(), "Invalid category ID");
            }
            if (!supplier.isBlank()) {
                check(SUPPLIER.matcher(supplier).matches(), "Invalid supplier Party ID");
            }
            check("OTH_ea".equals(uom) || "WT_kg".equals(uom), "Unsupported stock UOM: currently OTH_ea or WT_kg");
            if ("OTH_ea".equals(uom)) {
                check(openingQty.stripTrailingZeros().scale() <= 0,
                        "EA stock quantity must be a whole number");
            }
            if (openingQty.signum() > 0 && !STOCK_NONE.equals(inventoryMode)) {
                check(ctx.getSecurity().hasEntityPermission("FACILITY", "_CREATE", user),
                        "FACILITY_CREATE required for inventory posting");
            }
            check(allowDraft || dryRun, "Commit requires allowDraft=true as an explicit write acknowledgement");
            // Importing master records never activates sales. Sellable must be N or omitted.
            String sellable = string(p, "sellable");
            check(sellable.isBlank() || "N".equals(sellable), "Sellable=Y requires separate activation workflow");
            check(brand.length() <= 100, "Brand name too long");

            if ("SCALE_WEIGHT_EAN13".equals(barType)) {
                check(barcode.isEmpty() && "WT_kg".equals(uom), "Scale item must have no fixed GTIN and use WT_kg");
            } else if (!barcode.isBlank()) {
                check("EAN13".equals(barType) || "EAN8".equals(barType), "Unsupported GTIN type");
                check(barcode.matches("[0-9]+") && barcode.length() == ("EAN8".equals(barType) ? 8 : 13)
                        && validCheckDigit(barcode), "Bad GTIN/check digit");
            }
            check(find(d, "ProductStore", "productStoreId", store) != null, "Missing ProductStore");
            check(find(d, "ProdCatalog", "prodCatalogId", catalog) != null, "Missing catalog");
            GenericValue storeRec = find(d, "ProductStore", "productStoreId", store);
            String payToParty = storeRec.getString("payToPartyId");
            check(payToParty == null || payToParty.isBlank() || ownerPartyId.equals(payToParty),
                    "ProductStore payToPartyId differs from tenant company");
            if (!category.isBlank()) {
                GenericValue categoryRec = find(d, "ProductCategory", "productCategoryId", category);
                check(categoryRec != null && "CATALOG_CATEGORY".equals(categoryRec.getString("productCategoryTypeId")),
                        "Merchandise catalog category not found");
            }
            check(find(d, "Uom", "uomId", uom) != null, "Unknown sell UOM");
            check(find(d, "Uom", "uomId", currencyId) != null, "Unknown tenant currency UOM");
            if (openingQty.signum() > 0 && !STOCK_NONE.equals(inventoryMode)) {
                GenericValue fac = find(d, "Facility", "facilityId", facilityId);
                check(fac != null, "Tenant facility does not exist");
                String actualOwner = fac.getString("ownerPartyId");
                check(actualOwner == null || actualOwner.isBlank() || ownerPartyId.equals(actualOwner),
                        "Facility owner does not match the tenant company");
                check(find(d, "InventoryItemType", "inventoryItemTypeId", "NON_SERIAL_INV_ITEM") != null,
                        "NON_SERIAL_INV_ITEM is missing");
                check(find(d, "Party", "partyId", ownerPartyId) != null,
                        "Tenant inventory owner Party missing");
            }
            if (!supplier.isBlank()) {
                check(find(d, "Party", "partyId", supplier) != null, "Missing supplier Party");
                check(EntityQuery.use(d).from("PartyRole")
                        .where("partyId", supplier, "roleTypeId", "SUPPLIER")
                        .queryOne() != null, "Party does not have SUPPLIER role");
            }
            // No Agreement lookup: production and pilot product imports may omit agreements.
            check(EntityQuery.use(d).from("ProductStoreCatalog")
                    .where("productStoreId", store, "prodCatalogId", catalog).queryFirst() != null,
                    "ProductStore not linked to catalog");
            for (GenericValue gi : EntityQuery.use(d).from("GoodIdentification").where("idValue", sku).queryList()) {
                check(id.equals(gi.getString("productId")), "SKU already assigned to another product");
            }
            if (!barcode.isBlank()) {
                for (GenericValue gi : EntityQuery.use(d).from("GoodIdentification")
                        .where("idValue", barcode).queryList()) {
                    check(id.equals(gi.getString("productId")), "GTIN already assigned to another product");
                }
            }
            byte[] image = new byte[0];
            String b64 = string(p, "imageBase64");
            if (!b64.isBlank()) {
                check(b64.length() <= MAX_IMAGE_BYTES * 2, "Image payload too large");
                image = Base64.getDecoder().decode(b64);
                check(image.length <= MAX_IMAGE_BYTES, "Image exceeds 512 KiB");
                check(contentMime(image).equals(string(p, "imageMimeType")), "Image MIME mismatch");
            }
            // Images are optional. If supplied, MIME and size are strictly validated.
            // Native createContent creates an OWNER ContentRole via SECA; it needs
            // userLogin.partyId. Verify this even during dry-run, before any writes.
            if (image.length > 0) {
                String creatorPartyId = user.getString("partyId");
                check(creatorPartyId != null && !creatorPartyId.isBlank()
                        && find(d, "Party", "partyId", creatorPartyId) != null,
                        "Image content creation requires a UserLogin linked to a valid Party");
                check(find(d, "ProductContentType", "productContentTypeId", "ORIGINAL_IMAGE_URL") != null,
                        "OFBiz ORIGINAL_IMAGE_URL content type missing");
                check(find(d, "ContentType", "contentTypeId", "DOCUMENT") != null,
                        "OFBiz DOCUMENT content type missing");
            }
            GenericValue existing = find(d, "Product", "productId", id);
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("productId", id);
            out.put("productIdOut", id);
            out.put("outcome", dryRun ? "VALIDATED_ONLY" : existing == null ? "CREATED" : "UPDATED");
            out.put("imageBytes", image.length);
            out.put("supplierPartyId", supplier);
            out.put("inventoryMode", inventoryMode);
            out.put("requestedInventoryQty", openingQty);
            String stockOutcome = inventoryDisposition(d, id, facilityId, openingQty, inventoryMode, inventoryBatchId);
            out.put("inventoryOutcome", stockOutcome);
            out.put("facilityId", facilityId);
            if (dryRun) {
                return out;
            }

            LocalDispatcher dispatcher = ctx.getDispatcher();
            // Product updates must not reset existing tax, shipping, variant, inventory or
            // other operational flags for products managed by existing tenants/APIs.
            if (existing != null) {
                check("FINISHED_GOOD".equals(existing.getString("productTypeId")),
                        "Cannot overwrite an existing non-finished-good product");
                String previousUom = existing.getString("quantityUomId");
                check(previousUom == null || previousUom.isBlank() || previousUom.equals(uom),
                        "Existing product UOM differs; use a supervised UOM conversion");
            }
            Map<String, Object> productArgs = attrs("productId", id,
                    "internalName", name, "productName", name,
                    "description", string(p, "description").isBlank() ? null : string(p, "description"),
                    "brandName", brand.isBlank() ? null : brand,
                    "quantityUomId", uom,
                    "orderDecimalQuantity", "WT_kg".equals(uom) ? "Y" : "N",
                    "userLogin", user);
            if (existing == null) {
                productArgs.putAll(attrs("productTypeId", "FINISHED_GOOD",
                        "requireInventory", "Y", "chargeShipping", "N", "taxable", "N",
                        "isVirtual", "N", "isVariant", "N"));
            }
            call(dispatcher, existing == null ? "createProduct" : "updateProduct", productArgs);
            ensureIdentifier(d, dispatcher, user, id, "SKU", sku);
            if (!barcode.isEmpty()) {
                ensureIdentifier(d, dispatcher, user, id, "EAN", barcode);
            }
            if (!category.isBlank() && EntityQuery.use(d).from("ProductCategoryMember")
                    .where("productId", id, "productCategoryId", category).queryFirst() == null) {
                call(dispatcher, "addProductToCategory", attrs("productId", id, "productCategoryId", category,
                        "fromDate", Timestamp.from(Instant.now()), "userLogin", user));
            }
            if (!supplier.isBlank()) {
                // Native createSupplierProduct requires lastPrice. Entity write accepts nullable
                // lastPrice; no fabricated cost is used when unitCost is absent.
                GenericValue supplierProduct = EntityQuery.use(d).from("SupplierProduct")
                        .where("productId", id, "partyId", supplier).orderBy("-availableFromDate").queryFirst();
                if (supplierProduct == null) {
                    supplierProduct = d.makeValue("SupplierProduct", attrs("productId", id, "partyId", supplier,
                            "availableFromDate", Timestamp.from(Instant.now()), "minimumOrderQuantity", BigDecimal.ONE,
                            "currencyUomId", currencyId, "supplierPrefOrderId", "10_MAIN_SUPPL",
                            "quantityUomId", uom, "unitsIncluded", BigDecimal.ONE,
                            "supplierProductId", sku, "lastPrice", unitCost, "canDropShip", "N",
                            "comments", "Imported supplier-product association; no agreement verification"));
                    supplierProduct.create();
                } else {
                    check(currencyId.equals(supplierProduct.getString("currencyUomId")),
                            "Existing SupplierProduct currency mismatch");
                    if (unitCost != null) {
                        supplierProduct.set("lastPrice", unitCost);
                        supplierProduct.store();
                    }
                }
            }
            if (retailPrice != null) {
                saveRetailPrice(d, dispatcher, user, id, currencyId, retailPrice);
            }
            if (image.length > 0) {
                String mime = string(p, "imageMimeType");
                saveImage(d, id, image, mime);
                // Reuse the IMAGE_OBJECT bytes already written above. All content
                // and URL updates take place in the SAME importer row transaction.
                String contentId = ensureImageContent(d, dispatcher, user, id, mime);
                String url = "/content/control/ViewBinaryDataResource?contentId=" + contentId;
                ensureProductImageUrls(d, dispatcher, user, id, url);
                out.put("imageResourceId", imageId(id));
                out.put("imageContentId", contentId);
                out.put("imageUrl", url);
            }
            out.put("retailPriceApplied", retailPrice != null);
            out.put("unitCostApplied", unitCost != null && !supplier.isBlank());
            if ("READY_TO_POST".equals(stockOutcome)) {
                String itemId = postInventory(dispatcher, d, user, id, facilityId,
                        ownerPartyId, currencyId, openingQty, unitCost, inventoryBatchId);
                out.put("inventoryItemId", itemId);
                out.put("inventoryOutcome", "POSTED");
                out.put("inventoryPostedQty", openingQty);
            } else {
                out.put("inventoryPostedQty", BigDecimal.ZERO);
            }
            return out;
        } catch (Exception e) {
            // A row can fail after some native service calls. Require rollback of the
            // current OFBiz transaction so product and inventory do not diverge.
            try {
                TransactionUtil.setRollbackOnly("Product/inventory import failed", e);
            } catch (Exception rollbackError) {
                e.addSuppressed(rollbackError);
            }
            return ServiceUtil.returnError("Product/inventory import failed: " + e.getMessage());
        }
    }

    /** Optional quantity: blank == zero. Inventory is decimal KG or whole EA. */
    private static BigDecimal optionalStockQuantity(Map<String, ?> p) {
        String raw = string(p, "openingStockQty");
        if (raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        BigDecimal qty = new BigDecimal(raw);
        check(qty.signum() >= 0 && qty.scale() <= 3 && qty.precision() <= 14,
                "opening_stock_qty must be >= 0 with <= 3 decimal places");
        return qty;
    }

    private static boolean receiptPreviouslyRecorded(Delegator d, String id, String facilityId, String batchId)
            throws Exception {
        // Item attribute is an audit marker; check product/facility as well as batch.
        for (GenericValue attr : EntityQuery.use(d).from("InventoryItemAttribute")
                .where("attrName", "WPI_IMPORT_BATCH", "attrValue", batchId).queryList()) {
            GenericValue item = find(d, "InventoryItem", "inventoryItemId", attr.getString("inventoryItemId"));
            if (item != null && id.equals(item.getString("productId"))
                    && facilityId.equals(item.getString("facilityId"))) {
                return true;
            }
        }
        return false;
    }

    private static String inventoryDisposition(Delegator d, String id, String facilityId, BigDecimal qty,
            String mode, String batchId) throws Exception {
        if (qty.signum() == 0 || STOCK_NONE.equals(mode)) {
            return "NO_STOCK_CHANGE";
        }
        if (OPENING_IF_EMPTY.equals(mode)) {
            // Conservative by design: never overlay opening stock onto existing balances.
            GenericValue anyItem = EntityQuery.use(d).from("InventoryItem")
                    .where("productId", id, "facilityId", facilityId).queryFirst();
            return anyItem == null ? "READY_TO_POST" : "SKIPPED_ALREADY_EXISTS";
        }
        return receiptPreviouslyRecorded(d, id, facilityId, batchId) ? "SKIPPED_DUPLICATE_BATCH" : "READY_TO_POST";
    }

    private static String postInventory(LocalDispatcher dispatcher, Delegator d, GenericValue user,
            String productId, String facilityId, String ownerPartyId,
            String currencyId, BigDecimal qty, BigDecimal unitCost, String batchId) throws Exception {
        Timestamp now = Timestamp.from(Instant.now());
        // Create a separate non-serialized inventory item for each authorized opening/receipt.
        // Never update QOH/ATP fields directly: createInventoryItemDetail is the stock ledger.
        Map<String, Object> result = call(dispatcher, "createInventoryItem", attrs(
                "inventoryItemTypeId", "NON_SERIAL_INV_ITEM", "productId", productId,
                "facilityId", facilityId, "ownerPartyId", ownerPartyId,
                "currencyUomId", currencyId, "unitCost", unitCost,
                "datetimeReceived", now, "userLogin", user));
        String itemId = String.valueOf(result.get("inventoryItemId"));
        check(!itemId.isBlank() && !"null".equals(itemId), "Inventory service did not return inventoryItemId");
        // OFBiz quantity/ATP totals are maintained via InventoryItemDetail and entity ECA.
        call(dispatcher, "createInventoryItemDetail", attrs("inventoryItemId", itemId,
                "quantityOnHandDiff", qty, "availableToPromiseDiff", qty, "userLogin", user));
        if (!batchId.isBlank()) {
            call(dispatcher, "createInventoryItemAttribute", attrs(
                    "inventoryItemId", itemId, "attrName", "WPI_IMPORT_BATCH",
                    "attrValue", batchId, "userLogin", user));
        }
        return itemId;
    }

    /** Optional test retail price; blank never deletes or replaces an existing price. */
    private static void saveRetailPrice(Delegator d, LocalDispatcher dispatcher, GenericValue user,
            String productId, String currencyId, BigDecimal targetPrice) throws Exception {
        Timestamp now = Timestamp.from(Instant.now());
        GenericValue current = null;
        for (GenericValue row : EntityQuery.use(d).from("ProductPrice")
                .where("productId", productId, "productPriceTypeId", "DEFAULT_PRICE",
                        "productPricePurposeId", "PURCHASE", "currencyUomId", currencyId,
                        "productStoreGroupId", "_NA_")
                .orderBy("-fromDate").queryList()) {
            Timestamp end = row.getTimestamp("thruDate");
            if (!row.getTimestamp("fromDate").after(now) && (end == null || end.after(now))) {
                current = row;
                break;
            }
        }
        if (current != null && current.getBigDecimal("price") != null
                && targetPrice.compareTo(current.getBigDecimal("price")) == 0) {
            return;
        }
        if (current != null) {
            current.set("thruDate", now);
            current.store();
        }
        call(dispatcher, "createProductPrice", attrs("productId", productId,
                "productPriceTypeId", "DEFAULT_PRICE", "productPricePurposeId", "PURCHASE",
                "currencyUomId", currencyId, "productStoreGroupId", "_NA_",
                "fromDate", now, "price", targetPrice, "userLogin", user));
    }

    private static void ensureIdentifier(Delegator d, LocalDispatcher dispatcher, GenericValue user,
            String productId, String typeId, String value) throws Exception {
        check(find(d, "GoodIdentificationType", "goodIdentificationTypeId", typeId) != null,
                "GoodIdentificationType " + typeId + " not configured");
        GenericValue existing = EntityQuery.use(d).from("GoodIdentification")
                .where("productId", productId, "goodIdentificationTypeId", typeId).queryOne();
        if (existing == null) {
            call(dispatcher, "createGoodIdentification", attrs("productId", productId,
                    "goodIdentificationTypeId", typeId, "idValue", value, "userLogin", user));
        } else if (!value.equals(existing.getString("idValue"))) {
            // Do not silently reassign a live barcode. Requires a separate supervised change request.
            throw new IllegalArgumentException("Existing " + typeId + " differs for " + productId);
        }
    }

    /** Stores a tenant-local image in OFBiz's existing IMAGE_OBJECT entity pair. */
    private static void saveImage(Delegator d, String productId, byte[] image, String mime) throws Exception {
        String id = imageId(productId);
        GenericValue resource = find(d, "DataResource", "dataResourceId", id);
        if (resource == null) {
            resource = d.makeValue("DataResource", attrs("dataResourceId", id, "dataResourceTypeId", "IMAGE_OBJECT",
                    "dataResourceName", productId + " primary POS image", "mimeTypeId", mime, "isPublic", "N"));
            resource.create();
        } else {
            check("IMAGE_OBJECT".equals(resource.getString("dataResourceTypeId")), "Image resource ID collision");
            resource.set("mimeTypeId", mime);
            resource.store();
        }
        GenericValue blob = find(d, "ImageDataResource", "dataResourceId", id);
        if (blob == null) {
            blob = d.makeValue("ImageDataResource", attrs("dataResourceId", id));
            blob.setBytes("imageData", image);
            blob.create();
        } else {
            blob.setBytes("imageData", image);
            blob.store();
        }
        // The caller associates the resource using native Content and ProductContent
        // services, then sets the Product image URL fields. No duplicate JPEG upload.
    }

    /** Native OFBiz Content/ContentRole SECA + ProductContent, reuse existing blob. */
    private static String ensureImageContent(Delegator d, LocalDispatcher dispatcher,
            GenericValue user, String productId, String mime) throws Exception {
        String resourceId = imageId(productId);
        String contentId = null;
        for (GenericValue c : EntityQuery.use(d).from("Content")
                .where("dataResourceId", resourceId).queryList()) {
            check("DOCUMENT".equals(c.getString("contentTypeId")),
                    "Existing image Content is not a DOCUMENT");
            check(contentId == null, "Multiple Content records reference the imported image");
            contentId = c.getString("contentId");
        }
        if (contentId == null) {
            // createContentFromDataResource delegates to createContent and its
            // OWNER ContentRole SECA. Permission checks are not bypassed.
            Map<String, Object> result = call(dispatcher, "createContentFromDataResource", attrs(
                    "dataResourceId", resourceId, "contentTypeId", "DOCUMENT",
                    "contentName", productId + " primary POS image",
                    "mimeTypeId", mime, "statusId", "CTNT_IN_PROGRESS",
                    "userLogin", user));
            Object generated = result.get("contentId");
            check(generated != null && !generated.toString().isBlank(),
                    "createContentFromDataResource did not return contentId");
            contentId = generated.toString();
        }
        // Preserve unrelated associations. Do not create a second primary image
        // if some other Content is already assigned (even if currently expired).
        boolean linked = false;
        for (GenericValue pc : EntityQuery.use(d).from("ProductContent")
                .where("productId", productId,
                        "productContentTypeId", "ORIGINAL_IMAGE_URL").queryList()) {
            check(contentId.equals(pc.getString("contentId")),
                    "Product already has a different ORIGINAL_IMAGE_URL Content");
            linked = true;
        }
        if (!linked) {
            call(dispatcher, "createProductContent", attrs("productId", productId,
                    "contentId", contentId, "productContentTypeId", "ORIGINAL_IMAGE_URL",
                    "fromDate", Timestamp.from(Instant.now()), "userLogin", user));
        }
        return contentId;
    }

    /** Populate all five fields as proven by the native Catalog UI; keep existing values safe. */
    private static void ensureProductImageUrls(Delegator d, LocalDispatcher dispatcher,
            GenericValue user, String productId, String url) throws Exception {
        GenericValue product = find(d, "Product", "productId", productId);
        check(product != null, "Product not found after image creation");
        String[] fields = {"smallImageUrl", "mediumImageUrl", "largeImageUrl",
                "detailImageUrl", "originalImageUrl"};
        Map<String, Object> update = attrs("productId", productId, "userLogin", user);
        for (String field : fields) {
            String previous = product.getString(field);
            check(previous == null || previous.isBlank() || previous.equals(url),
                    "Refusing to replace existing " + field + " for " + productId);
            if (previous == null || previous.isBlank()) {
                update.put(field, url);
            }
        }
        if (update.size() > 2) {
            call(dispatcher, "updateProduct", update);
        }
    }

    /** Returned only through the authenticated Quarkus facade, never as a public file URL. */
    public static Map<String, Object> getImage(DispatchContext ctx, Map<String, Object> p) {
        try {
            Delegator d = ctx.getDelegator();
            GenericValue user = (GenericValue) p.get("userLogin");
            String tenantId = string(p, "tenantId");
            check(user != null && tenantId.matches("[A-Za-z0-9_-]{1,40}")
                    && d.getDelegatorName().endsWith("#" + tenantId), "Tenant login/delegator mismatch");
            check(ctx.getSecurity().hasEntityPermission("CATALOG", "_VIEW", user), "CATALOG_VIEW required");
            String id = string(p, "productId");
            check(PRODUCT_ID.matcher(id).matches(), "Invalid product ID (1-20 safe characters)");
            String resourceId = imageId(id);
            GenericValue dr = find(d, "DataResource", "dataResourceId", resourceId);
            GenericValue image = find(d, "ImageDataResource", "dataResourceId", resourceId);
            check(dr != null && image != null, "Image not found");
            byte[] data = image.getBytes("imageData");
            check(data != null && data.length <= MAX_IMAGE_BYTES, "Image invalid or too large");
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("imageBase64", Base64.getEncoder().encodeToString(data));
            out.put("mimeType", dr.getString("mimeTypeId"));
            return out;
        } catch (Exception e) {
            return ServiceUtil.returnError("Product image lookup failed: " + e.getMessage());
        }
    }
}
