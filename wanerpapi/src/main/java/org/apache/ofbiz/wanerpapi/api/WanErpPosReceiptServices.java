package org.apache.ofbiz.wanerpapi.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Read-only, source-of-truth receipt snapshot. Only invoked after POS bridge/HMAC and
 * actor/store checks in WanErpPosServices.getReceiptData. Does not print or post.
 */
public final class WanErpPosReceiptServices {
    private WanErpPosReceiptServices() { }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private static Map<String, Object> line(GenericValue item, String name) {
        BigDecimal qty = item.getBigDecimal("quantity");
        BigDecimal unit = item.getBigDecimal("unitPrice");
        if (qty == null || unit == null || qty.signum() <= 0 || unit.signum() < 0) {
            throw new IllegalStateException("Persisted sale contains an invalid line");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", item.getString("productId"));
        m.put("productName", name);
        m.put("orderItemSeqId", item.getString("orderItemSeqId"));
        m.put("quantity", qty);
        m.put("unitPrice", money(unit));
        m.put("lineNet", money(qty.multiply(unit)));
        return m;
    }

    public static Map<String, Object> receipt(DispatchContext dc, GenericValue sale) throws Exception {
        // Financial statuses are authoritative; no printing while checkout is pending.
        if (!"COMPLETED".equals(sale.getString("statusId"))
                || sale.getString("orderId") == null || sale.getString("invoiceId") == null
                || sale.getString("paymentId") == null || sale.getString("receiptNumber") == null) {
            return ServiceUtil.returnError("A receipt requires a completed sale");
        }
        Delegator d = dc.getDelegator();
        GenericValue order = EntityQuery.use(d).from("OrderHeader")
                .where("orderId", sale.getString("orderId")).queryOne();
        GenericValue invoice = EntityQuery.use(d).from("Invoice")
                .where("invoiceId", sale.getString("invoiceId")).queryOne();
        GenericValue payment = EntityQuery.use(d).from("Payment")
                .where("paymentId", sale.getString("paymentId")).queryOne();
        GenericValue store = EntityQuery.use(d).from("ProductStore")
                .where("productStoreId", sale.getString("productStoreId")).queryOne();
        GenericValue device = EntityQuery.use(d).from("WanPosTerminalDevice")
                .where("terminalId", sale.getString("terminalId"), "deviceType", "PRINTER").queryOne();
        if (order == null || invoice == null || payment == null || store == null) {
            return ServiceUtil.returnError("Persisted receipt source is incomplete");
        }
        if (device == null || device.getString("portName") == null || device.getString("portName").isBlank()) {
            return ServiceUtil.returnError("Register terminal PRINTER using save-device before receipt generation");
        }
        if (!"CASH".equals(sale.getString("tenderType")) && !"EFTPOS".equals(sale.getString("tenderType"))) {
            return ServiceUtil.returnError("Unsupported receipt tender");
        }
        BigDecimal gross = money(sale.getBigDecimal("totalAmount"));
        if (gross.signum() <= 0 || money(order.getBigDecimal("grandTotal")).compareTo(gross) != 0
                || money(payment.getBigDecimal("amount")).compareTo(gross) != 0) {
            return ServiceUtil.returnError("Persisted sale/order/payment totals do not agree");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        BigDecimal itemNet = BigDecimal.ZERO;
        for (GenericValue oi : EntityQuery.use(d).from("OrderItem")
                .where("orderId", sale.getString("orderId")).queryList()) {
            String productId = oi.getString("productId");
            if (productId == null) {
                continue;
            }
            GenericValue product = EntityQuery.use(d).from("Product").where("productId", productId).queryOne();
            String productName = oi.getString("itemDescription");
            if (productName == null || productName.isBlank()) {
                productName = product == null ? productId : product.getString("productName");
            }
            if (productName == null || productName.isBlank()) {
                productName = productId;
            }
            Map<String, Object> row = line(oi, productName);
            itemNet = itemNet.add((BigDecimal) row.get("lineNet"));
            items.add(row);
        }
        if (items.isEmpty() || items.size() > 200) {
            return ServiceUtil.returnError("Receipt items missing or exceed limit");
        }
        BigDecimal tax = BigDecimal.ZERO;
        for (GenericValue adj : EntityQuery.use(d).from("OrderAdjustment")
                .where("orderId", sale.getString("orderId")).queryList()) {
            if ("SALES_TAX".equals(adj.getString("orderAdjustmentTypeId"))) {
                tax = tax.add(money(adj.getBigDecimal("amount")));
            }
        }
        tax = money(tax);
        BigDecimal net = money(gross.subtract(tax));
        BigDecimal otherNetAdjustments = money(net.subtract(itemNet));
        // The difference is shown explicitly (e.g., discount), never concealed.
        if (net.signum() < 0 || tax.signum() < 0) {
            return ServiceUtil.returnError("Invalid persisted tax totals");
        }
        Timestamp when = sale.getTimestamp("updatedAt");
        if (when == null) {
            when = order.getTimestamp("orderDate");
        }
        if (when == null) {
            return ServiceUtil.returnError("Sale timestamp missing");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("receiptNumber", sale.getString("receiptNumber"));
        data.put("saleDateTimeUtc", when.toInstant().toString());
        data.put("storeName", store.getString("storeName") == null ? "Store " + sale.getString("productStoreId") : store.getString("storeName"));
        data.put("storeId", sale.getString("productStoreId"));
        data.put("facilityId", sale.getString("facilityId"));
        data.put("terminalId", sale.getString("terminalId"));
        data.put("shiftId", sale.getString("shiftId"));
        data.put("cashierUserLoginId", sale.getString("actorUserLoginId"));
        data.put("orderId", sale.getString("orderId"));
        data.put("invoiceId", sale.getString("invoiceId"));
        data.put("paymentId", sale.getString("paymentId"));
        data.put("tenderType", sale.getString("tenderType"));
        data.put("items", items);
        data.put("netAmount", net);
        data.put("taxAmount", tax);
        data.put("otherNetAdjustments", otherNetAdjustments);
        data.put("grossAmount", gross);
        data.put("cashReceived", "CASH".equals(sale.getString("tenderType")) ? money(sale.getBigDecimal("cashReceived")) : null);
        data.put("cashChange", "CASH".equals(sale.getString("tenderType")) ? money(sale.getBigDecimal("cashChange")) : null);
        // Never disclose PAN/PIN/CVV; even bank TID/RRN are not required on POS customer receipt.
        data.put("printerName", device.getString("portName"));
        data.put("printerConnectionType", device.getString("connectionType"));
        data.put("printerDeviceName", device.getString("deviceName"));
        Map<String, Object> out = ServiceUtil.returnSuccess();
        out.put("receiptData", data);
        return out;
    }
}
