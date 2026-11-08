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
public final class WanErpPosV396ReceiptServices {
    private WanErpPosV396ReceiptServices() { }

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
        List<GenericValue> storedTenders = EntityQuery.use(d).from("WanPosV396SaleTender")
                .where("idempotencyKey", sale.getString("idempotencyKey")).queryList();
        if (storedTenders.isEmpty()) return ServiceUtil.returnError("v396 tender allocations not found");
        BigDecimal totalPaid = BigDecimal.ZERO.setScale(2);
        List<Map<String, Object>> printedTenders = new ArrayList<>();
        for (GenericValue part : storedTenders) {
            BigDecimal paid = money(part.getBigDecimal("amount"));
            GenericValue nativePayment = EntityQuery.use(d).from("Payment")
                    .where("paymentId", part.getString("paymentId")).queryOne();
            if (nativePayment == null || nativePayment.getBigDecimal("amount") == null
                    || paid.compareTo(nativePayment.getBigDecimal("amount")) != 0) {
                return ServiceUtil.returnError("Native tender does not reconcile");
            }
            printedTenders.add(Map.of("type", part.getString("tenderType"), "amount", paid));
            totalPaid = totalPaid.add(paid);
        }
        BigDecimal gross = money(sale.getBigDecimal("totalAmount"));
        if (gross.signum() <= 0 || money(order.getBigDecimal("grandTotal")).compareTo(gross) != 0
                || totalPaid.compareTo(gross) != 0) {
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
        data.put("tenders", printedTenders);
        GenericValue cashout = EntityQuery.use(d).from("WanPosV396Cashout")
                .where("idempotencyKey", sale.getString("idempotencyKey")).queryOne();
        data.put("cashOutAmount", cashout == null ? BigDecimal.ZERO : cashout.getBigDecimal("cashOutAmount"));
        BigDecimal bankCharge = cashout == null ? sale.getBigDecimal("eftposReceiptAmount")
                : cashout.getBigDecimal("bankAmount");
        // A previously persisted cash-out must agree with the cashier-attested bank charge.
        // Never print a receipt that understates the actual amount charged to the bank card.
        if (cashout != null && (bankCharge == null || sale.getBigDecimal("eftposReceiptAmount") == null
                || money(bankCharge).compareTo(money(sale.getBigDecimal("eftposReceiptAmount"))) != 0)) {
            return ServiceUtil.returnError("Bank receipt charge does not match saved EFTPOS settlement evidence");
        }
        data.put("bankChargeAmount", bankCharge);
        data.put("documentType", "SALE");
        // Never disclose PAN/PIN/CVV; even bank TID/RRN are not required on POS customer receipt.
        data.put("printerName", device.getString("portName"));
        data.put("printerConnectionType", device.getString("connectionType"));
        data.put("printerDeviceName", device.getString("deviceName"));
        Map<String, Object> out = ServiceUtil.returnSuccess();
        out.put("receiptData", data);
        return out;
    }

