package org.apache.ofbiz.wanerpapi.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure integer-kina-cent accounting math, no dispatcher/database side effects. */
public final class WanErpPosV396TenderMath {
    private WanErpPosV396TenderMath() { }

    public record SaleAmounts(BigDecimal cashForGoods, BigDecimal cardForGoods,
                              BigDecimal cardCharge, BigDecimal cashOut) { }
    public record RefundAmounts(BigDecimal cashRefund, BigDecimal cardRefund) { }

    private static BigDecimal money(BigDecimal n) {
        if (n == null || n.signum() < 0 || n.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("Non-negative PGK money with at most 2 decimals required");
        }
        return n.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static SaleAmounts sale(BigDecimal goods, BigDecimal cashReceived,
            BigDecimal cardCharge, BigDecimal cashOut) {
        BigDecimal g = money(goods);
        BigDecimal cash = money(cashReceived);
        BigDecimal charge = money(cardCharge);
        BigDecimal out = money(cashOut);
        BigDecimal forGoods = charge.subtract(out);
        if (g.signum() <= 0 || charge.signum() <= 0 || forGoods.signum() <= 0
                || forGoods.add(cash).compareTo(g) != 0) {
            throw new IllegalArgumentException("Card merchandise + cash merchandise must equal native goods total");
        }
        return new SaleAmounts(cash, forGoods, charge, out);
    }

    public static RefundAmounts refund(BigDecimal originalTotal, BigDecimal originalCash,
            BigDecimal alreadyRefundedTotal, BigDecimal alreadyRefundedCash, BigDecimal newRefund) {
        BigDecimal total = money(originalTotal);
        BigDecimal cash = money(originalCash);
        BigDecimal prior = money(alreadyRefundedTotal);
        BigDecimal priorCash = money(alreadyRefundedCash);
        BigDecimal now = money(newRefund);
        if (total.signum() <= 0 || now.signum() <= 0 || cash.compareTo(total) > 0
                || prior.compareTo(total) > 0 || priorCash.compareTo(cash) > 0
                || priorCash.compareTo(prior) > 0 || prior.add(now).compareTo(total) > 0) {
            throw new IllegalArgumentException("Return would exceed original tender or merchandise balance");
        }
        BigDecimal cumul = prior.add(now);
        BigDecimal targetCash = cumul.compareTo(total) == 0 ? cash
                : cumul.multiply(cash).divide(total, 2, RoundingMode.HALF_UP);
        BigDecimal cashPart = targetCash.subtract(priorCash);
        BigDecimal cardPart = now.subtract(cashPart);
        if (cashPart.signum() < 0 || cardPart.signum() < 0
                || cashPart.compareTo(cash.subtract(priorCash)) > 0) {
            throw new IllegalArgumentException("Inconsistent prior refund allocation; must reconcile first");
        }
        return new RefundAmounts(cashPart, cardPart);
    }
}
