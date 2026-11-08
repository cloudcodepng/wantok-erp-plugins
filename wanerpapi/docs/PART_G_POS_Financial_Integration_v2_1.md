# PART G v2.1 — Standalone EFTPOS and cash UAT integration

**Scope:** `wanerpapi(5).zip` baseline + native OFBiz order/payment/return integration from PART G v2.0 + manually confirmed standalone bank EFTPOS sale and refund evidence.

## IMPORTANT: manual evidence is not bank verification

A standalone EFTPOS terminal is independent from Wantok POS/Oracle APEX. The cashier must inspect the **terminal-printed APPROVED** receipt and enter its **bank terminal ID (TID), RRN/reference, approval code, receipt date and receipt amount**. The posted record is explicitly marked `CASHIER_ATTESTED / PENDING` reconciliation. The OFBiz `Payment` is recorded as `CUSTOMER_PAYMENT / CREDIT_CARD / PMNT_RECEIVED` for sale, or `CUSTOMER_REFUND / CREDIT_CARD / PMNT_SENT` for a card refund; **this acknowledges the cashier's receipt attestation, NOT an authenticated bank/PSP settlement confirmation**. Business must reconcile bank settlement statements with POS Eftpos references daily. Do not process a DECLINED, CANCELLED, TIMED OUT or uncertain terminal response. No PAN, CVV or PIN data is accepted.

## Process sequence

1. Cashier signs into Wantok POS, opens an authorized shift, scans items, selects `quote-sale`. This v2.1 quote uses the same native ShoppingCart + CheckOutHelper tax calculation as checkout and returns `grandTotal`, `taxAmount`, `currencyUomId`. **Prices are not locked**; final checkout re-calculates everything, and a bank receipt amount mismatch fails. Confirm configured GST/address rules during UAT.
2. Cashier enters the **exact displayed total** on the standalone bank EFTPOS terminal; customer taps/inserts card and authorizes.
3. Cashier inspects the *terminal's* printed status `APPROVED`, RRN, terminal ID, approval code, date and amount; confirm on the APEX UI (do NOT interpret user-entered fields as independently bank verified).
4. Call `complete-sale` with `tender.type=EFTPOS`, the receipt evidence and `confirmedApproved=true`. Terminal ID is checked against the registered `WanPosTerminalDevice` for the actual POS lane (`deviceType=EFTPOS`, `connectionType=STANDALONE`, `portName=<bank TID>`). OFBiz validates the sale, calculates final order total and demands exact EFTPOS amount match. Only then it creates native order/stock issuance/invoice/payment application.
5. The unique receipt key `(store, bank terminal, RRN, receipt date)` is protected by a SHONG-only PostgreSQL unique partial index. A repeated HTTP request with the **same sale idempotency key and identical payload** returns the original sale. Reusing the receipt in another sale is rejected (after step-2 migration applied).
6. Receipt display includes both `receiptNumber` (Wantok POS) and bank receipt TID/RRN; flag `CASHIER_ATTESTED/PENDING`. Bank settlement reconciliation must be a separate human operational control.
7. EFTPOS **refund**: manager processes original-card refund/reversal on the standalone terminal, obtains and checks **a new approved refund receipt**, then invokes `create-return` or `void-sale` with `refundTender` containing that receipt. Native credit/refund amount must equal terminal refund amount, and a uniqueness guard prevents reusing a refund receipt. No cash refunds for original EFTPOS sales via this API. The existing authorized return/REFUND roles remain required. **Refund amount should be determined with the manager before processing the physical bank refund.**

## Configuring the standalone bank terminal (once per POS lane)

After `save-terminal` and admin store MANAGE grant, register via:

```json
{
  "tenantId":"SHONG",
  "terminalId":"GEREHU-POS-01",
  "deviceType":"EFTPOS",
  "connectionType":"STANDALONE",
  "deviceName":"Bank EFTPOS Terminal 1",
  "portName":"TID12345678"
}
```

