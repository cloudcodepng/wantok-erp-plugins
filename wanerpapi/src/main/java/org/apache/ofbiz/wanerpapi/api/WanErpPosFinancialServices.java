package org.apache.ofbiz.wanerpapi.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.LinkedHashSet;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.order.shoppingcart.CheckOutHelper;
import org.apache.ofbiz.order.shoppingcart.ShoppingCart;
import org.apache.ofbiz.order.shoppingcart.ShoppingCartItem;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Native OFBiz POS financial orchestration. Never exposed as a separate service:
 * WanErpPosServices checks bridge authentication, signed actor, permissions and scope.
 * Native OFBiz calls run as that verified human actor, not as the transport bridge.
 * No external EFTPOS approvals or refunds are fabricated by this implementation.
 */
public final class WanErpPosFinancialServices {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.:-]{1,56}");
    private static final Pattern BANK_TERMINAL = Pattern.compile("[A-Za-z0-9_.:-]{3,56}");
    private static final Pattern BANK_REFERENCE = Pattern.compile("[A-Za-z0-9-]{6,40}");
    private static final Pattern APPROVAL = Pattern.compile("[A-Za-z0-9-]{2,20}");
    private static final Set<String> CARD_FIELDS = Set.of("type", "amountReceived", "bankTerminalId",
            "rrn", "approvalCode", "receiptDate", "receiptStatus", "confirmedApproved");

    /** Bank receipt attested by a cashier, NOT a bank/PSP-verified capture. No cardholder data. */
    private static final class CardReceipt {
        private final String bankTerminalId;
        private final String rrn;
        private final String approvalCode;
        private final java.sql.Date receiptDate;
        private final BigDecimal amount;

        private CardReceipt(String bankTerminalId, String rrn, String approvalCode,
                java.sql.Date receiptDate, BigDecimal amount) {
            this.bankTerminalId = bankTerminalId;
            this.rrn = rrn;
            this.approvalCode = approvalCode;
            this.receiptDate = receiptDate;
            this.amount = amount;
        }

        private String fingerprintPart() {
            return bankTerminalId + "|" + rrn + "|" + approvalCode + "|" + receiptDate
                    + "|" + amount.toPlainString();
        }
    }

    private static String cardField(Map<?, ?> input, String field, Pattern pattern) {
        Object value = input.get(field);
        if (!(value instanceof String) || !pattern.matcher(((String) value).trim()).matches()) {
            throw new IllegalArgumentException("Invalid EFTPOS receipt " + field);
        }
        return ((String) value).trim().toUpperCase(Locale.ROOT);
    }

    /**
     * Standalone terminal mode: the cashier checks an APPROVED bank-printed receipt.
     * Terminal ID must match the bank device registered for this POS lane.
     * Neither the receipt nor these assertions provide independent bank verification.
     */
    private static CardReceipt cardReceipt(Delegator d, Object input, String posTerminalId) throws Exception {
        if (!(input instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Standalone EFTPOS receipt details are required");
        }
        Map<?, ?> details = (Map<?, ?>) input;
        if (!CARD_FIELDS.containsAll(details.keySet())) {
            throw new IllegalArgumentException("Unsupported EFTPOS field: do not submit PIN, PAN or CVV");
        }
        if (!"EFTPOS".equals(details.get("type")) || !"APPROVED".equals(details.get("receiptStatus"))
                || !Boolean.TRUE.equals(details.get("confirmedApproved"))) {
            throw new IllegalArgumentException("Cashier must inspect an APPROVED EFTPOS terminal receipt");
        }
        String terminal = cardField(details, "bankTerminalId", BANK_TERMINAL);
        String reference = cardField(details, "rrn", BANK_REFERENCE);
        String approval = cardField(details, "approvalCode", APPROVAL);
        Object dateValue = details.get("receiptDate");
        LocalDate date;
        try {
            if (!(dateValue instanceof String) || !dateValue.toString().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                throw new IllegalArgumentException("Invalid receipt date");
            }
            date = LocalDate.parse((String) dateValue);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid receipt date", e);
        }
        if (Math.abs(ChronoUnit.DAYS.between(LocalDate.now(ZoneOffset.UTC), date)) > 1) {
            throw new IllegalArgumentException("EFTPOS receipt date outside permitted 1-day window");
        }
        BigDecimal paid = amount(details.get("amountReceived"));
        if (paid.signum() <= 0) {
            throw new IllegalArgumentException("Approved EFTPOS amount must exceed zero");
        }
        GenericValue device = EntityQuery.use(d).from("WanPosTerminalDevice")
                .where("terminalId", posTerminalId, "deviceType", "EFTPOS").queryOne();
        if (device == null || !"STANDALONE".equals(device.getString("connectionType"))
                || !terminal.equalsIgnoreCase(device.getString("portName"))) {
            throw new IllegalArgumentException("Bank terminal ID is not registered for this POS lane");
        }
        return new CardReceipt(terminal, reference, approval, java.sql.Date.valueOf(date), paid);
    }

    private static void recordCard(GenericValue row, CardReceipt receipt) {
        row.set("eftposBankTerminalId", receipt.bankTerminalId);
        row.set("eftposReference", receipt.rrn);
        row.set("eftposApprovalCode", receipt.approvalCode);
        row.set("eftposReceiptDate", receipt.receiptDate);
        row.set("eftposReceiptAmount", receipt.amount);
        row.set("eftposEvidenceStatus", "CASHIER_ATTESTED");
        row.set("eftposReconciliationStatus", "PENDING");
    }

    private WanErpPosFinancialServices() {
    }

    private static String key(Map<String, Object> ctx, String name) {
        Object value = ctx.get(name);
        String text = value == null ? "" : value.toString();
        if (!ID.matcher(text).matches()) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return text;
    }

    private static BigDecimal amount(Object input) {
        if (input == null) {
            throw new IllegalArgumentException("Missing monetary amount");
        }
        BigDecimal n = new BigDecimal(input.toString());
        if (n.scale() > 2 || n.signum() < 0) {
            throw new IllegalArgumentException("Invalid monetary amount");
        }
        return n.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static BigDecimal quantity(Object input) {
        if (input == null) {
            throw new IllegalArgumentException("Missing quantity");
        }
        BigDecimal n = new BigDecimal(input.toString());
        if (n.signum() <= 0 || n.scale() > 3 || n.compareTo(new BigDecimal("100000")) > 0) {
            throw new IllegalArgumentException("Invalid quantity");
        }
        return n;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    private static Map<String, Object> run(DispatchContext dc, String name, Map<String, Object> args)
            throws Exception {
        Map<String, Object> response = dc.getDispatcher().runSync(name, args);
        if (response == null || !ServiceUtil.isSuccess(response)) {
            throw new IllegalStateException("Native OFBiz service failed: " + name);
        }
        return response;
    }

    private static GenericValue find(Delegator d, String entity, String pk, String value) throws Exception {
        return EntityQuery.use(d).from(entity).where(pk, value).queryOne();
    }

    /**
     * Called ONLY by the return-received OFBiz SECAs. Ordinary OFBiz returns
     * and POS card returns retain their original native refund handling.
     * A POS cash return has exactly one refund owner: wanerpPosCreateReturn.
     * The POS row is persisted with its native returnId before RETURN_ACCEPTED.
     */
    public static Map<String, Object> processNativeRefundUnlessPosCash(
            DispatchContext dc, Map<String, Object> ctx) {
        try {
            String returnId = (String) ctx.get("returnId");
            if (returnId == null || returnId.isBlank()) {
                return ServiceUtil.returnError("Missing native returnId for refund ECA");
            }
            List<GenericValue> rows = EntityQuery.use(dc.getDelegator())
                    .from("WanPosReturnRequest").where("returnId", returnId).queryList();
            if (rows.size() > 1) {
                return ServiceUtil.returnError("Ambiguous POS refund owner");
            }
            if (rows.size() == 1 && ("CASH".equals(rows.get(0).getString("refundTenderType"))
                    || "V396".equals(rows.get(0).getString("refundTenderType")))) {
                if (!"PROCESSING".equals(rows.get(0).getString("statusId"))
                        && !"COMPLETED".equals(rows.get(0).getString("statusId"))) {
                    return ServiceUtil.returnError("Invalid POS refund state for native ECA");
                }
                return ServiceUtil.returnSuccess();
            }
            // Preserve the original native handler for all non-POS and card returns.
            return dc.getDispatcher().runSync("processRefundOnlyReturn", new HashMap<>(ctx));
        } catch (Exception e) {
            return ServiceUtil.returnError("Native refund guard unavailable; no refund committed");
        }
    }

    /**
     * Reuse the source cash-sale journal's debit account as the cash-refund
     * credit account. Refuse ambiguous, unposted or non-asset source entries.
     * This avoids hard-coded SHONG account IDs and keeps lanes tenant-scoped.
     */
    private static String originalCashAccount(Delegator d, GenericValue sale, String payee,
            String currency) throws Exception {
        GenericValue paid = find(d, "Payment", "paymentId", sale.getString("paymentId"));
        if (paid == null || !"CASH".equals(paid.getString("paymentMethodTypeId"))
                || !currency.equals(paid.getString("currencyUomId"))
                || !payee.equals(paid.getString("partyIdTo"))) {
            throw new IllegalStateException("Original cash sale payment/currency mismatch");
        }
        List<GenericValue> journals = EntityQuery.use(d).from("AcctgTrans")
                .where("paymentId", paid.getString("paymentId"),
                        "acctgTransTypeId", "INCOMING_PAYMENT").queryList();
        if (journals.size() != 1 || !"Y".equals(journals.get(0).getString("isPosted"))) {
            throw new IllegalStateException("Expected one posted cash receipt journal");
        }
        List<GenericValue> entries = EntityQuery.use(d).from("AcctgTransEntry")
                .where("acctgTransId", journals.get(0).getString("acctgTransId"),
                        "debitCreditFlag", "D").queryList();
        if (entries.size() != 1 || entries.get(0).getString("glAccountId") == null
                || entries.get(0).getBigDecimal("amount") == null
                || entries.get(0).getBigDecimal("amount").compareTo(paid.getBigDecimal("amount")) != 0) {
            throw new IllegalStateException("Original cash account is missing or ambiguous");
        }
        String id = entries.get(0).getString("glAccountId");
        GenericValue account = find(d, "GlAccount", "glAccountId", id);
        if (account == null || !"CURRENT_ASSET".equals(account.getString("glAccountClassId"))) {
            throw new IllegalStateException("Original cash GL account is not a current asset");
        }
        return id;
    }

    private static Map<String, Object> result(String key, Object value) {
        Map<String, Object> out = ServiceUtil.returnSuccess();
        out.put(key, value);
        return out;
    }

    private static String fingerprint(String input) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static Map<String, Object> shiftData(GenericValue shift) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String f : new String[] {"shiftId", "terminalId", "productStoreId", "facilityId", "cashierUserLoginId",
                "statusId", "openedAt", "closedAt", "closedByUserLoginId",
                "openingFloat", "closingCashCount", "expectedCash", "cashVariance"}) {
            result.put(f, shift.get(f));
        }
        return result;
    }

    /**
     * PostgreSQL partial UNIQUE indexes must protect OPEN terminal and cashier identities.
     * The read checks below provide friendly messages; indexes close the concurrency race.
     * Existing same-cashier/same-lane OPEN shifts are resumed without modifying the float.
     */
    public static Map<String, Object> openShift(DispatchContext dc, Map<String, Object> ctx,
            GenericValue actor, GenericValue terminal) throws Exception {
        Delegator d = dc.getDelegator();
        BigDecimal opening = amount(ctx.get("openingFloat"));
        String cashier = actor.getString("userLoginId");
        String terminalId = terminal.getString("terminalId");
        for (GenericValue old : EntityQuery.use(d).from("WanPosShift")
                .where("terminalId", terminalId, "statusId", "OPEN").queryList()) {
            if (cashier.equals(old.getString("cashierUserLoginId"))) {
                // A shift is permanently bound to the originally recorded store,
                // facility and physical terminal. Never silently reassign it.
                if (!terminalId.equals(old.getString("terminalId"))
                        || !terminal.getString("productStoreId").equals(old.getString("productStoreId"))
                        || !terminal.getString("facilityId").equals(old.getString("facilityId"))) {
                    throw new IllegalArgumentException("Existing OPEN shift location binding mismatch");
                }
                return result("shift", shiftData(old));
            }
            throw new IllegalArgumentException("This terminal already has an OPEN cashier shift. Close it before opening another.");
        }
        for (GenericValue old : EntityQuery.use(d).from("WanPosShift")
                .where("cashierUserLoginId", cashier, "statusId", "OPEN").queryList()) {
            throw new IllegalArgumentException("You already have an OPEN shift on another terminal. Close that shift before starting a new one.");
        }
        GenericValue s = d.makeValue("WanPosShift");
        s.set("shiftId", d.getNextSeqId("WanPosShift"));
        s.set("terminalId", terminal.getString("terminalId"));
        s.set("productStoreId", terminal.getString("productStoreId"));
        s.set("facilityId", terminal.getString("facilityId"));
        s.set("cashierUserLoginId", actor.getString("userLoginId"));
        s.set("openedAt", new Timestamp(System.currentTimeMillis()));
        s.set("statusId", "OPEN");
        s.set("openingFloat", opening);
        d.create(s);
        WanErpPosServices.audit(dc, actor, "OPEN_SHIFT", s.getString("productStoreId"),
                s.getString("facilityId"), s.getString("terminalId"), s.getString("shiftId"),
                s.getString("shiftId"), key(ctx, "requestId"), "SUCCESS");
        return result("shift", shiftData(s));
    }

    public static Map<String, Object> closeShift(DispatchContext dc, Map<String, Object> ctx,
            GenericValue actor) throws Exception {
        Delegator d = dc.getDelegator();
        GenericValue s = find(d, "WanPosShift", "shiftId", key(ctx, "shiftId"));
        if (s == null || !"OPEN".equals(s.getString("statusId"))) {
            throw new IllegalArgumentException("Shift not open");
        }
        if (!actor.getString("userLoginId").equals(s.getString("cashierUserLoginId"))) {
            // The public service already requires an explicit SHIFT_CLOSE permission.
            // A manager may reconcile someone else's shift, but the store must match.
            boolean managerGrant = false;
            for (GenericValue grant : EntityQuery.use(d).from("WanPosUserStoreGrant")
                    .where("userLoginId", actor.getString("userLoginId"), "productStoreId",
                            s.getString("productStoreId"), "accessCode", "MANAGE").queryList()) {
                Timestamp now = new Timestamp(System.currentTimeMillis());
                Timestamp from = grant.getTimestamp("fromDate");
                Timestamp thru = grant.getTimestamp("thruDate");
                if ((from == null || !from.after(now)) && (thru == null || thru.after(now))
                        && (grant.getString("facilityId") == null
                        || s.getString("facilityId").equals(grant.getString("facilityId")))) {
                    managerGrant = true;
                    break;
                }
            }
            if (!managerGrant) {
                throw new IllegalArgumentException("Manager store authorization required");
            }
        }
        WanErpPosCashControls.lockShift(d, s.getString("shiftId"));
        BigDecimal expected = WanErpPosCashControls.expected(d, s);
        BigDecimal count = amount(ctx.get("closingCashCount"));
        s.set("expectedCash", expected);
        s.set("closingCashCount", count);
        s.set("cashVariance", count.subtract(expected));
        s.set("closedByUserLoginId", actor.getString("userLoginId"));
        s.set("closedAt", new Timestamp(System.currentTimeMillis()));
        s.set("statusId", "CLOSED");
        s.store();
        WanErpPosServices.audit(dc, actor, "CLOSE_SHIFT", s.getString("productStoreId"),
                s.getString("facilityId"), s.getString("terminalId"), s.getString("shiftId"),
                s.getString("shiftId"), key(ctx, "requestId"), "SUCCESS");
        return result("shift", shiftData(s));
    }

    private static List<Map<String, Object>> items(Map<String, Object> ctx) {
        Object input = ctx.get("items");
        if (!(input instanceof List<?>) || ((List<?>) input).isEmpty() || ((List<?>) input).size() > 100) {
            throw new IllegalArgumentException("Sale must contain 1 to 100 lines");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object row : (List<?>) input) {
            if (!(row instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Invalid sale item");
            }
            Map<?, ?> raw = (Map<?, ?>) row;
            String productId = String.valueOf(raw.get("productId"));
            if (!ID.matcher(productId).matches()) {
                throw new IllegalArgumentException("Invalid productId");
            }
            Map<String, Object> line = map("productId", productId, "quantity", quantity(raw.get("quantity")));
            result.add(line);
        }
        return result;
    }

    private static String requestCanonical(List<Map<String, Object>> lines, Map<?, ?> tender, String shiftId) {
        StringBuilder sb = new StringBuilder(shiftId);
        for (Map<String, Object> line : lines) {
            sb.append('|').append(line.get("productId")).append(':')
                    .append(((BigDecimal) line.get("quantity")).stripTrailingZeros().toPlainString());
        }
        sb.append('|').append(tender.get("type"));
        sb.append('|').append(tender.get("amountReceived"));
        if ("EFTPOS".equals(tender.get("type"))) {
            for (String field : new String[] {"bankTerminalId", "rrn", "approvalCode", "receiptDate",
                    "receiptStatus", "confirmedApproved"}) {
                sb.append('|').append(tender.get(field));
            }
        }
        return sb.toString();
    }

    /** Resolve the active on-premise tax jurisdiction using the selling facility's postal address. */
    private static GenericValue facilityTaxAddress(Delegator d, String facilityId) throws Exception {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        for (String purpose : List.of("PRIMARY_LOCATION", "SHIP_ORIG_LOCATION")) {
            for (GenericValue use : EntityQuery.use(d).from("FacilityContactMechPurpose")
                    .where("facilityId", facilityId, "contactMechPurposeTypeId", purpose).queryList()) {
                Timestamp from = use.getTimestamp("fromDate");
                Timestamp thru = use.getTimestamp("thruDate");
                if ((from != null && from.after(now)) || (thru != null && !thru.after(now))) {
                    continue;
                }
                String mechId = use.getString("contactMechId");
                boolean active = false;
                for (GenericValue rel : EntityQuery.use(d).from("FacilityContactMech")
                        .where("facilityId", facilityId, "contactMechId", mechId).queryList()) {
                    Timestamp relFrom = rel.getTimestamp("fromDate");
                    Timestamp relThru = rel.getTimestamp("thruDate");
                    if ((relFrom == null || !relFrom.after(now))
                            && (relThru == null || relThru.after(now))) {
                        active = true;
                        break;
                    }
                }
                if (active) {
                    GenericValue address = find(d, "PostalAddress", "contactMechId", mechId);
                    if (address != null && address.getString("countryGeoId") != null) {
                        return address;
                    }
                }
            }
        }
        throw new IllegalStateException("Active postal tax address is missing for POS facility " + facilityId);
    }

    private static String customer(Delegator d, Map<String, Object> ctx) throws Exception {
        String id = ctx.get("customerPartyId") == null
                ? d.getDelegatorName().substring(d.getDelegatorName().indexOf('#') + 1).toUpperCase(Locale.ROOT) + "_WALKIN"
                : key(ctx, "customerPartyId");
        if (find(d, "Party", "partyId", id) == null) {
            throw new IllegalArgumentException("Create tenant walk-in customer Party: " + id);
        }
        return id;
    }

    private static Map<String, Object> saleData(GenericValue sale) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String field : new String[] {"idempotencyKey", "orderId", "invoiceId", "paymentId", "receiptNumber",
                "statusId", "totalAmount", "tenderType", "cashReceived", "cashChange", "terminalId", "shiftId",
                "eftposBankTerminalId", "eftposReference", "eftposApprovalCode", "eftposReceiptDate",
                "eftposReceiptAmount", "eftposEvidenceStatus", "eftposReconciliationStatus",
                "actorUserLoginId"}) {
            m.put(field, sale.get(field));
        }
        return m;
    }

    /**
     * Reuses the same ShoppingCart setup and CheckOutHelper tax invocation as completeSale.
     * This is a current native-calculated quote, NOT a reserved price or bank guarantee.
     */
    public static Map<String, Object> quoteSale(DispatchContext dc, Map<String, Object> ctx,
            GenericValue actor, GenericValue terminal) throws Exception {
        Delegator d = dc.getDelegator();
        String storeId = terminal.getString("productStoreId");
        String facilityId = terminal.getString("facilityId");
        GenericValue store = find(d, "ProductStore", "productStoreId", storeId);
        if (store == null || !"Y".equals(store.getString("reserveInventory"))) {
            throw new IllegalArgumentException("Native stock reservation required");
        }
        String currency = store.getString("defaultCurrencyUomId");
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Store currency not configured");
        }
        String walkIn = customer(d, ctx);
        ShoppingCart cart = new ShoppingCart(d, storeId, Locale.ENGLISH, currency);
        cart.setUserLogin(actor, dc.getDispatcher());
        cart.setOrderType("SALES_ORDER");
        cart.setOrderPartyId(walkIn);
        cart.setPlacingCustomerPartyId(walkIn);
        cart.setBillToCustomerPartyId(walkIn);
        cart.setShipToCustomerPartyId(walkIn);
        cart.setEndUserCustomerPartyId(walkIn);
        cart.setFacilityId(facilityId);
        cart.setShipGroupFacilityId(0, facilityId);
        cart.setTerminalId(terminal.getString("terminalId"));
        if (find(d, "Enumeration", "enumId", "POS_SALES_CHANNEL") != null) {
            cart.setChannelType("POS_SALES_CHANNEL");
        }
        for (Map<String, Object> line : items(ctx)) {
            String productId = (String) line.get("productId");
            if (find(d, "Product", "productId", productId) == null) {
                throw new IllegalArgumentException("Unknown product");
            }
            Map<String, Object> inventory = run(dc, "getInventoryAvailableByFacility",
                    map("productId", productId, "facilityId", facilityId, "userLogin", actor));
            Object available = inventory.get("availableToPromiseTotal");
            if (!(available instanceof BigDecimal)
                    || ((BigDecimal) available).compareTo((BigDecimal) line.get("quantity")) < 0) {
                throw new IllegalArgumentException("Insufficient available inventory");
            }
            cart.addItemToEnd(productId, BigDecimal.ZERO, (BigDecimal) line.get("quantity"), BigDecimal.ZERO,
                    new HashMap<String, GenericValue>(), new HashMap<String, Object>(), null,
                    "PRODUCT_ORDER_ITEM", dc.getDispatcher(), Boolean.TRUE, Boolean.TRUE);
        }
        // Walk-in sales have no customer delivery address. Use the selling facility's jurisdiction.
        new CheckOutHelper(dc.getDispatcher(), d, cart).calcAndAddTax(facilityTaxAddress(d, facilityId));
        BigDecimal grandTotal = cart.getGrandTotal();
        if (grandTotal == null || grandTotal.signum() <= 0) {
            throw new IllegalArgumentException("Native quoted total unavailable");
        }
        Map<String, Object> quote = new LinkedHashMap<>();
        // Read the SAME native cart used to compute the quote, never recalculate
        // frontend price/tax from catalog reference values. Mixed EA and KG retain precision.
        List<Map<String, Object>> quotedLines = new ArrayList<>();
        List<BigDecimal> itemTaxes = new ArrayList<>();
        BigDecimal sumItemTax = BigDecimal.ZERO;
        for (ShoppingCartItem item : cart.items()) {
            String productId = item.getProductId();
            GenericValue product = find(d, "Product", "productId", productId);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("productId", productId);
            line.put("productName", product == null ? productId : product.getString("productName"));
            line.put("quantityUomId", product == null ? null : product.getString("quantityUomId"));
            line.put("weighted", product != null && "WT_kg".equals(product.getString("quantityUomId")));
            line.put("taxable", product == null ? null : product.getString("taxable"));
            line.put("quantity", item.getQuantity());
            // These two native prices are SELLING prices and remain authoritative.
            // Inventory unit cost is an optional read-only merchandising figure.
            line.put("nativeUnitPrice", item.getBasePrice());
            line.put("nativeDisplayUnitPrice", item.getDisplayPrice());
            try {
                Map<String, Object> valuation = WanErpPosProductRead.inventoryValuation(
                        d, productId, facilityId, currency);
                line.put("inventoryUnitCost", valuation.get("inventoryUnitCost"));
                line.put("inventoryCostMethod", valuation.get("inventoryCostMethod"));
            } catch (Exception costReadException) {
                // Optional cost metadata must not prevent a legitimate sale quote.
                line.put("inventoryCostStatus", "UNAVAILABLE");
            }
            line.put("nativeListPrice", item.getListPrice());
            line.put("nativeLineSubtotal", item.getItemSubTotal());
            // Inspect native item tax adjustments, but never proportionally invent tax.
            BigDecimal itemTax = BigDecimal.ZERO;
            List<GenericValue> adjustments = item.getAdjustments();
            if (adjustments != null) {
                for (GenericValue adjustment : adjustments) {
                    if (adjustment != null && "SALES_TAX".equals(adjustment.getString("orderAdjustmentTypeId"))
                            && adjustment.getBigDecimal("amount") != null) {
                        itemTax = itemTax.add(adjustment.getBigDecimal("amount"));
                    }
                }
            }
            itemTaxes.add(itemTax);
            sumItemTax = sumItemTax.add(itemTax);
            quotedLines.add(line);
        }
        BigDecimal nativeTax = cart.getTotalSalesTax();
        boolean taxReconciled = nativeTax != null && sumItemTax.compareTo(nativeTax) == 0;
        for (int i = 0; i < quotedLines.size(); i++) {
            if (taxReconciled) {
                quotedLines.get(i).put("nativeLineTax", itemTaxes.get(i));
            }
            quotedLines.get(i).put("lineTaxStatus", taxReconciled
                    ? "NATIVE_ITEM_ADJUSTMENTS_VERIFIED" : "TOTAL_TAX_AT_QUOTE_LEVEL");
        }
        quote.put("lines", quotedLines);
        quote.put("taxLineAllocationStatus", taxReconciled
                ? "NATIVE_ITEM_ADJUSTMENTS_VERIFIED" : "TOTAL_TAX_AT_QUOTE_LEVEL");
        quote.put("lineCount", quotedLines.size());
        quote.put("subtotalBeforeTax", cart.getSubTotal());
        quote.put("taxAmount", cart.getTotalSalesTax());
        quote.put("grandTotal", grandTotal.setScale(2, RoundingMode.HALF_UP));
        quote.put("currencyUomId", currency);
        quote.put("nativeTaxCalculationInvoked", Boolean.TRUE);
        quote.put("readyForTender", Boolean.TRUE);
        quote.put("message", "Current native OFBiz quote; amount is rechecked when posting. "
                + "Reverse terminal payment if checkout price changes.");
        return result("quote", quote);
    }

    /**
     * Approve only single-order, already AUTO-approved allocation plans created by
     * this immediate POS checkout. Native OFBiz can create approved plan ITEMS
     * while leaving the plan HEADER in CREATED; item issuance subsequently asks
     * OFBiz to complete that header and fails CREATED -> COMPLETED.
     *
     * Never approve a shared or manually allocated plan from a cash sale. This
     * deliberately fails closed rather than changing other order workflows.
     * All checks and native status services run within the outer POS transaction.
     */
    private static void approvePosAllocationPlans(DispatchContext dc, String orderId,
            Map<String, BigDecimal> reservedByItem, Map<String, String> productsByItem,
            GenericValue actor) throws Exception {
        Delegator d = dc.getDelegator();
        List<GenericValue> orderPlans = EntityQuery.use(d).from("AllocationPlanItem")
                .where("orderId", orderId).queryList();
        if (orderPlans.size() != reservedByItem.size()) {
            throw new IllegalStateException("POS: allocation item count differs from reserved order items");
        }
        Set<String> seenItems = new LinkedHashSet<>();
        Map<String, Set<String>> planItemIds = new LinkedHashMap<>();
        Map<String, String> planProducts = new HashMap<>();
        for (GenericValue planItem : orderPlans) {
            String itemId = planItem.getString("orderItemSeqId");
            String planId = planItem.getString("planId");
            String planItemId = planItem.getString("planItemSeqId");
            String productId = planItem.getString("productId");
            BigDecimal allocated = planItem.getBigDecimal("allocatedQuantity");
            BigDecimal reserved = reservedByItem.get(itemId);
            if (planId == null || planId.isBlank() || planItemId == null
                    || !seenItems.add(itemId) || reserved == null || allocated == null
                    || reserved.compareTo(allocated) != 0
                    || !productsByItem.containsKey(itemId)
                    || !productsByItem.get(itemId).equals(productId)
                    || !"AUTO".equals(planItem.getString("planMethodEnumId"))
                    || !"ALLOC_PLAN_ITEM_APRV".equals(planItem.getString("statusId"))) {
                throw new IllegalStateException("POS: allocation item is not exclusively AUTO-approved and reserved");
            }
            if (planProducts.containsKey(planId) && !productId.equals(planProducts.get(planId))) {
                throw new IllegalStateException("POS: allocation plan references multiple products");
            }
            planProducts.put(planId, productId);
            planItemIds.computeIfAbsent(planId, ignored -> new LinkedHashSet<>()).add(planItemId);
        }
        if (!seenItems.equals(reservedByItem.keySet())) {
            throw new IllegalStateException("POS: missing allocation items for reserved order");
        }
        Map<String, String> planStatuses = new LinkedHashMap<>();
        // Validate EVERY affected plan before changing ANY plan status.
        for (Map.Entry<String, Set<String>> entry : planItemIds.entrySet()) {
            String planId = entry.getKey();
            List<GenericValue> headers = EntityQuery.use(d).from("AllocationPlanHeader")
                    .where("planId", planId).queryList();
            if (headers.size() != 1) {
                throw new IllegalStateException("POS: allocation plan header missing or ambiguous");
            }
            GenericValue header = headers.get(0);
            String status = header.getString("statusId");
            if (!"SALES_ORD_ALLOCATION".equals(header.getString("planTypeId"))
                    || !planProducts.get(planId).equals(header.getString("productId"))
                    || !("ALLOC_PLAN_CREATED".equals(status) || "ALLOC_PLAN_APPROVED".equals(status))) {
                throw new IllegalStateException("POS: allocation plan type/product/status unexpected");
            }
            List<GenericValue> allItems = EntityQuery.use(d).from("AllocationPlanItem")
                    .where("planId", planId).queryList();
            if (allItems.size() != entry.getValue().size()) {
                throw new IllegalStateException("POS: allocation plan is shared or incomplete");
            }
            for (GenericValue planItem : allItems) {
                if (!orderId.equals(planItem.getString("orderId"))
                        || !entry.getValue().contains(planItem.getString("planItemSeqId"))
                        || !"AUTO".equals(planItem.getString("planMethodEnumId"))
                        || !"ALLOC_PLAN_ITEM_APRV".equals(planItem.getString("statusId"))) {
                    throw new IllegalStateException("POS: shared or unapproved allocation plan; no shipment");
                }
            }
            planStatuses.put(planId, status);
        }
        // OFBiz 24.09 changeAllocationPlanStatus fires approveAllocationPlanItems.
        // For AUTO reservations all items are already APPROVED; that ECA's
        // implementation derives facilityId/productId only from CREATED items
        // and invokes reassignInventoryReservationsByAllocationPlan with nulls.
        // This AUTO-only fast path uses the native entity-auto update service,
        // not the broken approval/re-reservation ECA. It is permitted ONLY after
        // the full plan, reservation, ownership and item-state checks above.
        // Manually allocated/shared plans still fail closed, and native issue
        // SECAs continue to finish approved plans during shipment issuance.
        for (Map.Entry<String, String> entry : planStatuses.entrySet()) {
            if ("ALLOC_PLAN_CREATED".equals(entry.getValue())) {
                if (EntityQuery.use(d).from("StatusValidChange")
                        .where("statusId", "ALLOC_PLAN_CREATED", "statusIdTo", "ALLOC_PLAN_APPROVED")
                        .queryOne() == null) {
                    throw new IllegalStateException("POS: AUTO allocation approval status transition missing");
                }
                run(dc, "updateAllocationPlanHeader", map("planId", entry.getKey(),
                        "productId", planProducts.get(entry.getKey()),
                        "statusId", "ALLOC_PLAN_APPROVED",
                        "lastModifiedByUserLogin", actor.getString("userLoginId"),
                        "userLogin", actor));
                List<GenericValue> changed = EntityQuery.use(d).from("AllocationPlanHeader")
                        .where("planId", entry.getKey()).queryList();
                if (changed.size() != 1
                        || !"ALLOC_PLAN_APPROVED".equals(changed.get(0).getString("statusId"))
                        || !planProducts.get(entry.getKey()).equals(changed.get(0).getString("productId"))) {
                    throw new IllegalStateException("POS: AUTO allocation plan approval was not persisted");
                }
            }
        }
    }

    /**
     * Fast over-the-counter fulfillment for SHONG's one-facility,
     * one-ship-group basket. Calls the SAME native reservation issuance and
     * shipment transitions as native OFBiz quick-ship, but skips shipping-package
     * construction, which a carried-away supermarket checkout does not need.
     *
     * Native issueOrderItemShipGrpInvResToShipment writes ItemIssuance,
     * InventoryItemDetail (SOH change), and releases each reservation (ATP).
     * SHIPMENT_PACKED triggers native invoice/ready SECAs. All calls execute
     * synchronously in wanerpPosCompleteSale's outer transaction.
     */
    private static Map<String, Object> issueCounterSale(DispatchContext dc, String orderId,
            String facilityId, GenericValue actor, boolean allocationEnabled) throws Exception {
        Delegator d = dc.getDelegator();
        List<GenericValue> reservations = EntityQuery.use(d).from("OrderItemShipGrpInvRes")
                .where("orderId", orderId).queryList();
        if (reservations.isEmpty()) {
            throw new IllegalStateException("POS: native order has no inventory reservations");
        }
        Set<String> shipGroups = new LinkedHashSet<>();
        Map<String, BigDecimal> expectedByItem = new HashMap<>();
        for (GenericValue reserve : reservations) {
            String group = reserve.getString("shipGroupSeqId");
            String item = reserve.getString("orderItemSeqId");
            String invId = reserve.getString("inventoryItemId");
            BigDecimal qty = reserve.getBigDecimal("quantity");
            GenericValue inventoryItem = invId == null ? null
                    : find(d, "InventoryItem", "inventoryItemId", invId);
            if (group == null || item == null || inventoryItem == null || qty == null
                    || qty.signum() <= 0 || !facilityId.equals(inventoryItem.getString("facilityId"))) {
                throw new IllegalStateException("POS: inconsistent inventory reservation; shipment cancelled");
            }
            shipGroups.add(group);
            expectedByItem.merge(item, qty, BigDecimal::add);
        }
        if (shipGroups.size() != 1) {
            throw new IllegalStateException("POS lean shipment supports exactly one ship group");
        }
        // No partial sales or unexpected unreserved order items are allowed.
        List<GenericValue> orderedItems = EntityQuery.use(d).from("OrderItem")
                .where("orderId", orderId).queryList();
        Map<String, String> productsByItem = new HashMap<>();
        int count = 0;
        for (GenericValue item : orderedItems) {
            if (item.getString("productId") != null && item.getBigDecimal("quantity") != null) {
                ++count;
                productsByItem.put(item.getString("orderItemSeqId"), item.getString("productId"));
                BigDecimal reserved = expectedByItem.get(item.getString("orderItemSeqId"));
                if (reserved == null || reserved.compareTo(item.getBigDecimal("quantity")) != 0) {
                    throw new IllegalStateException("POS: reserved quantity != ordered quantity");
                }
            }
        }
        if (count != expectedByItem.size()) {
            throw new IllegalStateException("POS: reservation/order item count mismatch");
        }
        if (allocationEnabled) {
            approvePosAllocationPlans(dc, orderId, expectedByItem, productsByItem, actor);
        }
        String group = shipGroups.iterator().next();
        Map<String, Object> created = run(dc, "createShipment", map(
                "primaryOrderId", orderId, "primaryShipGroupSeqId", group,
                "originFacilityId", facilityId, "statusId", "SHIPMENT_INPUT",
                "userLogin", actor));
        Object shipmentIdObject = created.get("shipmentId");
        if (!(shipmentIdObject instanceof String) || ((String) shipmentIdObject).isBlank()) {
            throw new IllegalStateException("POS: native shipment missing");
        }
        String shipmentId = (String) shipmentIdObject;
        GenericValue shipment = find(d, "Shipment", "shipmentId", shipmentId);
        if (shipment == null || !"SALES_SHIPMENT".equals(shipment.getString("shipmentTypeId"))
                || !facilityId.equals(shipment.getString("originFacilityId"))) {
            throw new IllegalStateException("POS: native shipment type/facility mismatch");
        }
        for (GenericValue reserve : reservations) {
            run(dc, "issueOrderItemShipGrpInvResToShipment", map(
                    "shipmentId", shipmentId, "orderId", orderId,
                    "orderItemSeqId", reserve.getString("orderItemSeqId"),
                    "shipGroupSeqId", group,
                    "inventoryItemId", reserve.getString("inventoryItemId"),
                    "quantity", reserve.getBigDecimal("quantity"), "userLogin", actor));
        }
        List<GenericValue> issuances = EntityQuery.use(d).from("ItemIssuance")
                .where("shipmentId", shipmentId).queryList();
        Map<String, BigDecimal> issuedByItem = new HashMap<>();
        for (GenericValue issuance : issuances) {
            if (!orderId.equals(issuance.getString("orderId")) || issuance.getBigDecimal("quantity") == null) {
                throw new IllegalStateException("POS: unexpected shipment issuance");
            }
            issuedByItem.merge(issuance.getString("orderItemSeqId"),
                    issuance.getBigDecimal("quantity"), BigDecimal::add);
            List<GenericValue> details = EntityQuery.use(d).from("InventoryItemDetail")
                    .where("itemIssuanceId", issuance.getString("itemIssuanceId")).queryList();
            BigDecimal sohMovement = BigDecimal.ZERO;
            for (GenericValue detail : details) {
                BigDecimal diff = detail.getBigDecimal("quantityOnHandDiff");
                if (diff != null) {
                    sohMovement = sohMovement.add(diff);
                }
            }
            if (sohMovement.compareTo(issuance.getBigDecimal("quantity").negate()) != 0) {
                throw new IllegalStateException("POS: SOH issue movement mismatch");
            }
        }
        if (expectedByItem.size() != issuedByItem.size()) {
            throw new IllegalStateException("POS: ItemIssuance item count mismatch");
        }
        for (Map.Entry<String, BigDecimal> issued : issuedByItem.entrySet()) {
            BigDecimal expected = expectedByItem.get(issued.getKey());
            if (expected == null || expected.compareTo(issued.getValue()) != 0) {
                throw new IllegalStateException("POS: ItemIssuance quantity mismatch");
            }
        }
        // Native packed transition triggers createInvoicesFromShipment and
        // setInvoicesToReadyFromShipment SECAs, followed by shipped status.
        run(dc, "updateShipment", map("shipmentId", shipmentId,
                "statusId", "SHIPMENT_PACKED", "userLogin", actor));
        run(dc, "updateShipment", map("shipmentId", shipmentId,
                "statusId", "SHIPMENT_SHIPPED", "userLogin", actor));
        shipment = find(d, "Shipment", "shipmentId", shipmentId);
        if (shipment == null || !"SHIPMENT_SHIPPED".equals(shipment.getString("statusId"))) {
            throw new IllegalStateException("POS: physical shipment not completed");
        }
        List<Map<String, String>> completed = new ArrayList<>();
        completed.add(Map.of("shipmentId", shipmentId, "facilityId", facilityId,
                "shipGroupSeqId", group));
        return map("shipmentShipGroupFacilityList", completed);
    }

    /**
     * Uses the established NEA ShoppingCart -> native order -> invoice -> payment
     * pattern, with POS-specific multi-line cart and native warehouse issuance.
     * Standalone EFTPOS is an explicitly cashier-attested RECEIPT, not PSP settlement verification.
     */
    public static Map<String, Object> completeSale(DispatchContext dc, Map<String, Object> ctx,
            GenericValue actor, GenericValue terminal) throws Exception {
        Delegator d = dc.getDelegator();
        LocalDispatcher dispatcher = dc.getDispatcher();
        String idem = key(ctx, "idempotencyKey");
        String shiftId = key(ctx, "shiftId");
        List<Map<String, Object>> lines = items(ctx);
        Object tenderObj = ctx.get("tender");
        if (!(tenderObj instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Tender object required");
        }
        Map<?, ?> tender = (Map<?, ?>) tenderObj;
        String type = String.valueOf(tender.get("type")).toUpperCase(Locale.ROOT);
        if ((!"CASH".equals(type) && !"EFTPOS".equals(type))
                || !type.equals(tender.get("type"))) {
            throw new IllegalArgumentException("Unsupported POS tender type; use CASH or EFTPOS");
        }
        if ("CASH".equals(type) && !Set.of("type", "amountReceived").containsAll(tender.keySet())) {
            throw new IllegalArgumentException("Cash tender must not include card details");
        }
        CardReceipt card = "EFTPOS".equals(type)
                ? cardReceipt(d, tender, terminal.getString("terminalId")) : null;
        BigDecimal received = card != null ? card.amount : amount(tender.get("amountReceived"));
        String finger = fingerprint(requestCanonical(lines, tender, shiftId));
        GenericValue prior = find(d, "WanPosSaleRequest", "idempotencyKey", idem);
        if (prior != null) {
            if (finger.equals(prior.getString("requestFingerprint"))
                    && "COMPLETED".equals(prior.getString("statusId"))) {
                return result("sale", saleData(prior));
            }
            throw new IllegalArgumentException("Idempotency key conflict or unfinished sale");
        }
        // v3.9.1: do not race a physical shift closure during native checkout.
        WanErpPosCashControls.lockShift(d, shiftId);
        String storeId = terminal.getString("productStoreId");
        String facilityId = terminal.getString("facilityId");
        GenericValue store = find(d, "ProductStore", "productStoreId", storeId);
        if (store == null || !"Y".equals(store.getString("reserveInventory"))) {
            throw new IllegalArgumentException("Native inventory reservation is required for POS shipping");
        }
        String payee = store.getString("payToPartyId");
        if (payee == null || find(d, "Party", "partyId", payee) == null) {
            throw new IllegalArgumentException("Store pay-to Party is missing");
        }
        String walkIn = customer(d, ctx);
        String currency = store.getString("defaultCurrencyUomId");
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Store currency must be configured");
        }
        // Insert the idempotency record before any financial writes. The primary
        // key serializes duplicate keys; a failed service transaction rolls it back.
        GenericValue sale = d.makeValue("WanPosSaleRequest");
        sale.set("idempotencyKey", idem);
        sale.set("requestFingerprint", finger);
        sale.set("terminalId", terminal.getString("terminalId"));
        sale.set("shiftId", shiftId);
        sale.set("productStoreId", storeId);
        sale.set("facilityId", facilityId);
        sale.set("actorUserLoginId", actor.getString("userLoginId"));
        sale.set("tenderType", type);
        if (card != null) {
            recordCard(sale, card);
        }
        sale.set("statusId", "PROCESSING");
        sale.set("createdAt", new Timestamp(System.currentTimeMillis()));
        d.create(sale);
        ShoppingCart cart = new ShoppingCart(d, storeId, Locale.ENGLISH, currency);
        cart.setUserLogin(actor, dispatcher);
        cart.setOrderType("SALES_ORDER");
        cart.setOrderPartyId(walkIn);
        cart.setPlacingCustomerPartyId(walkIn);
        cart.setBillToCustomerPartyId(walkIn);
        cart.setShipToCustomerPartyId(walkIn);
        cart.setEndUserCustomerPartyId(walkIn);
        cart.setFacilityId(facilityId);
        cart.setShipGroupFacilityId(0, facilityId);
        cart.setTerminalId(terminal.getString("terminalId"));
        // Native OrderHeader.externalId is an OFBiz id (20 chars), not a UUID-sized key.
        // Preserve the full key in WanPosSaleRequest for authoritative idempotency.
        cart.setExternalId("POS-" + fingerprint(idem).substring(0, 16));
        if (find(d, "Enumeration", "enumId", "POS_SALES_CHANNEL") != null) {
            cart.setChannelType("POS_SALES_CHANNEL");
        }
        for (Map<String, Object> line : lines) {
            String product = (String) line.get("productId");
            if (find(d, "Product", "productId", product) == null) {
                throw new IllegalArgumentException("Unknown product");
            }
            Map<String, Object> inv = run(dc, "getInventoryAvailableByFacility",
                    map("productId", product, "facilityId", facilityId, "userLogin", actor));
            Object atp = inv.get("availableToPromiseTotal");
            if (!(atp instanceof BigDecimal) || ((BigDecimal) atp).compareTo((BigDecimal) line.get("quantity")) < 0) {
                throw new IllegalArgumentException("Insufficient available inventory");
            }
            int cartIndex = cart.addItemToEnd(product, BigDecimal.ZERO,
                    (BigDecimal) line.get("quantity"), BigDecimal.ZERO,
                    new HashMap<String, GenericValue>(), new HashMap<String, Object>(), null,
                    "PRODUCT_ORDER_ITEM", dispatcher, Boolean.TRUE, Boolean.TRUE);
            // Native OFBiz storeOrder skips reservations when allocateInventory=Y
            // unless OrderItemAttribute.autoReserve is literally "true".
            // Only the authorized POS checkout sets this; quoteSale stays read-only.
            if ("Y".equals(store.getString("allocateInventory"))) {
                cart.findCartItem(cartIndex).setOrderItemAttribute("autoReserve", "true");
            }
        }
        CheckOutHelper checkout = new CheckOutHelper(dispatcher, d, cart);
        // Unlike the web checkout, our server-side POS flow must invoke tax
        // calculation explicitly before makeCartMap/createOrder are called.
        // Ensure the final order uses exactly the same on-premise tax jurisdiction as its quote.
        checkout.calcAndAddTax(facilityTaxAddress(d, facilityId));
        // All POS sales use the native checkout preference and counter-sale issue path.
        // NEA/KumulPay uses WanErpApiServices and is not changed here.
        BigDecimal quotedAmount = cart.getGrandTotal().setScale(2, RoundingMode.HALF_UP);
        if (quotedAmount.signum() <= 0 || (card != null && received.compareTo(quotedAmount) != 0)
                || (card == null && received.compareTo(quotedAmount) < 0)) {
            throw new IllegalArgumentException("Tender does not match native quoted sale");
        }
        // Apply only the sale total, not the amount of cash received (change is not revenue).
        // EFTPOS approval evidence remains in WanPosSaleRequest, not the cart tender.
        cart.addPaymentAmount(card == null ? "CASH" : "CREDIT_CARD",
                quotedAmount, null, null, true, true, false);
        Map<String, Object> validation = checkout.validatePaymentMethods();
        if (validation == null || !ServiceUtil.isSuccess(validation)) {
            throw new IllegalStateException("OFBiz checkout payment preference rejected");
        }
        long posOrderStart = System.nanoTime();
        Map<String, Object> orderResult = checkout.createOrder(actor);
        if (orderResult == null || !ServiceUtil.isSuccess(orderResult) || orderResult.get("orderId") == null) {
            throw new IllegalStateException("OFBiz native order checkout failed");
        }
        String orderId = (String) orderResult.get("orderId");
        GenericValue order = find(d, "OrderHeader", "orderId", orderId);
        if (order == null) {
            throw new IllegalStateException("Native order missing");
        }
        // OFBiz OrderServices.createOrder writes OrderHeader.createdBy from its userLogin.
        // Abort the entire transaction if the native creator is not the verified operator.
        if (!actor.getString("userLoginId").equals(order.getString("createdBy"))) {
            throw new IllegalStateException("Native order creator does not match the POS operator");
        }
        BigDecimal total = order == null ? null : order.getBigDecimal("grandTotal");
        if (total == null || total.signum() <= 0 || total.scale() > 2) {
            throw new IllegalArgumentException("Native order total invalid");
        }
        if (card != null && total.compareTo(received) != 0) {
            throw new IllegalArgumentException("EFTPOS receipt amount differs from native final invoice total; "
                    + "do not post, reverse the terminal transaction and re-quote");
        }
        if (card == null && total.compareTo(received) > 0) {
            throw new IllegalArgumentException("Cash received is insufficient");
        }
        BigDecimal change = card == null ? received.subtract(total) : BigDecimal.ZERO.setScale(2);
        // Take exactly ONE native receipt against the checkout CASH / EFTPOS preference
        // BEFORE any order-status / issuance SECA can call createPaymentFromOrder.
        // OFBiz's auto COMPANY_ACCOUNT / PMNT_NOT_PAID preference is NOT a customer tender.
        String paymentReference = card == null ? "POS-" + idem : "EFTPOS-" + card.rrn;
        String paymentComment = card == null ? "POS cash sale " + orderId
                : "Standalone EFTPOS cashier-attested; pending bank reconciliation; "
                    + "bankTerminal=" + card.bankTerminalId + " approval=" + card.approvalCode
                    + " date=" + card.receiptDate + " order=" + orderId;
        List<GenericValue> preferences = EntityQuery.use(d).from("OrderPaymentPreference")
                .where("orderId", orderId).queryList();
        GenericValue pref = null;
        String expectedMethod = card == null ? "CASH" : "CREDIT_CARD";
        for (GenericValue candidate : preferences) {
            if (expectedMethod.equals(candidate.getString("paymentMethodTypeId"))
                    && "PAYMENT_RECEIVED".equals(candidate.getString("statusId"))
                    && candidate.getBigDecimal("maxAmount") != null
                    && total.compareTo(candidate.getBigDecimal("maxAmount")) == 0) {
                if (pref != null) {
                    throw new IllegalStateException("POS: multiple matching native tender preferences");
                }
                pref = candidate;
            }
        }
        if (pref == null) {
            throw new IllegalStateException("POS: cash/EFTPOS native tender preference missing");
        }
        String preferenceId = pref.getString("orderPaymentPreferenceId");
        if (!EntityQuery.use(d).from("Payment").where("paymentPreferenceId", preferenceId)
                .queryList().isEmpty()) {
            throw new IllegalStateException("POS: native tender preference is already paid");
        }
        Map<String, Object> payment = run(dc, "createPaymentFromPreference", map(
                "orderPaymentPreferenceId", preferenceId,
                "paymentRefNum", paymentReference, "paymentFromId", walkIn,
                "comments", paymentComment, "userLogin", actor));
        Object nativeId = payment.get("paymentId");
        if (!(nativeId instanceof String) || ((String) nativeId).isBlank()) {
            throw new IllegalStateException("POS: native preference payment missing");
        }
        String paymentId = (String) nativeId;
        GenericValue nativePayment = find(d, "Payment", "paymentId", paymentId);
        if (nativePayment == null || !"CUSTOMER_PAYMENT".equals(nativePayment.getString("paymentTypeId"))
                || !"PMNT_RECEIVED".equals(nativePayment.getString("statusId"))
                || !preferenceId.equals(nativePayment.getString("paymentPreferenceId"))
                || !walkIn.equals(nativePayment.getString("partyIdFrom"))
                || !payee.equals(nativePayment.getString("partyIdTo"))
                || nativePayment.getBigDecimal("amount") == null
                || total.compareTo(nativePayment.getBigDecimal("amount")) != 0) {
            throw new IllegalStateException("POS: native customer receipt inconsistent");
        }
        // Now approve if required. The approval/issuance SECAs see a received
        // preference payment and must not create a second unpaid company-account payment.
        if (!"ORDER_APPROVED".equals(order.getString("statusId"))) {
            run(dc, "changeOrderStatus", map("orderId", orderId, "statusId", "ORDER_APPROVED",
                    "setItemStatus", "Y", "userLogin", actor));
            order = find(d, "OrderHeader", "orderId", orderId);
        }
        long posShipStart = System.nanoTime();
        Map<String, Object> shipping = issueCounterSale(dc, orderId, facilityId, actor,
                "Y".equals(store.getString("allocateInventory")));
        long posShipEnd = System.nanoTime();
        if (!(shipping.get("shipmentShipGroupFacilityList") instanceof List<?>)
                || ((List<?>) shipping.get("shipmentShipGroupFacilityList")).isEmpty()) {
            throw new IllegalStateException("OFBiz did not issue stock");
        }
        GenericValue billing = EntityQuery.use(d).from("OrderItemBilling")
                .where("orderId", orderId).queryFirst();
        String invoiceId;
        if (billing == null) {
            Map<String, Object> invoiced = run(dc, "createInvoiceForOrderAllItems",
                    map("orderId", orderId, "userLogin", actor));
            invoiceId = String.valueOf(invoiced.get("invoiceId"));
        } else {
            invoiceId = billing.getString("invoiceId");
        }
        if (invoiceId == null || "null".equals(invoiceId)) {
            throw new IllegalStateException("Invoice not created");
        }
        GenericValue salesInvoice = find(d, "Invoice", "invoiceId", invoiceId);
        BigDecimal billedTotal = salesInvoice == null ? null
                : org.apache.ofbiz.accounting.invoice.InvoiceWorker.getInvoiceTotal(salesInvoice);
        if (billedTotal == null || billedTotal.setScale(2, RoundingMode.HALF_UP).compareTo(total) != 0) {
            throw new IllegalStateException("Invoice and order totals do not match");
        }
        // The invoice can already be PAID if shipment invoicing auto-applied the
        // received tender. Otherwise it must be READY before we apply it.
        GenericValue beforePayment = find(d, "Invoice", "invoiceId", invoiceId);
        if (beforePayment == null || !("INVOICE_READY".equals(beforePayment.getString("statusId"))
                || "INVOICE_PAID".equals(beforePayment.getString("statusId")))) {
            throw new IllegalStateException("POS: shipped invoice not ready/paid; actualStatus="
                    + (beforePayment == null ? "MISSING" : beforePayment.getString("statusId")));
        }
        // Do not accept an accidental PMNT_NOT_PAID / COMPANY_ACCOUNT payment
        // created by createPaymentFromOrder. It would be an extra financial record.
        for (GenericValue orderPref : EntityQuery.use(d).from("OrderPaymentPreference")
                .where("orderId", orderId).queryList()) {
            if (!preferenceId.equals(orderPref.getString("orderPaymentPreferenceId"))
                    && !EntityQuery.use(d).from("Payment")
                            .where("paymentPreferenceId", orderPref.getString("orderPaymentPreferenceId"))
                            .queryList().isEmpty()) {
                throw new IllegalStateException("POS: extra native payment from order-status ECA");
            }
        }
        List<GenericValue> applications = EntityQuery.use(d).from("PaymentApplication")
                .where("paymentId", paymentId).queryList();
        if (applications.isEmpty() && "INVOICE_READY".equals(beforePayment.getString("statusId"))) {
            run(dc, "createPaymentApplication", map("paymentId", paymentId,
                    "invoiceId", invoiceId, "amountApplied", total, "userLogin", actor));
            applications = EntityQuery.use(d).from("PaymentApplication")
                    .where("paymentId", paymentId).queryList();
        }
        if (applications.size() != 1
                || !invoiceId.equals(applications.get(0).getString("invoiceId"))
                || applications.get(0).getBigDecimal("amountApplied") == null
                || total.compareTo(applications.get(0).getBigDecimal("amountApplied")) != 0) {
            throw new IllegalStateException("POS: native payment application is duplicated or mismatched");
        }
        run(dc, "checkPaymentInvoices", map("paymentId", paymentId, "userLogin", actor));
        GenericValue invoice = find(d, "Invoice", "invoiceId", invoiceId);
        if (invoice == null || !"INVOICE_PAID".equals(invoice.getString("statusId"))) {
            throw new IllegalStateException("Invoice remains unpaid");
        }
        // Timings exclude client network; inventory issuance completes before returning success.
        org.apache.ofbiz.base.util.Debug.logInfo("SHONG native POS checkout timings ms: "
                + "orderAndTender=" + ((posShipStart - posOrderStart) / 1_000_000L)
                + ", inventoryAndInvoice=" + ((posShipEnd - posShipStart) / 1_000_000L)
                + ", total=" + ((System.nanoTime() - posOrderStart) / 1_000_000L),
                WanErpPosFinancialServices.class.getName());
        sale.set("orderId", orderId);
        sale.set("invoiceId", invoiceId);
        sale.set("paymentId", paymentId);
        sale.set("receiptNumber", "POS-" + orderId);
        sale.set("totalAmount", total);
        if (card == null) {
            sale.set("cashReceived", received);
            sale.set("cashChange", change);
        }
        sale.set("statusId", "COMPLETED");
        sale.set("updatedAt", new Timestamp(System.currentTimeMillis()));
        sale.store();
        WanErpPosCashControls.sale(d, sale);
        WanErpPosServices.audit(dc, actor, "CASH".equals(type) ? "CASH_SALE" : "EFTPOS_SALE",
                storeId, facilityId, terminal.getString("terminalId"), shiftId,
                orderId, key(ctx, "requestId"), "SUCCESS");
        return result("sale", saleData(sale));
    }

    public static Map<String, Object> voidSale(DispatchContext dc, Map<String, Object> ctx,
            GenericValue actor) throws Exception {
        Delegator d = dc.getDelegator();
        String idempotencyKey = key(ctx, "idempotencyKey");
        GenericValue sale = find(d, "WanPosSaleRequest", "idempotencyKey", idempotencyKey);
        if (sale == null) {
            throw new IllegalArgumentException("Sale not found");
        }
        List<GenericValue> orderItems = EntityQuery.use(d).from("OrderItem")
                .where("orderId", sale.getString("orderId")).queryList();
        List<Map<String, Object>> lines = new ArrayList<>();
        for (GenericValue item : orderItems) {
            if (item.getString("productId") != null && item.getBigDecimal("quantity") != null) {
                lines.add(map("orderItemSeqId", item.getString("orderItemSeqId"),
                        "quantity", item.getBigDecimal("quantity").stripTrailingZeros()));
            }
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("No returnable lines");
        }
        Map<String, Object> inputs = new HashMap<>(ctx);
        inputs.put("originalIdempotencyKey", idempotencyKey);
        inputs.put("returnRequestKey", "VOID-" + fingerprint(idempotencyKey).substring(0, 40));
        inputs.put("items", lines);
        inputs.put("approvalOperation", "VOID");
        Map<String, Object> returned = createReturn(dc, inputs, actor);
        sale.set("statusId", "VOIDED");
        sale.set("updatedAt", new Timestamp(System.currentTimeMillis()));
        sale.store();
        WanErpPosServices.audit(dc, actor, "VOID_SALE", sale.getString("productStoreId"),
                sale.getString("facilityId"), sale.getString("terminalId"), sale.getString("shiftId"),
                sale.getString("orderId"), key(ctx, "requestId"), "SUCCESS");
        Map<String, Object> out = result("void", returned.get("returnResult"));
        return out;
    }

    private static Map<String, Object> returnData(GenericValue ret) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (String field : new String[] {"returnRequestKey", "originalSaleKey", "returnId", "creditInvoiceId",
                "refundPaymentId", "totalRefund", "statusId", "refundTenderType",
                "eftposBankTerminalId", "eftposReference", "eftposApprovalCode", "eftposReceiptDate",
                "eftposReceiptAmount", "eftposEvidenceStatus", "eftposReconciliationStatus",
                "actorUserLoginId", "approvedByUserLoginId", "shiftId"}) {
            data.put(field, ret.get(field));
        }
        return data;
    }

    public static Map<String, Object> createReturn(DispatchContext dc, Map<String, Object> ctx,
            GenericValue actor) throws Exception {
        Delegator d = dc.getDelegator();
        String originalKey = key(ctx, "originalIdempotencyKey");
        if (!WanErpPosServices.lockOriginalSaleForReturn(d, originalKey)) {
            throw new IllegalArgumentException("Completed original sale required");
        }
        GenericValue sale = EntityQuery.use(d).from("WanPosSaleRequest")
                .where("idempotencyKey", originalKey).queryOne();
        if (sale == null || !("COMPLETED".equals(sale.getString("statusId"))
                || "VOIDED".equals(sale.getString("statusId")))) {
            throw new IllegalArgumentException("Completed original sale required");
        }
        boolean cardSale = "EFTPOS".equals(sale.getString("tenderType"));
        if (!cardSale && !"CASH".equals(sale.getString("tenderType"))) {
            throw new IllegalArgumentException("Unsupported original tender");
        }
        if (!cardSale && ctx.get("refundTender") != null) {
            throw new IllegalArgumentException("Cash sale refunds may not use a card receipt");
        }
        String orderId = sale.getString("orderId");
        if (orderId == null) {
            throw new IllegalArgumentException("Original order missing");
        }
        Object rawItems = ctx.get("items");
        if (!(rawItems instanceof List<?>) || ((List<?>) rawItems).isEmpty()) {
            throw new IllegalArgumentException("Return lines required");
        }
        String reason = ctx.get("reason") == null ? "" : ctx.get("reason").toString();
        if (reason.isBlank() || reason.length() > 255) {
            throw new IllegalArgumentException("Return reason required");
        }
        StringBuilder payload = new StringBuilder(originalKey);
        List<Map<String, Object>> checked = new ArrayList<>();
        for (Object obj : (List<?>) rawItems) {
            if (!(obj instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Invalid return line");
            }
            Map<?, ?> item = (Map<?, ?>) obj;
            String seqId = String.valueOf(item.get("orderItemSeqId"));
            if (!ID.matcher(seqId).matches()) {
                throw new IllegalArgumentException("orderItemSeqId required");
            }
            BigDecimal qty = quantity(item.get("quantity"));
            payload.append('|').append(seqId).append(':').append(qty.stripTrailingZeros().toPlainString());
            checked.add(map("orderItemSeqId", seqId, "quantity", qty));
        }
        if (cardSale) {
            Object input = ctx.get("refundTender");
            if (!(input instanceof Map<?, ?>)) {
                throw new IllegalArgumentException("Approved standalone terminal refund receipt is required");
            }
            Map<?, ?> proof = (Map<?, ?>) input;
            for (String field : new String[] {"bankTerminalId", "rrn", "approvalCode", "receiptDate",
                    "amountReceived", "receiptStatus", "confirmedApproved"}) {
                payload.append('|').append(proof.get(field));
            }
        }
        String finger = fingerprint(payload.toString());
        String returnKey = ctx.get("returnRequestKey") == null ? "R-" + finger : key(ctx, "returnRequestKey");
        if (find(d, "WanPosRefundAttemptResolution", "returnRequestKey", returnKey) != null) {
            throw new IllegalArgumentException("Resolved refund request key cannot be reused");
        }
        GenericValue earlier = find(d, "WanPosReturnRequest", "returnRequestKey", returnKey);
        if (earlier != null) {
            if (finger.equals(earlier.getString("requestFingerprint"))
                    && "COMPLETED".equals(earlier.getString("statusId"))) {
                return result("returnResult", returnData(earlier));
            }
            throw new IllegalArgumentException("Return idempotency conflict");
        }
        GenericValue store = find(d, "ProductStore", "productStoreId", sale.getString("productStoreId"));
        GenericValue order = find(d, "OrderHeader", "orderId", orderId);
        if (store == null || order == null) {
            throw new IllegalArgumentException("Original store/order missing");
        }
        GenericValue orderRole = EntityQuery.use(d).from("OrderRole")
                .where("orderId", orderId, "roleTypeId", "BILL_TO_CUSTOMER").queryFirst();
        if (orderRole == null) {
            throw new IllegalArgumentException("Original bill-to customer not found");
        }
        String fromParty = orderRole.getString("partyId");
        String payee = store.getString("payToPartyId");
        String currency = order.getString("currencyUom");
        GenericValue ret = d.makeValue("WanPosReturnRequest");
        ret.set("returnRequestKey", returnKey);
        ret.set("originalSaleKey", originalKey);
        ret.set("requestFingerprint", finger);
        // Enforce one-time manager approval, locked to this cashier's actual payout shift.
        String operation = "VOID".equals(ctx.get("approvalOperation")) ? "VOID" : "RETURN";
        GenericValue approval = WanErpPosCashControls.approvedForExecution(
                dc, ctx, actor, sale, operation, checked);
        GenericValue financialActor = WanErpPosCashControls.nativeApprover(dc, approval);
        GenericValue refundShift = EntityQuery.use(d).from("WanPosShift")
                .where("shiftId", approval.getString("shiftId")).queryOne();
        CardReceipt refundCard = cardSale
                ? cardReceipt(d, ctx.get("refundTender"), refundShift.getString("terminalId")) : null;
        if (refundCard == null && !Boolean.TRUE.equals(ctx.get("cashPayoutConfirmed"))) {
            throw new IllegalArgumentException("Cashier must confirm the planned cash refund payout");
        }
        ret.set("productStoreId", sale.getString("productStoreId"));
        ret.set("refundTenderType", refundCard == null ? "CASH" : "EFTPOS");
        if (refundCard != null) {
            recordCard(ret, refundCard);
        }
        ret.set("shiftId", refundShift.getString("shiftId"));
        ret.set("actorUserLoginId", actor.getString("userLoginId"));
        ret.set("approvedByUserLoginId", approval.getString("approvedByUserLoginId"));
        ret.set("statusId", "PROCESSING");
        ret.set("createdAt", new Timestamp(System.currentTimeMillis()));
        d.create(ret);
        // The initial GST fix supports item-linked native adjustments. Do not
        // silently omit a nonzero order-wide tax, shipping charge or discount.
        List<GenericValue> orderWideAdjustments = EntityQuery.use(d).from("OrderAdjustment")
                .where("orderId", orderId).queryList();
        for (GenericValue adjustment : orderWideAdjustments) {
            String adjustmentItemSeq = adjustment.getString("orderItemSeqId");
            if ((adjustmentItemSeq == null || "_NA_".equals(adjustmentItemSeq))
                    && adjustment.getBigDecimal("amount") != null
                    && adjustment.getBigDecimal("amount").signum() != 0) {
                throw new IllegalArgumentException("POS: order-wide adjustment return not yet supported");
            }
        }
        Map<String, Object> head = run(dc, "createReturnHeader", map(
                "returnHeaderTypeId", "CUSTOMER_RETURN", "returnTypeId", "RTN_REFUND",
                "fromPartyId", fromParty, "toPartyId", payee, "currencyUomId", currency,
                "destinationFacilityId", sale.getString("facilityId"), "needsInventoryReceive", "Y",
                "userLogin", financialActor));
        String returnId = String.valueOf(head.get("returnId"));
        if ("null".equals(returnId)) {
            throw new IllegalStateException("Return header not created");
        }
        // The native RETURN_RECEIVED ECA runs before this method finishes.
        // Link the in-flight POS return now, inside the SAME transaction,
        // so the ECA wrapper can suppress only native cash auto-refunds.
        ret.set("returnId", returnId);
        ret.store();
        for (Map<String, Object> line : checked) {
            String seqId = (String) line.get("orderItemSeqId");
            GenericValue orderItem = EntityQuery.use(d).from("OrderItem")
                    .where("orderId", orderId, "orderItemSeqId", seqId).queryOne();
            if (orderItem == null || orderItem.getString("productId") == null) {
                throw new IllegalArgumentException("Original order item unavailable");
            }
            Map<String, Object> available = run(dc, "getReturnableQuantity", map("orderItem", orderItem,
                    "userLogin", financialActor));
            Object allowed = available.get("returnableQuantity");
            if (!(allowed instanceof BigDecimal)
                    || ((BigDecimal) allowed).compareTo((BigDecimal) line.get("quantity")) < 0) {
                throw new IllegalArgumentException("Return quantity exceeds outstanding quantity");
            }
            GenericValue product = find(d, "Product", "productId", orderItem.getString("productId"));
            GenericValue itemType = product == null ? null : EntityQuery.use(d).from("ReturnItemTypeMap")
                    .where("returnItemMapKey", product.getString("productTypeId"),
                            "returnHeaderTypeId", "CUSTOMER_RETURN").queryOne();
            if (itemType == null) {
                throw new IllegalArgumentException("Missing return item type mapping");
            }
            // Control return adjustments explicitly: without this, the native invoice can
            // credit the untaxed item value while omitting the original sales tax.
            // OFBiz supports includeAdjustments=N; copy each applicable original tax
            // through its native createReturnAdjustment service instead.
            run(dc, "createReturnItem", map("returnId", returnId, "returnTypeId", "RTN_REFUND",
                    "returnReasonId", "RTN_NOT_WANT", "returnItemTypeId", itemType.getString("returnItemTypeId"),
                    "orderId", orderId, "orderItemSeqId", seqId, "productId", orderItem.getString("productId"),
                    "returnQuantity", line.get("quantity"), "returnPrice", available.get("returnablePrice"),
                    "includeAdjustments", "N", "description", reason, "userLogin", financialActor));
            List<GenericValue> nativeReturnItems = EntityQuery.use(d).from("ReturnItem")
                    .where("returnId", returnId, "orderId", orderId, "orderItemSeqId", seqId).queryList();
            if (nativeReturnItems.size() != 1) {
                throw new IllegalStateException("POS: native return item must be unique");
            }
            String returnItemSeqId = nativeReturnItems.get(0).getString("returnItemSeqId");
            if (returnItemSeqId == null) {
                throw new IllegalStateException("POS: native return item sequence missing");
            }
            List<GenericValue> adjustments = EntityQuery.use(d).from("OrderAdjustment")
                    .where("orderId", orderId, "orderItemSeqId", seqId).queryList();
            for (GenericValue adjustment : adjustments) {
                BigDecimal originalAdjustment = adjustment.getBigDecimal("amount");
                if (originalAdjustment == null) {
                    throw new IllegalStateException("POS: original order adjustment has no amount");
                }
                if (originalAdjustment.signum() == 0) {
                    continue;
                }
                // Fail closed for promotions, VAT variants, or unknown charges until
                // their return proration is explicitly supported and UAT verified.
                if (!"SALES_TAX".equals(adjustment.getString("orderAdjustmentTypeId"))) {
                    throw new IllegalArgumentException("POS: unsupported original return adjustment");
                }
                GenericValue taxMap = EntityQuery.use(d).from("ReturnItemTypeMap")
                        .where("returnHeaderTypeId", "CUSTOMER_RETURN", "returnItemMapKey", "SALES_TAX")
                        .queryOne();
                if (taxMap == null || !"RET_SALES_TAX_ADJ".equals(taxMap.getString("returnItemTypeId"))) {
                    throw new IllegalStateException("POS: native sales-tax return mapping missing");
                }
                Map<String, Object> created = run(dc, "createReturnAdjustment", map(
                        "returnId", returnId, "returnItemSeqId", returnItemSeqId,
                        "orderAdjustmentId", adjustment.getString("orderAdjustmentId"), "userLogin", financialActor));
                Object adjustmentId = created.get("returnAdjustmentId");
                GenericValue nativeTax = adjustmentId == null ? null : find(d, "ReturnAdjustment",
                        "returnAdjustmentId", String.valueOf(adjustmentId));
                if (nativeTax == null || !"RET_SALES_TAX_ADJ".equals(nativeTax.getString("returnAdjustmentTypeId"))
                        || !returnId.equals(nativeTax.getString("returnId"))
                        || !returnItemSeqId.equals(nativeTax.getString("returnItemSeqId"))) {
                    throw new IllegalStateException("POS: native return GST adjustment was not created");
                }
                BigDecimal originalQuantity = orderItem.getBigDecimal("quantity");
                if (originalQuantity == null || originalQuantity.signum() <= 0) {
                    throw new IllegalStateException("POS: original quantity invalid for GST proration");
                }
                BigDecimal expectedTax = originalAdjustment.multiply((BigDecimal) line.get("quantity"))
                        .divide(originalQuantity, 2, RoundingMode.HALF_UP);
                BigDecimal actualTax = nativeTax.getBigDecimal("amount");
                if (actualTax == null || actualTax.setScale(2, RoundingMode.HALF_UP)
                        .compareTo(expectedTax) != 0) {
                    throw new IllegalStateException("POS: native return GST amount does not reconcile");
                }
            }
        }
        run(dc, "updateReturnHeader", map("returnId", returnId, "statusId", "RETURN_ACCEPTED",
                "userLogin", financialActor));
        run(dc, "quickReceiveReturn", map("returnId", returnId, "userLogin", financialActor));
        List<GenericValue> receipts = EntityQuery.use(d).from("ShipmentReceipt")
                .where("returnId", returnId).queryList();
        if (receipts.isEmpty()) {
            throw new IllegalStateException("Native return shipment was not received");
        }
        // The native SALES_RETURN shipment-received ECA has already called
        // createInvoicesFromReturnShipment, which bills each ShipmentReceipt.
        // Calling createInvoiceFromReturn again here double-credits the customer.
        // Reuse the ONE native credit invoice and fail closed on missing/ambiguous billing.
        List<GenericValue> billings = EntityQuery.use(d).from("ReturnItemBilling")
                .where("returnId", returnId).queryList();
        Set<String> creditInvoiceIds = new LinkedHashSet<>();
        Set<String> billedReceiptIds = new LinkedHashSet<>();
        for (GenericValue billing : billings) {
            String invoiceId = billing.getString("invoiceId");
            String receiptId = billing.getString("shipmentReceiptId");
            if (invoiceId == null || receiptId == null || !billedReceiptIds.add(receiptId)) {
                throw new IllegalStateException("POS: invalid or duplicate native return billing");
            }
            creditInvoiceIds.add(invoiceId);
        }
        if (creditInvoiceIds.size() != 1 || billedReceiptIds.size() != receipts.size()) {
            throw new IllegalStateException("POS: exactly one native credit invoice required");
        }
        for (GenericValue receipt : receipts) {
            if (!billedReceiptIds.contains(receipt.getString("receiptId"))) {
                throw new IllegalStateException("POS: an inventory receipt was not invoiced");
            }
        }
        String creditId = creditInvoiceIds.iterator().next();
        GenericValue creditInvoice = find(d, "Invoice", "invoiceId", creditId);
        if (creditInvoice == null || !"CUST_RTN_INVOICE".equals(creditInvoice.getString("invoiceTypeId"))
                || !"INVOICE_READY".equals(creditInvoice.getString("statusId"))) {
            throw new IllegalStateException("POS: native credit invoice is missing or not ready");
        }
        // Native credit invoice total, not a caller-selected refund amount.
        BigDecimal refundAmount = org.apache.ofbiz.accounting.invoice.InvoiceWorker
                .getInvoiceTotal(creditInvoice).setScale(2, RoundingMode.HALF_UP);
        if (refundAmount.signum() <= 0 || refundAmount.compareTo(sale.getBigDecimal("totalAmount")) > 0) {
            throw new IllegalArgumentException("Invalid native credit invoice amount");
        }
        if (refundCard != null && refundCard.amount.compareTo(refundAmount) != 0) {
            throw new IllegalArgumentException("Terminal refund amount differs from native return credit; "
                    + "stop and reconcile the physical refund with the bank");
        }
        // A manager cannot approve Kx and have a different native credit processed.
        // This check occurs before creating the outgoing Payment; thrown exceptions
        // must roll back the surrounding OFBiz service transaction.
        if (refundAmount.compareTo(approval.getBigDecimal("approvedAmount")) != 0) {
            throw new IllegalArgumentException("Native refund amount differs from manager approval");
        }
        String paymentReference = refundCard == null ? "POSR-" + returnKey : "EFTREF-" + refundCard.rrn;
        String refundComment = refundCard == null ? "Cash refund for return " + returnId
                : "Standalone EFTPOS refund cashier-attested; pending bank reconciliation; "
                    + "bankTerminal=" + refundCard.bankTerminalId + " approval=" + refundCard.approvalCode
                    + " date=" + refundCard.receiptDate + " return=" + returnId;
        Map<String, Object> refundPayment = map("partyIdFrom", payee,
                "partyIdTo", fromParty, "amount", refundAmount, "currencyUomId", currency,
                "paymentTypeId", "CUSTOMER_REFUND", "paymentMethodTypeId",
                refundCard == null ? "CASH" : "CREDIT_CARD", "statusId", "PMNT_SENT",
                "paymentRefNum", paymentReference,
                "comments", refundComment, "userLogin", financialActor);
        if (refundCard == null) {
            // Override is a native Payment field. For POS cash refunds, the
            // v3.7 GL logic uses it ONLY on the cash-credit side (never A/R).
            refundPayment.put("overrideGlAccountId", originalCashAccount(d, sale, payee, currency));
        }
        Map<String, Object> paid = run(dc, "createPayment", refundPayment);
        String paymentId = String.valueOf(paid.get("paymentId"));
        if ("null".equals(paymentId)) {
            throw new IllegalStateException("Refund payment not recorded");
        }
        run(dc, "createPaymentApplication", map("paymentId", paymentId, "invoiceId", creditId,
                "amountApplied", refundAmount, "userLogin", financialActor));
        run(dc, "checkPaymentInvoices", map("paymentId", paymentId, "userLogin", financialActor));
        GenericValue settledCredit = find(d, "Invoice", "invoiceId", creditId);
        if (settledCredit == null || !"INVOICE_PAID".equals(settledCredit.getString("statusId"))) {
            throw new IllegalStateException("Return credit invoice was not settled");
        }
        ret.set("returnId", returnId);
        ret.set("creditInvoiceId", creditId);
        ret.set("refundPaymentId", paymentId);
        ret.set("totalRefund", refundAmount);
        ret.set("statusId", "COMPLETED");
        ret.store();
        WanErpPosCashControls.consume(dc, approval, returnKey, refundAmount);
        WanErpPosCashControls.refund(d, ret, approval);
        WanErpPosServices.audit(dc, actor, refundCard == null ? "CASH_RETURN" : "EFTPOS_RETURN",
                sale.getString("productStoreId"), sale.getString("facilityId"),
                refundShift.getString("terminalId"), refundShift.getString("shiftId"),
                returnId, key(ctx, "requestId"), "SUCCESS");
        return result("returnResult", returnData(ret));
    }
}
