package com.cloudcode.wantok.keycloak;

import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.ServiceUtil;

import java.util.Map;

public class WanKeycloakServices {
    public static Map<String,Object> auditLogin(DispatchContext dctx, Map<String,Object> context) {
        try {
            Delegator delegator = dctx.getDelegator();
            GenericValue audit = delegator.makeValue("WanKeycloakSessionAudit");
            audit.set("auditId", delegator.getNextSeqId("WanKeycloakSessionAudit"));
            audit.set("tenantId", context.get("tenantId"));
            audit.set("userLoginId", context.get("userLoginId"));
            audit.set("keycloakSubject", context.get("keycloakSubject"));
            audit.set("eventType", context.get("eventType"));
            audit.set("auditMessage", context.get("message"));
            audit.set("eventStamp", UtilDateTime.nowTimestamp());
            audit.create();
            return ServiceUtil.returnSuccess();
        } catch (Exception e) {
            return ServiceUtil.returnError(e.getMessage());
        }
    }
}