`portName` stores the TID printed on the **physical EFTPOS receipt**, not a serial/USB port; `settingsJson` must remain omitted/null for this device. Register each standalone bank terminal against the allowed POS lane(s). Do NOT store payment card numbers, PAN, track data, CVV, PIN, or EFTPOS administrative passwords anywhere in Wantok POS.

## Sale request (matching Quarkus PART H v1.2 or v1.1)

```json
{
 "tenantId":"SHONG", "terminalId":"GEREHU-POS-01", "shiftId":"REPLACE_SHIFT",
 "idempotencyKey":"SHONG-EFTPOS-UAT-001",
 "items":[{"productId":"SHONG-P000001","quantity":2}],
 "tender":{
   "type":"EFTPOS","amountReceived":12.50,
   "bankTerminalId":"TID12345678","rrn":"123456789012",
   "approvalCode":"A12345","receiptDate":"2026-10-19",
   "receiptStatus":"APPROVED","confirmedApproved":true
 }
}
```

**12.50 is illustrative only.** Use the live `quote-sale.grandTotal` and actual terminal receipt. A different amount is **rejected**. `receiptDate` must be ISO YYYY-MM-DD within one day of current UTC date; use the physical terminal's local receipt date. RRN/reference may include A-Z, 0-9 and hyphen (6–40 chars). Approval code 2–20 chars; TID 3–56.

## EFTPOS refund/void extension (Quarkus v1.2)

Add `refundTender` to `create-return` or `void-sale`:

```json
"refundTender":{
 "type":"EFTPOS","amountReceived":6.25,"bankTerminalId":"TID12345678",
 "rrn":"123456789013","approvalCode":"R54321",
 "receiptDate":"2026-10-19","receiptStatus":"APPROVED","confirmedApproved":true
}
```

**6.25 is illustrative.** For a partial refund use exact native credit-note value. If the amount differs or native posting fails after the physical terminal refund, escalate for manual financial reconciliation; **never issue another physical refund simply because the API timed out.** The v1.1 Quarkus allowlist does not accept `refundTender` on return/void; use the PART H v1.2 replacement patch supplied with v2.1.

## Accounting and reconciliation

- Native `OrderHeader`, `OrderItem`, `Shipment`, `Invoice`, `Payment`, `PaymentApplication`, and `WanPosSaleRequest` remain the system-of-record and audit links.
- `WanPosSaleRequest.eftpos*` records manual receipt evidence; `WanPosReturnRequest.eftpos*` records the separate refund receipt evidence.
- Shift expected **cash** includes only CASH sales less CASH refunds; EFTPOS sales and refunds must never affect the cash drawer variance.
- Reconcile daily by TID/date/RRN/amount against bank merchant settlement, flag missing, duplicate, cancelled, reversed or charged-back transactions; manually investigate discrepancies.
- No mixed tenders, bank/PSP API integration, automated chargebacks, or real-time settlement verification are implemented. PCI-sensitive card details must never enter APEX or backend request logs.
- Register the physical terminal with `wanerpPosSaveDevice` first. Keep `WANERP_POS_BRIDGE` scoped, enable tenant HMAC, retain store/facility grants and explicit user permissions.
- Do not run production sales until full Gradle `checkstyleMain`, `build`, host UAT, PostgreSQL uniqueness migration, reconciliation and concurrent failure tests pass.

## Upgrade notes

For a **fresh install** on the existing `wanerpapi(5).zip`, apply PART G v2.1 **replacement patch** instead of v2.0. Run the original SHONG v2.0 **pre-startup shift-uniqueness migration**, start host OFBiz once to let the entity model add new columns and `wan_pos_return_request`, then stop it and run **PART G v2.1 post-startup EFTPOS uniqueness migration**, then restart with the secure SHONG bridge environment. Verify the read-only SQL. If v2.0 was **already applied**, use the incremental v2.0→v2.1 patch; do not reapply the replacement. No `loadAll` or any tenant reset.
