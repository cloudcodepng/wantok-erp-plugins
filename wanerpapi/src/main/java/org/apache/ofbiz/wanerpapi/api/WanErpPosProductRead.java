package org.apache.ofbiz.wanerpapi.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/** Read-only, facility-scoped ERP merchandising facts; no fake prices or balances. */
public final class WanErpPosProductRead {
    private WanErpPosProductRead() { }

    /** Caller must FIRST validate a real human actor and either TERMINAL_ADMIN or SALE scope. */
    public static Map<String, Object> describe(DispatchContext dctx, GenericValue actor,
            String productId, String storeId, String facilityId) throws Exception {
        Delegator d = dctx.getDelegator();
        GenericValue product = EntityQuery.use(d).from("Product").where("productId", productId).queryOne();
        if (product == null) {
            throw new IllegalArgumentException("Unknown product");
        }
        GenericValue store = EntityQuery.use(d).from("ProductStore")
                .where("productStoreId", storeId).queryOne();
        if (store == null) {
            throw new IllegalArgumentException("Unknown store");
        }
        String currency = store.getString("defaultCurrencyUomId");
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Store currency unavailable");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("productId", productId);
        String name = product.getString("productName");
        if (name == null || name.isBlank()) {
            name = product.getString("internalName");
        }
        out.put("productName", name == null ? productId : name);
        out.put("quantityUomId", product.getString("quantityUomId"));
        out.put("weighted", "WT_kg".equals(product.getString("quantityUomId")));
        out.put("currencyUomId", currency);
        out.put("productStoreId", storeId);
        out.put("facilityId", facilityId);
        out.put("taxable", product.getString("taxable")); // Flag, not a promise of final tax amount.
        out.put("smallImageUrl", product.getString("smallImageUrl"));
        Map<String, Object> priceArgs = new HashMap<>();
        priceArgs.put("product", product);
        priceArgs.put("productStoreId", storeId);
        priceArgs.put("quantity", BigDecimal.ONE);
        priceArgs.put("currencyUomId", currency);
        Map<String, Object> priced = dctx.getDispatcher().runSync("calculateProductPrice", priceArgs);
        if (ServiceUtil.isSuccess(priced)) {
            if (priced.get("price") instanceof BigDecimal) {
                out.put("unitPrice", priced.get("price"));
                out.put("retailPrice", priced.get("price"));
                out.put("retailPriceSource", "NATIVE_SELLING_PRICE");
            }
            if (priced.get("listPrice") instanceof BigDecimal) {
                out.put("listPrice", priced.get("listPrice"));
            }
            if (priced.get("defaultPrice") instanceof BigDecimal) {
                out.put("defaultPrice", priced.get("defaultPrice"));
            }
        }
        GenericValue productFacility = EntityQuery.use(d).from("ProductFacility")
                .where("productId", productId, "facilityId", facilityId).queryFirst();
        if (productFacility != null) {
            out.put("reorderPoint", productFacility.getBigDecimal("minimumStock"));
            out.put("reorderQuantity", productFacility.getBigDecimal("reorderQuantity"));
        }
        Map<String, Object> invArgs = new HashMap<>();
        invArgs.put("productId", productId);
        invArgs.put("facilityId", facilityId);
        invArgs.put("userLogin", actor);
        Map<String, Object> stock = dctx.getDispatcher().runSync("getInventoryAvailableByFacility", invArgs);
        if (ServiceUtil.isSuccess(stock)
                && stock.get("availableToPromiseTotal") instanceof BigDecimal
                && stock.get("quantityOnHandTotal") instanceof BigDecimal) {
            out.put("availableToPromiseTotal", stock.get("availableToPromiseTotal"));
            out.put("quantityOnHandTotal", stock.get("quantityOnHandTotal"));
            out.put("inventoryStatus", "VERIFIED");
        } else {
            out.put("inventoryStatus", "UNAVAILABLE");
        }
        // AQT is the accounting quantity, NOT units sold. Read the same
        // facility-bound InventoryItem rows that OFBiz uses for ledger facts.
        out.putAll(inventoryValuation(d, productId, facilityId, currency));
        // Retain existing explicit opening stock metadata for audit/import
        // compatibility. It is intentionally NOT shown in A07/C08 product UI.
        BigDecimal opening = BigDecimal.ZERO;
        boolean markerFound = false;
        for (GenericValue item : EntityQuery.use(d).from("InventoryItem")
                .where("productId", productId, "facilityId", facilityId).maxRows(10000).queryList()) {
            List<GenericValue> attrs = EntityQuery.use(d).from("InventoryItemAttribute")
                    .where("inventoryItemId", item.getString("inventoryItemId"),
                            "attrName", "WPI_OPENING_QTY").queryList();
            for (GenericValue attr : attrs) {
                BigDecimal marked = new BigDecimal(attr.getString("attrValue"));
                if (marked.signum() < 0) {
                    throw new IllegalStateException("Invalid recorded opening stock");
                }
                opening = opening.add(marked);
                markerFound = true;
            }
        }
        out.put("openingInventoryStatus", markerFound ? "RECORDED" : "NOT_RECORDED");
        if (markerFound) {
            out.put("openingInventoryQuantity", opening);
        }
        return out;
    }