    /** Read-only native, completed, manager-approved credit note snapshot. */
    public static Map<String, Object> returnReceipt(DispatchContext dc, GenericValue ret)
            throws Exception {
        if (!"COMPLETED".equals(ret.getString("statusId"))
                || !"V396".equals(ret.getString("refundTenderType"))) {
            return ServiceUtil.returnError("Completed v3.9.6 return required");
        }
        Delegator d = dc.getDelegator();
        GenericValue sale = EntityQuery.use(d).from("WanPosSaleRequest")
                .where("idempotencyKey", ret.getString("originalSaleKey")).queryOne();
        GenericValue originalOrder = sale == null ? null : EntityQuery.use(d).from("OrderHeader")
                .where("orderId", sale.getString("orderId")).queryOne();
        GenericValue credit = EntityQuery.use(d).from("Invoice")
                .where("invoiceId", ret.getString("creditInvoiceId")).queryOne();
        if (sale == null || originalOrder == null || credit == null
                || !"INVOICE_PAID".equals(credit.getString("statusId"))) {
            return ServiceUtil.returnError("Return native invoice not settled");
        }
        GenericValue store = EntityQuery.use(d).from("ProductStore")
                .where("productStoreId", sale.getString("productStoreId")).queryOne();
        GenericValue printer = EntityQuery.use(d).from("WanPosTerminalDevice")
                .where("terminalId", sale.getString("terminalId"), "deviceType", "PRINTER").queryOne();
        if (store == null || printer == null || printer.getString("portName") == null) {
            return ServiceUtil.returnError("Native printer not configured");
        }
        List<Map<String, Object>> refundLines = new ArrayList<>();
        BigDecimal netItems = BigDecimal.ZERO;
        for (GenericValue returned : EntityQuery.use(d).from("ReturnItem")
                .where("returnId", ret.getString("returnId")).queryList()) {
            String productId = returned.getString("productId");
            if (productId == null) continue;
            BigDecimal qty = returned.getBigDecimal("returnQuantity");
            BigDecimal price = returned.getBigDecimal("returnPrice");
            if (qty == null || price == null || qty.signum() <= 0 || price.signum() < 0) {
                return ServiceUtil.returnError("Invalid native return item");
            }
            GenericValue product = EntityQuery.use(d).from("Product")
                    .where("productId", productId).queryOne();
            String name = product == null ? productId : product.getString("productName");
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("productName", name == null ? productId : name);
            line.put("quantity", qty);
            line.put("unitPrice", money(price));
            BigDecimal value = money(qty.multiply(price));
            line.put("lineNet", value);
            refundLines.add(line);
            netItems = netItems.add(value);
        }
        if (refundLines.isEmpty()) return ServiceUtil.returnError("Credit receipt has no returnable items");
        BigDecimal tax = BigDecimal.ZERO;
        for (GenericValue adjustment : EntityQuery.use(d).from("ReturnAdjustment")
                .where("returnId", ret.getString("returnId")).queryList()) {
            if ("RET_SALES_TAX_ADJ".equals(adjustment.getString("returnAdjustmentTypeId"))) {
                tax = tax.add(money(adjustment.getBigDecimal("amount")));
            }
        }
        BigDecimal gross = money(ret.getBigDecimal("totalRefund"));
        BigDecimal net = gross.subtract(tax);
        List<GenericValue> allocations = EntityQuery.use(d).from("WanPosV396ReturnTender")
                .where("returnRequestKey", ret.getString("returnRequestKey")).queryList();
        List<Map<String, Object>> tenders = new ArrayList<>();
        BigDecimal applied = BigDecimal.ZERO;
        for (GenericValue allocation : allocations) {
            BigDecimal a = money(allocation.getBigDecimal("amount"));
            GenericValue paid = EntityQuery.use(d).from("Payment")
                    .where("paymentId", allocation.getString("refundPaymentId")).queryOne();
            if (paid == null || a.compareTo(paid.getBigDecimal("amount")) != 0) {
                return ServiceUtil.returnError("Credit refund payment mismatch");
            }
            tenders.add(Map.of("type", allocation.getString("tenderType"), "amount", a));
            applied = applied.add(a);
        }
        if (applied.compareTo(gross) != 0) {
            return ServiceUtil.returnError("Credit refund payment allocations incomplete");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentType", "REFUND");
        data.put("receiptNumber", "REF-" + ret.getString("returnId"));
        data.put("originalReceiptNumber", sale.getString("receiptNumber"));
        data.put("saleDateTimeUtc", ret.getTimestamp("createdAt").toInstant().toString());
        data.put("storeName", store.getString("storeName") == null ? "Store " + sale.getString("productStoreId") : store.getString("storeName"));
        data.put("storeId", sale.getString("productStoreId"));
        data.put("facilityId", sale.getString("facilityId"));
        data.put("terminalId", sale.getString("terminalId"));
        data.put("cashierUserLoginId", ret.getString("actorUserLoginId"));
        data.put("managerUserLoginId", ret.getString("approvedByUserLoginId"));
        data.put("items", refundLines);
        data.put("tenders", tenders);
        data.put("netAmount", net);
        data.put("taxAmount", tax);
        data.put("grossAmount", gross);
        data.put("otherNetAdjustments", money(net.subtract(netItems)));
        data.put("cashOutAmount", BigDecimal.ZERO);
        data.put("printerName", printer.getString("portName"));
        Map<String, Object> out = ServiceUtil.returnSuccess();
        out.put("receiptData", data);
        return out;
    }

}
