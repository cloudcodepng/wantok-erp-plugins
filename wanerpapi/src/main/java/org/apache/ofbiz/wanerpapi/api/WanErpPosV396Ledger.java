package org.apache.ofbiz.wanerpapi.api;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/** New v3.9.6 append-only cash ledger entries. The existing v3.9.1 logic is unchanged. */
public final class WanErpPosV396Ledger {
    private WanErpPosV396Ledger() { }

    private static String hash(String value) throws Exception {
        byte[] b = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static Map<String, Object> map(Object... args) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < args.length; i += 2) m.put((String) args[i], args[i + 1]);
        return m;
    }

    private static Map<String, Object> run(DispatchContext dc, String service, Map<String, Object> ctx)
            throws Exception {
        Map<String, Object> response = dc.getDispatcher().runSync(service, ctx);
        if (response == null || !ServiceUtil.isSuccess(response)) {
            throw new IllegalStateException("Native payment service declined: " + service);
        }
        return response;
    }

    private static void movement(Delegator d, String id, String shiftId, String kind,
            String reference, BigDecimal value, String actor, String approver, String description)
            throws Exception {
        if (value.signum() <= 0 || value.scale() > 2) throw new IllegalArgumentException("Invalid cash event");
        WanErpPosCashControls.lockShift(d, shiftId);
        GenericValue shift = EntityQuery.use(d).from("WanPosShift").where("shiftId", shiftId).queryOne();
        if (shift == null || !"OPEN".equals(shift.getString("statusId"))) {
            throw new IllegalArgumentException("Payout shift is no longer open");
        }
        if (EntityQuery.use(d).from("WanPosCashMovement").where("movementId", id).queryOne() != null) {
            throw new IllegalArgumentException("Duplicate immutable cash event");
        }
        GenericValue e = d.makeValue("WanPosCashMovement");
        e.set("movementId", id);
        e.set("shiftId", shiftId);
        e.set("terminalId", shift.getString("terminalId"));
        e.set("productStoreId", shift.getString("productStoreId"));
        e.set("eventAt", new Timestamp(System.currentTimeMillis()));
        e.set("movementType", kind);
        e.set("sourceReference", reference);
        e.set("amount", value);
        e.set("actorUserLoginId", actor);
        e.set("approvedByUserLoginId", approver);
        e.set("reason", description);
        d.create(e);
    }

    static void saleCash(Delegator d, GenericValue sale, BigDecimal amount) throws Exception {
        movement(d, "S-" + hash(sale.getString("idempotencyKey")), sale.getString("shiftId"),
                "CASH_SALE", sale.getString("idempotencyKey"), amount,
                sale.getString("actorUserLoginId"), null, "Mixed tender merchandise cash allocation");
    }

    static void refundCash(Delegator d, GenericValue ret, GenericValue approval, BigDecimal amount)
            throws Exception {
        if (amount.signum() > 0) {
            movement(d, "R-" + hash(ret.getString("returnRequestKey")),
                    ret.getString("shiftId"), "CASH_REFUND", ret.getString("returnRequestKey"), amount,
                    ret.getString("actorUserLoginId"), approval.getString("approvedByUserLoginId"),
                    "Manager-approved v3.9.6 mixed tender merchandise refund");
        }
    }

    /**
     * The outgoing CASH account comes from a verified, posted native cash receipt journal
     * for THIS store, payee and currency. Never hard-code a SHONG GL account ID.
     */
    static String postedStoreCashAccount(Delegator d, String storeId, String payee, String currency)
            throws Exception {
        String resolved = null;
        for (GenericValue s : EntityQuery.use(d).from("WanPosSaleRequest")
                .where("productStoreId", storeId, "tenderType", "CASH").queryList()) {
            if (!"COMPLETED".equals(s.getString("statusId"))
                    && !"VOIDED".equals(s.getString("statusId"))) continue;
            GenericValue p = EntityQuery.use(d).from("Payment")
                    .where("paymentId", s.getString("paymentId")).queryOne();
            if (p == null || !"CASH".equals(p.getString("paymentMethodTypeId"))
                    || !currency.equals(p.getString("currencyUomId"))
                    || !payee.equals(p.getString("partyIdTo"))) continue;
            List<GenericValue> postings = EntityQuery.use(d).from("AcctgTrans")
                    .where("paymentId", p.getString("paymentId"),
                            "acctgTransTypeId", "INCOMING_PAYMENT").queryList();
            if (postings.size() != 1 || !"Y".equals(postings.get(0).getString("isPosted"))) continue;
            List<GenericValue> debits = EntityQuery.use(d).from("AcctgTransEntry")
                    .where("acctgTransId", postings.get(0).getString("acctgTransId"),
                            "debitCreditFlag", "D").queryList();
            if (debits.size() != 1 || debits.get(0).getBigDecimal("amount") == null
                    || debits.get(0).getBigDecimal("amount").compareTo(p.getBigDecimal("amount")) != 0) {
                continue;
            }
            String accountId = debits.get(0).getString("glAccountId");
            GenericValue acct = EntityQuery.use(d).from("GlAccount")
                    .where("glAccountId", accountId).queryOne();
            if (acct == null || !"CURRENT_ASSET".equals(acct.getString("glAccountClassId"))) continue;
            if (resolved != null && !resolved.equals(accountId)) {
                throw new IllegalStateException("Ambiguous cash account from posted store payments");
            }
            resolved = accountId;
        }
        if (resolved == null) throw new IllegalStateException("No verified store cash GL account");
        return resolved;
    }

    static void cashOut(DispatchContext dc, GenericValue sale, BigDecimal bankAmount,
            String rrn, String bankTid, BigDecimal cashOut, GenericValue actor,
            String payee, String customer, String currency) throws Exception {
        Delegator d = dc.getDelegator();
        GenericValue shift = EntityQuery.use(d).from("WanPosShift")
                .where("shiftId", sale.getString("shiftId")).queryOne();
        WanErpPosCashControls.lockShift(d, sale.getString("shiftId"));
        if (shift == null || WanErpPosCashControls.expected(d, shift).compareTo(cashOut) < 0) {
            throw new IllegalArgumentException("Insufficient shift cash for bank cash-out");
        }
        String key = sale.getString("idempotencyKey");
        String tag = hash(key).substring(0, 24);
        Map<String, Object> incoming = run(dc, "createPayment", map(
                "partyIdFrom", customer, "partyIdTo", payee, "amount", cashOut,
                "currencyUomId", currency, "paymentTypeId", "CUSTOMER_PAYMENT",
                "paymentMethodTypeId", "CREDIT_CARD", "statusId", "PMNT_RECEIVED",
                "paymentRefNum", "V396-CARD-OUT-" + tag,
                "comments", "EFTPOS purchase + cash-out, bank TID=" + bankTid
                        + ", bank RRN=" + rrn + ", order=" + sale.getString("orderId"),
                "userLogin", actor));
        String incomingId = String.valueOf(incoming.get("paymentId"));
        GenericValue incomingPayment = EntityQuery.use(d).from("Payment")
                .where("paymentId", incomingId).queryOne();
        if (incomingPayment == null || !"PMNT_RECEIVED".equals(incomingPayment.getString("statusId"))
                || incomingPayment.getBigDecimal("amount").compareTo(cashOut) != 0) {
            throw new IllegalStateException("Card cash-out funding not recorded");
        }
        Map<String, Object> outgoing = run(dc, "createPayment", map(
                "partyIdFrom", payee, "partyIdTo", customer, "amount", cashOut,
                "currencyUomId", currency, "paymentTypeId", "CUSTOMER_REFUND",
                "paymentMethodTypeId", "CASH", "statusId", "PMNT_SENT",
                "paymentRefNum", "V396-PAYOUT-" + tag,
                "overrideGlAccountId", postedStoreCashAccount(d, sale.getString("productStoreId"), payee, currency),
                "comments", "v3.9.6 BANK-FUNDED CASH OUT, NOT merchandise refund; original sale="
                        + sale.getString("orderId"), "userLogin", actor));
        String outgoingId = String.valueOf(outgoing.get("paymentId"));
        GenericValue outgoingPayment = EntityQuery.use(d).from("Payment")
                .where("paymentId", outgoingId).queryOne();
        if (outgoingPayment == null || !"PMNT_SENT".equals(outgoingPayment.getString("statusId"))
                || outgoingPayment.getBigDecimal("amount").compareTo(cashOut) != 0) {
            throw new IllegalStateException("Customer cash-out payout not recorded");
        }
        GenericValue record = d.makeValue("WanPosV396Cashout");
        record.set("idempotencyKey", key);
        record.set("cashOutAmount", cashOut);
        record.set("cardFundingPaymentId", incomingId);
        record.set("cashPayoutPaymentId", outgoingId);
        record.set("bankAmount", bankAmount);
        record.set("statusId", "COMPLETED");
        d.create(record);
        movement(d, "O-" + hash(key), sale.getString("shiftId"),
                "EFTPOS_CASH_OUT", key, cashOut,
                actor.getString("userLoginId"), null, "Bank-approved customer cash-out; NOT merchandise refund");
    }
}
