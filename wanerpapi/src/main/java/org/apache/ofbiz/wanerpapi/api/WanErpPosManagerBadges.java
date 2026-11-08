package org.apache.ofbiz.wanerpapi.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

/** Permanent badge is ONLY identification. Independent password auth is done in Quarkus per override. */
public final class WanErpPosManagerBadges {
    private static final SecureRandom RANDOM = new SecureRandom();

    private WanErpPosManagerBadges() { }

    private static String hash(String code) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(code.getBytes(StandardCharsets.UTF_8)));
    }

    public static Map<String, Object> issue(DispatchContext dc, GenericValue administrator,
            GenericValue manager, String store, String facility) throws Exception {
        Delegator d = dc.getDelegator();
        // Reissue rotates the manager's credential for this location. No concurrent active badges.
        for (GenericValue prior : EntityQuery.use(d).from("WanPosManagerBadge")
                .where("managerUserLoginId", manager.getString("userLoginId"),
                        "productStoreId", store, "facilityId", facility,
                        "statusId", "ACTIVE").queryList()) {
            prior.set("statusId", "REVOKED");
            prior.set("revokedAt", new Timestamp(System.currentTimeMillis()));
            prior.set("revokedByUserLoginId", administrator.getString("userLoginId"));
            prior.store();
        }
        byte[] random = new byte[16];
        RANDOM.nextBytes(random);
        String code = "WMB" + HexFormat.of().withUpperCase().formatHex(random);
        GenericValue row = d.makeValue("WanPosManagerBadge");
        row.set("badgeId", "MB-" + UUID.randomUUID());
        row.set("badgeHash", hash(code));
        row.set("managerUserLoginId", manager.getString("userLoginId"));
        row.set("productStoreId", store);
        row.set("facilityId", facility);
        row.set("statusId", "ACTIVE");
        row.set("issuedByUserLoginId", administrator.getString("userLoginId"));
        row.set("issuedAt", new Timestamp(System.currentTimeMillis()));
        d.create(row);
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("badgeId", row.getString("badgeId"));
        result.put("managerUserLoginId", manager.getString("userLoginId"));
        result.put("badgeValueOnce", code); // sensitive: print securely; NEVER log or fetch again
        return result;
    }

    public static Map<String, Object> revoke(DispatchContext dc, GenericValue administrator,
            GenericValue row) throws Exception {
        if (!"REVOKED".equals(row.getString("statusId"))) {
            row.set("statusId", "REVOKED");
            row.set("revokedAt", new Timestamp(System.currentTimeMillis()));
            row.set("revokedByUserLoginId", administrator.getString("userLoginId"));
            row.store();
        }
        Map<String, Object> result = ServiceUtil.returnSuccess();
        result.put("badgeId", row.getString("badgeId"));
        result.put("statusId", "REVOKED");
        return result;
    }

    /** A cashier may resolve a credential only for a sale at their authorized store.
     * Never expose hashes or any reusable password credentials in the response.
     */
    public static String resolve(DispatchContext dc, String badgeValue,
            String store, String facility) throws Exception {
        if (badgeValue == null || !badgeValue.matches("WMB[A-F0-9]{32}")) {
            throw new IllegalArgumentException("Invalid manager badge");
        }
        byte[] supplied = HexFormat.of().parseHex(hash(badgeValue));
        String matchedManager = null;
        for (GenericValue badge : EntityQuery.use(dc.getDelegator()).from("WanPosManagerBadge")
                .where("productStoreId", store, "facilityId", facility,
                        "statusId", "ACTIVE").queryList()) {
            byte[] expected = HexFormat.of().parseHex(badge.getString("badgeHash"));
            if (MessageDigest.isEqual(expected, supplied)) {
                matchedManager = badge.getString("managerUserLoginId");
            }
        }
        if (matchedManager == null) {
            throw new IllegalArgumentException("Manager badge is not active at this location");
        }
        return matchedManager;
    }

    public static void requireActive(DispatchContext dc, GenericValue manager, String badgeValue,
            String store, String facility) throws Exception {
        if (badgeValue == null || !badgeValue.matches("WMB[A-F0-9]{32}")) {
            throw new IllegalArgumentException("Manager badge missing or invalid");
        }
        byte[] supplied = HexFormat.of().parseHex(hash(badgeValue));
        boolean valid = false;
        List<GenericValue> active = EntityQuery.use(dc.getDelegator()).from("WanPosManagerBadge")
                .where("managerUserLoginId", manager.getString("userLoginId"),
                        "productStoreId", store, "facilityId", facility, "statusId", "ACTIVE").queryList();
        for (GenericValue badge : active) {
            byte[] expected = HexFormat.of().parseHex(badge.getString("badgeHash"));
            valid |= MessageDigest.isEqual(expected, supplied);
        }
        if (!valid) {
            throw new IllegalArgumentException("Manager badge is not active for this store");
        }
    }
}