    /**
     * Read-only, facility-specific ledger figures. AQT sums the native
     * accountingQuantityTotal for every inventory item. "Unit price" on the
     * inventory screen is unitCost (purchase/stock valuation), not the retail
     * selling price; where multiple live batches exist, present an explicitly
     * labelled QOH-weighted average rather than arbitrarily choosing one batch.
     * Missing unit cost or mismatching currency must NOT silently become zero.
     */
    public static Map<String, Object> inventoryValuation(Delegator d,
            String productId, String facilityId, String currency) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        List<GenericValue> items = EntityQuery.use(d).from("InventoryItem")
                .where("productId", productId, "facilityId", facilityId).maxRows(10001).queryList();
        // Do not return totals from a truncated result set.
        if (items.size() > 10000) {
            result.put("inventoryCostStatus", "UNAVAILABLE_TOO_MANY_ITEMS");
            result.put("accountingQuantityStatus", "UNAVAILABLE_TOO_MANY_ITEMS");
            return result;
        }
        BigDecimal accountingTotal = BigDecimal.ZERO;
        BigDecimal costedQuantity = BigDecimal.ZERO;
        BigDecimal extendedCost = BigDecimal.ZERO;
        boolean accountingComplete = !items.isEmpty();
        boolean costComplete = true;
        for (GenericValue item : items) {
            BigDecimal aq = item.getBigDecimal("accountingQuantityTotal");
            if (aq == null) {
                accountingComplete = false;
            } else {
                accountingTotal = accountingTotal.add(aq);
            }
            BigDecimal qoh = item.getBigDecimal("quantityOnHandTotal");
            if (qoh != null && qoh.signum() > 0) {
                BigDecimal unitCost = item.getBigDecimal("unitCost");
                String itemCurrency = item.getString("currencyUomId");
                if (unitCost == null || unitCost.signum() < 0
                        || itemCurrency == null || !itemCurrency.equals(currency)) {
                    costComplete = false;
                } else {
                    costedQuantity = costedQuantity.add(qoh);
                    extendedCost = extendedCost.add(unitCost.multiply(qoh));
                }
            }
        }
        if (accountingComplete) {
            result.put("accountingQuantityTotal", accountingTotal);
            result.put("accountingQuantityStatus", "VERIFIED_INVENTORY_ITEMS");
        } else {
            result.put("accountingQuantityStatus", "NOT_RECORDED");
        }
        if (costComplete && costedQuantity.signum() > 0) {
            result.put("inventoryUnitCost", extendedCost.divide(costedQuantity, 4, RoundingMode.HALF_UP));
            result.put("inventoryCostMethod", "QOH_WEIGHTED_AVERAGE");
            result.put("inventoryCostStatus", "VERIFIED_INVENTORY_ITEMS");
        } else {
            result.put("inventoryCostStatus", "NOT_RECORDED");
        }
        return result;
    }
}
