package org.apache.ofbiz.wanerpapi.api;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.ofbiz.base.util.UtilDateTime;
import org.apache.ofbiz.entity.Delegator;
import org.apache.ofbiz.entity.GenericEntityException;
import org.apache.ofbiz.entity.GenericValue;
import org.apache.ofbiz.entity.condition.EntityCondition;
import org.apache.ofbiz.entity.condition.EntityOperator;
import org.apache.ofbiz.entity.util.EntityQuery;
import org.apache.ofbiz.order.shoppingcart.CheckOutHelper;
import org.apache.ofbiz.order.shoppingcart.ShoppingCart;
import org.apache.ofbiz.service.DispatchContext;
import org.apache.ofbiz.service.GenericServiceException;
import org.apache.ofbiz.service.LocalDispatcher;
import org.apache.ofbiz.service.ServiceUtil;

/**
 * Controlled REST adapter layer for Wantok ERP / Apache OFBiz release24.09.
 *
 * <p>The public contracts are deliberately narrow. Raw generic finder and
 * order-storage services remain internal to OFBiz.</p>
 */
public final class WanErpApiServices {
    private static final String COMPONENT = "wanerpapi";
    private static final String DEFAULT_CURRENCY = "PGK";
    private static final String DEFAULT_PAYMENT_TYPE = "CUSTOMER_PAYMENT";
    private static final String DEFAULT_PAYMENT_METHOD_TYPE = "EXT_OFFLINE";
    private static final String DEFAULT_PAYMENT_STATUS = "PMNT_RECEIVED";

    private WanErpApiServices() { }

    private static String str(Map<String, ? extends Object> context, String key) {
        Object value = context.get(key);
        return value == null ? null : value.toString();
    }

    private static BigDecimal decimal(Map<String, ? extends Object> context, String key, BigDecimal defaultValue) {
        Object value = context.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        return new BigDecimal(value.toString());
    }

    private static boolean bool(Map<String, ? extends Object> context, String key, boolean defaultValue) {
        Object value = context.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        String text = value.toString();
        return "Y".equalsIgnoreCase(text) || "YES".equalsIgnoreCase(text)
                || "TRUE".equalsIgnoreCase(text) || "1".equals(text);
    }

    private static GenericValue login(Map<String, ? extends Object> context) {
        return (GenericValue) context.get("userLogin");
    }

    private static Map<String, Object> error(String message) {
        return ServiceUtil.returnError(message);
    }

    private static Map<String, Object> dto(GenericValue value) {
        if (value == null) {
            return null;
        }
        return new LinkedHashMap<>(value);
    }

    private static List<Map<String, Object>> dtoList(List<GenericValue> values) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (values != null) {
            for (GenericValue value : values) {
                out.add(dto(value));
            }
        }
        return out;
    }

    private static boolean active(GenericValue value, Timestamp now) {
        Timestamp from = value.getTimestamp("fromDate");
        Timestamp thru = value.getTimestamp("thruDate");
        return (from == null || !from.after(now)) && (thru == null || thru.after(now));
    }

    private static Map<String, Object> run(LocalDispatcher dispatcher, String serviceName, Map<String, Object> input)
            throws GenericServiceException {
        Map<String, Object> result = dispatcher.runSync(serviceName, input);
        if (ServiceUtil.isError(result)) {
            throw new GenericServiceException(serviceName + " failed: " + ServiceUtil.getErrorMessage(result));
        }
        return result;
    }

    private static GenericValue require(Delegator delegator, String entityName, String field, Object value)
            throws GenericEntityException {
        GenericValue found = EntityQuery.use(delegator).from(entityName).where(field, value).queryOne();
        if (found == null) {
            throw new GenericEntityException(entityName + " not found: " + value);
        }
        return found;
    }

    private static void copyIfPresent(Map<String, ? extends Object> source, Map<String, Object> target,
                                      String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null) {
                target.put(key, value);
            }
        }
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i] != null && pairs[i + 1] != null) {
                out.put(pairs[i].toString(), pairs[i + 1]);
            }
        }
        return out;
    }

    /* ========================= 17 operational APIs ========================= */

    public static Map<String, Object> health(DispatchContext dctx, Map<String, Object> context) {
        Map<String, Object> out = ServiceUtil.returnSuccess();
        String delegatorName = dctx.getDelegator().getDelegatorName();
        String tenantId = null;
        int separator = delegatorName.indexOf('#');
        if (separator >= 0 && separator + 1 < delegatorName.length()) {
            tenantId = delegatorName.substring(separator + 1);
        }
        out.put("status", "UP");
        out.put("component", COMPONENT);
        out.put("delegatorName", delegatorName);
        out.put("tenantId", tenantId);
        out.put("timestamp", Instant.now().toString());
        return out;
    }

    public static Map<String, Object> getProduct(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String id = str(context, "idToFind");
            GenericValue product = EntityQuery.use(delegator).from("Product").where("productId", id).queryOne();
            if (product == null) {
                String typeId = str(context, "goodIdentificationTypeId");
                GenericValue identification;
                if (typeId == null) {
                    identification = EntityQuery.use(delegator).from("GoodIdentification")
                            .where("idValue", id).queryFirst();
                } else {
                    identification = EntityQuery.use(delegator).from("GoodIdentification")
                            .where("goodIdentificationTypeId", typeId, "idValue", id).queryFirst();
                }
                if (identification != null) {
                    product = EntityQuery.use(delegator).from("Product")
                            .where("productId", identification.getString("productId")).queryOne();
                }
            }
            if (product == null) {
                return error("Product/identification not found: " + id);
            }
            List<GenericValue> ids = EntityQuery.use(delegator).from("GoodIdentification")
                    .where("productId", product.getString("productId")).queryList();
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("product", dto(product));
            out.put("identifications", dtoList(ids));
            return out;
        } catch (Exception e) {
            return error("Unable to get product: " + e.getMessage());
        }
    }

    public static Map<String, Object> searchProducts(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String q = str(context, "q");
            if (q == null || q.trim().isEmpty()) {
                return error("q is required");
            }
            q = q.trim();
            int limit = context.get("limit") instanceof Number ? ((Number) context.get("limit")).intValue() : 50;
            limit = Math.max(1, Math.min(limit, 200));
            String pattern = "%" + q + "%";
            List<EntityCondition> conditions = Arrays.asList(
                    EntityCondition.makeCondition("productId", EntityOperator.LIKE, pattern),
                    EntityCondition.makeCondition("internalName", EntityOperator.LIKE, pattern),
                    EntityCondition.makeCondition("productName", EntityOperator.LIKE, pattern));
            EntityCondition productCondition = EntityCondition.makeCondition(conditions, EntityOperator.OR);
            List<GenericValue> products = EntityQuery.use(delegator).from("Product")
                    .where(productCondition).orderBy("productId").maxRows(limit).queryList();
            LinkedHashMap<String, GenericValue> unique = new LinkedHashMap<>();
            for (GenericValue product : products) {
                unique.put(product.getString("productId"), product);
            }
            if (unique.size() < limit) {
                List<GenericValue> ids = EntityQuery.use(delegator).from("GoodIdentification")
                        .where(EntityCondition.makeCondition("idValue", EntityOperator.LIKE, pattern))
                        .maxRows(limit).queryList();
                for (GenericValue id : ids) {
                    if (unique.size() >= limit) {
                        break;
                    }
                    GenericValue product = EntityQuery.use(delegator).from("Product")
                            .where("productId", id.getString("productId")).queryOne();
                    if (product != null) {
                        unique.put(product.getString("productId"), product);
                    }
                }
            }
            List<Map<String, Object>> responseProducts = new ArrayList<>();
            for (GenericValue product : unique.values()) {
                Map<String, Object> item = dto(product);
                item.put("identifications", dtoList(EntityQuery.use(delegator).from("GoodIdentification")
                        .where("productId", product.getString("productId")).queryList()));
                responseProducts.add(item);
            }
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("products", responseProducts);
            out.put("count", responseProducts.size());
            return out;
        } catch (Exception e) {
            return error("Unable to search products: " + e.getMessage());
        }
    }

    public static Map<String, Object> getProductsByProductStore(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String storeId = str(context, "productStoreId");
            GenericValue store = require(delegator, "ProductStore", "productStoreId", storeId);
            boolean includeInactive = bool(context, "includeInactive", false);
            Timestamp now = UtilDateTime.nowTimestamp();

            LinkedHashSet<String> catalogIds = new LinkedHashSet<>();
            for (GenericValue rel : EntityQuery.use(delegator).from("ProductStoreCatalog")
                    .where("productStoreId", storeId).orderBy("sequenceNum", "fromDate").queryList()) {
                if (includeInactive || active(rel, now)) {
                    catalogIds.add(rel.getString("prodCatalogId"));
                }
            }

            LinkedHashSet<String> categoryIds = new LinkedHashSet<>();
            Deque<String> pending = new ArrayDeque<>();
            for (String catalogId : catalogIds) {
                for (GenericValue rel : EntityQuery.use(delegator).from("ProdCatalogCategory")
                        .where("prodCatalogId", catalogId).orderBy("sequenceNum", "fromDate").queryList()) {
                    if (includeInactive || active(rel, now)) {
                        String categoryId = rel.getString("productCategoryId");
                        if (categoryIds.add(categoryId)) {
                            pending.addLast(categoryId);
                        }
                    }
                }
            }

            while (!pending.isEmpty()) {
                String parentCategoryId = pending.removeFirst();
                for (GenericValue rollup : EntityQuery.use(delegator).from("ProductCategoryRollup")
                        .where("parentProductCategoryId", parentCategoryId)
                        .orderBy("sequenceNum", "fromDate").queryList()) {
                    if (includeInactive || active(rollup, now)) {
                        String child = rollup.getString("productCategoryId");
                        if (categoryIds.add(child)) {
                            pending.addLast(child);
                        }
                    }
                }
            }

            LinkedHashSet<String> productIds = new LinkedHashSet<>();
            for (String categoryId : categoryIds) {
                for (GenericValue member : EntityQuery.use(delegator).from("ProductCategoryMember")
                        .where("productCategoryId", categoryId).orderBy("sequenceNum", "fromDate").queryList()) {
                    if (includeInactive || active(member, now)) {
                        productIds.add(member.getString("productId"));
                    }
                }
            }

            List<Map<String, Object>> products = new ArrayList<>();
            for (String productId : productIds) {
                GenericValue product = EntityQuery.use(delegator).from("Product")
                        .where("productId", productId).queryOne();
                if (product != null) {
                    Map<String, Object> item = dto(product);
                    item.put("identifications", dtoList(EntityQuery.use(delegator).from("GoodIdentification")
                            .where("productId", productId).queryList()));
                    products.add(item);
                }
            }

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("productStore", dto(store));
            out.put("catalogIds", new ArrayList<>(catalogIds));
            out.put("categoryIds", new ArrayList<>(categoryIds));
            out.put("products", products);
            out.put("count", products.size());
            return out;
        } catch (Exception e) {
            return error("Unable to get products for ProductStore: " + e.getMessage());
        }
    }

    public static Map<String, Object> getProductStore(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String storeId = str(context, "productStoreId");
            GenericValue store = require(delegator, "ProductStore", "productStoreId", storeId);
            Timestamp now = UtilDateTime.nowTimestamp();
            List<Map<String, Object>> catalogs = new ArrayList<>();
            for (GenericValue rel : EntityQuery.use(delegator).from("ProductStoreCatalog")
                    .where("productStoreId", storeId).orderBy("sequenceNum", "fromDate").queryList()) {
                if (active(rel, now)) {
                    catalogs.add(dto(rel));
                }
            }
            List<Map<String, Object>> facilities = new ArrayList<>();
            for (GenericValue rel : EntityQuery.use(delegator).from("ProductStoreFacility")
                    .where("productStoreId", storeId).orderBy("fromDate").queryList()) {
                if (active(rel, now)) {
                    facilities.add(dto(rel));
                }
            }
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("productStore", dto(store));
            out.put("catalogs", catalogs);
            out.put("facilities", facilities);
            return out;
        } catch (Exception e) {
            return error("Unable to get ProductStore: " + e.getMessage());
        }
    }

    public static Map<String, Object> getFacility(DispatchContext dctx, Map<String, Object> context) {
        try {
            GenericValue facility = require(dctx.getDelegator(), "Facility", "facilityId", str(context, "facilityId"));
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("facility", dto(facility));
            return out;
        } catch (Exception e) {
            return error("Unable to get Facility: " + e.getMessage());
        }
    }

    public static Map<String, Object> getInventory(DispatchContext dctx, Map<String, Object> context) {
        try {
            Map<String, Object> result = run(dctx.getDispatcher(), "getInventoryAvailableByFacility",
                    map("productId", str(context, "productId"), "facilityId", str(context, "facilityId")));
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("quantityOnHandTotal", result.getOrDefault("quantityOnHandTotal", BigDecimal.ZERO));
            out.put("availableToPromiseTotal", result.getOrDefault("availableToPromiseTotal", BigDecimal.ZERO));
            out.put("accountingQuantityTotal", result.getOrDefault("accountingQuantityTotal", BigDecimal.ZERO));
            return out;
        } catch (Exception e) {
            return error("Unable to get inventory: " + e.getMessage());
        }
    }

    public static Map<String, Object> getPrice(DispatchContext dctx, Map<String, Object> context) {
        try {
            GenericValue product = require(dctx.getDelegator(), "Product", "productId", str(context, "productId"));
            Map<String, Object> input = new HashMap<>();
            input.put("product", product);
            copyIfPresent(context, input, "productStoreId", "prodCatalogId", "partyId", "quantity", "currencyUomId");
            if (!input.containsKey("quantity")) {
                input.put("quantity", BigDecimal.ONE);
            }
            if (!input.containsKey("currencyUomId")) {
                input.put("currencyUomId", DEFAULT_CURRENCY);
            }
            Map<String, Object> result = run(dctx.getDispatcher(), "calculateProductPrice", input);
            Map<String, Object> out = ServiceUtil.returnSuccess();
            copyIfPresent(result, out, "basePrice", "price", "listPrice", "defaultPrice", "discountRate");
            out.put("currencyUomIdOut", input.get("currencyUomId"));
            return out;
        } catch (Exception e) {
            return error("Unable to calculate price: " + e.getMessage());
        }
    }

    public static Map<String, Object> getParty(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String partyId = str(context, "partyId");
            GenericValue party = require(delegator, "Party", "partyId", partyId);
            GenericValue person = EntityQuery.use(delegator).from("Person").where("partyId", partyId).queryOne();
            GenericValue partyGroup = EntityQuery.use(delegator).from("PartyGroup").where("partyId", partyId).queryOne();
            List<GenericValue> roles = EntityQuery.use(delegator).from("PartyRole").where("partyId", partyId).queryList();
            List<Map<String, Object>> emails = new ArrayList<>();
            List<Map<String, Object>> telecomNumbers = new ArrayList<>();
            List<Map<String, Object>> postalAddresses = new ArrayList<>();
            Timestamp now = UtilDateTime.nowTimestamp();
            for (GenericValue pcm : EntityQuery.use(delegator).from("PartyContactMech")
                    .where("partyId", partyId).queryList()) {
                if (!active(pcm, now)) {
                    continue;
                }
                String contactMechId = pcm.getString("contactMechId");
                GenericValue cm = EntityQuery.use(delegator).from("ContactMech")
                        .where("contactMechId", contactMechId).queryOne();
                if (cm == null) {
                    continue;
                }
                String typeId = cm.getString("contactMechTypeId");
                if ("EMAIL_ADDRESS".equals(typeId)) {
                    emails.add(dto(cm));
                } else if ("TELECOM_NUMBER".equals(typeId)) {
                    GenericValue telecom = EntityQuery.use(delegator).from("TelecomNumber")
                            .where("contactMechId", contactMechId).queryOne();
                    if (telecom != null) {
                        telecomNumbers.add(dto(telecom));
                    }
                } else if ("POSTAL_ADDRESS".equals(typeId)) {
                    GenericValue postal = EntityQuery.use(delegator).from("PostalAddress")
                            .where("contactMechId", contactMechId).queryOne();
                    if (postal != null) {
                        postalAddresses.add(dto(postal));
                    }
                }
            }
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("party", dto(party));
            out.put("person", dto(person));
            out.put("partyGroup", dto(partyGroup));
            out.put("roles", dtoList(roles));
            out.put("emails", emails);
            out.put("telecomNumbers", telecomNumbers);
            out.put("postalAddresses", postalAddresses);
            return out;
        } catch (Exception e) {
            return error("Unable to get Party: " + e.getMessage());
        }
    }

    /**
     * Backward-compatible name retained for existing clients.
     */
    public static Map<String, Object> createCustomer(DispatchContext dctx, Map<String, Object> context) {
        return registerEndUserCustomer(dctx, context);
    }

    /**
     * Creates or reconciles an OFBiz Person Party in the CUSTOMER role for an NEA end-user registration.
     * The service is designed to be called by a trusted backend/facade after identity registration.
     */
    public static Map<String, Object> registerEndUserCustomer(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        try {
            String firstName = trimToNull(str(context, "firstName"));
            String lastName = trimToNull(str(context, "lastName"));
            String email = normalizeEmail(str(context, "emailAddress"));
            String requestedPartyId = trimToNull(str(context, "partyId"));
            String externalId = trimToNull(str(context, "externalId"));
            String idempotencyKey = trimToNull(str(context, "idempotencyKey"));
            String identityKey = externalId != null ? externalId : idempotencyKey;

            if (firstName == null) {
                return error("firstName is required");
            }
            if (lastName == null) {
                return error("lastName is required");
            }
            if (email == null) {
                return error("emailAddress is required");
            }
            if (identityKey == null && requestedPartyId == null) {
                return error("externalId or idempotencyKey is required for idempotent end-user registration");
            }

            GenericValue byPartyId = requestedPartyId == null ? null
                    : EntityQuery.use(delegator).from("Party").where("partyId", requestedPartyId).queryOne();
            GenericValue byIdentity = identityKey == null ? null
                    : EntityQuery.use(delegator).from("Party").where("externalId", identityKey).queryFirst();
            GenericValue byEmail = findPartyByEmail(delegator, email);

            String resolvedPartyId = null;
            for (GenericValue candidate : new GenericValue[] {byPartyId, byIdentity, byEmail}) {
                if (candidate == null) {
                    continue;
                }
                if (resolvedPartyId == null) {
                    resolvedPartyId = candidate.getString("partyId");
                } else if (!resolvedPartyId.equals(candidate.getString("partyId"))) {
                    return error("Registration identity/email already resolves to a different OFBiz Party");
                }
            }

            GenericValue party = resolvedPartyId == null ? null
                    : EntityQuery.use(delegator).from("Party").where("partyId", resolvedPartyId).queryOne();
            boolean created = false;
            if (party == null) {
                Map<String, Object> input = new HashMap<>();
                if (requestedPartyId != null) {
                    input.put("partyId", requestedPartyId);
                }
                input.put("firstName", firstName);
                input.put("lastName", lastName);
                copyIfPresent(context, input, "middleName", "personalTitle");
                input.put("preferredCurrencyUomId", trimToNull(str(context, "preferredCurrencyUomId")) == null
                        ? DEFAULT_CURRENCY : trimToNull(str(context, "preferredCurrencyUomId")));
                if (identityKey != null) {
                    input.put("externalId", identityKey);
                }
                input.put("userLogin", login(context));
                Map<String, Object> result = run(dispatcher, "createPerson", input);
                resolvedPartyId = (String) result.get("partyId");
                party = require(delegator, "Party", "partyId", resolvedPartyId);
                created = true;
            } else {
                GenericValue person = EntityQuery.use(delegator).from("Person")
                        .where("partyId", resolvedPartyId).queryOne();
                if (person == null) {
                    return error("Existing party " + resolvedPartyId + " is not a Person and cannot be used as an end-user customer");
                }
                String currentExternalId = trimToNull(party.getString("externalId"));
                if (identityKey != null && currentExternalId != null && !identityKey.equals(currentExternalId)) {
                    return error("Existing customer is already bound to a different external identity");
                }
                if (identityKey != null && currentExternalId == null) {
                    party.set("externalId", identityKey);
                    party.store();
                }
            }

            run(dispatcher, "ensurePartyRole",
                    map("partyId", resolvedPartyId, "roleTypeId", "CUSTOMER", "userLogin", login(context)));

            String emailContactMechId = ensureEmail(delegator, dispatcher, resolvedPartyId, email, login(context));
            String phoneContactMechId = ensurePhone(delegator, dispatcher, resolvedPartyId, context, login(context));
            String postalContactMechId = ensurePostalAddress(delegator, dispatcher, resolvedPartyId, context, login(context));

            party = require(delegator, "Party", "partyId", resolvedPartyId);
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("partyIdOut", resolvedPartyId);
            out.put("externalIdOut", party.getString("externalId"));
            out.put("emailAddressOut", email);
            out.put("emailContactMechId", emailContactMechId);
            out.put("phoneContactMechId", phoneContactMechId);
            out.put("postalContactMechId", postalContactMechId);
            out.put("customerRoleEnsured", Boolean.TRUE);
            out.put("created", created);
            out.put("duplicate", !created);
            return out;
        } catch (Exception e) {
            return error("Unable to register end-user customer: " + e.getMessage());
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizeEmail(String value) {
        String email = trimToNull(value);
        return email == null ? null : email.toLowerCase(Locale.ROOT);
    }

    private static GenericValue findPartyByEmail(Delegator delegator, String email) throws GenericEntityException {
        GenericValue contactMech = EntityQuery.use(delegator).from("ContactMech")
                .where("contactMechTypeId", "EMAIL_ADDRESS", "infoString", email).queryFirst();
        if (contactMech == null) {
            return null;
        }
        Timestamp now = UtilDateTime.nowTimestamp();
        for (GenericValue pcm : EntityQuery.use(delegator).from("PartyContactMech")
                .where("contactMechId", contactMech.getString("contactMechId")).queryList()) {
            if (active(pcm, now)) {
                return EntityQuery.use(delegator).from("Party")
                        .where("partyId", pcm.getString("partyId")).queryOne();
            }
        }
        return null;
    }

    private static String findPartyEmailContactMechId(Delegator delegator, String partyId, String email)
            throws GenericEntityException {
        Timestamp now = UtilDateTime.nowTimestamp();
        for (GenericValue pcm : EntityQuery.use(delegator).from("PartyContactMech")
                .where("partyId", partyId).queryList()) {
            if (!active(pcm, now)) {
                continue;
            }
            GenericValue cm = EntityQuery.use(delegator).from("ContactMech")
                    .where("contactMechId", pcm.getString("contactMechId"),
                            "contactMechTypeId", "EMAIL_ADDRESS", "infoString", email).queryOne();
            if (cm != null) {
                return cm.getString("contactMechId");
            }
        }
        return null;
    }

    private static String ensureEmail(Delegator delegator, LocalDispatcher dispatcher, String partyId,
                                      String email, GenericValue userLogin) throws Exception {
        String existingId = findPartyEmailContactMechId(delegator, partyId, email);
        if (existingId != null) {
            return existingId;
        }
        Map<String, Object> result = run(dispatcher, "createPartyEmailAddress",
                map("partyId", partyId, "emailAddress", email,
                        "contactMechPurposeTypeId", "PRIMARY_EMAIL", "userLogin", userLogin));
        return (String) result.get("contactMechId");
    }

    private static String ensurePhone(Delegator delegator, LocalDispatcher dispatcher, String partyId,
                                      Map<String, Object> context, GenericValue userLogin) throws Exception {
        String contactNumber = trimToNull(str(context, "contactNumber"));
        if (contactNumber == null) {
            return null;
        }
        String countryCode = trimToNull(str(context, "countryCode"));
        String areaCode = trimToNull(str(context, "areaCode"));
        Timestamp now = UtilDateTime.nowTimestamp();
        for (GenericValue pcm : EntityQuery.use(delegator).from("PartyContactMech")
                .where("partyId", partyId).queryList()) {
            if (!active(pcm, now)) {
                continue;
            }
            GenericValue telecom = EntityQuery.use(delegator).from("TelecomNumber")
                    .where("contactMechId", pcm.getString("contactMechId")).queryOne();
            if (telecom != null && same(contactNumber, telecom.getString("contactNumber"))
                    && same(countryCode, telecom.getString("countryCode"))
                    && same(areaCode, telecom.getString("areaCode"))) {
                return telecom.getString("contactMechId");
            }
        }
        Map<String, Object> input = map("partyId", partyId, "contactNumber", contactNumber, "userLogin", userLogin);
        if (countryCode != null) input.put("countryCode", countryCode);
        if (areaCode != null) input.put("areaCode", areaCode);
        String purpose = trimToNull(str(context, "phoneContactMechPurposeTypeId"));
        if (purpose != null) input.put("contactMechPurposeTypeId", purpose);
        Map<String, Object> result = run(dispatcher, "createPartyTelecomNumber", input);
        return (String) result.get("contactMechId");
    }

    private static String ensurePostalAddress(Delegator delegator, LocalDispatcher dispatcher, String partyId,
                                              Map<String, Object> context, GenericValue userLogin) throws Exception {
        String address1 = trimToNull(str(context, "address1"));
        String city = trimToNull(str(context, "city"));
        String postalCode = trimToNull(str(context, "postalCode"));
        boolean anyAddress = address1 != null || city != null || postalCode != null
                || trimToNull(str(context, "address2")) != null
                || trimToNull(str(context, "stateProvinceGeoId")) != null
                || trimToNull(str(context, "countryGeoId")) != null;
        if (!anyAddress) {
            return null;
        }
        if (address1 == null || city == null || postalCode == null) {
            throw new IllegalArgumentException("address1, city and postalCode are all required when a postal address is supplied");
        }
        Timestamp now = UtilDateTime.nowTimestamp();
        for (GenericValue pcm : EntityQuery.use(delegator).from("PartyContactMech")
                .where("partyId", partyId).queryList()) {
            if (!active(pcm, now)) {
                continue;
            }
            GenericValue postal = EntityQuery.use(delegator).from("PostalAddress")
                    .where("contactMechId", pcm.getString("contactMechId")).queryOne();
            if (postal != null && same(address1, postal.getString("address1"))
                    && same(city, postal.getString("city"))
                    && same(postalCode, postal.getString("postalCode"))) {
                return postal.getString("contactMechId");
            }
        }
        Map<String, Object> input = map("partyId", partyId, "address1", address1,
                "city", city, "postalCode", postalCode, "userLogin", userLogin);
        copyIfPresent(context, input, "address2", "stateProvinceGeoId", "countryGeoId");
        String purpose = trimToNull(str(context, "postalContactMechPurposeTypeId"));
        if (purpose != null) input.put("contactMechPurposeTypeId", purpose);
        Map<String, Object> result = run(dispatcher, "createPartyPostalAddress", input);
        return (String) result.get("contactMechId");
    }

    private static boolean same(String expected, String actual) {
        return expected == null ? actual == null || actual.isEmpty() : expected.equals(actual);
    }

    public static Map<String, Object> getOrder(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String orderId = str(context, "orderId");
            GenericValue header = require(delegator, "OrderHeader", "orderId", orderId);
            List<GenericValue> items = EntityQuery.use(delegator).from("OrderItem")
                    .where("orderId", orderId).orderBy("orderItemSeqId").queryList();
            List<GenericValue> statuses = EntityQuery.use(delegator).from("OrderStatus")
                    .where("orderId", orderId).orderBy("statusDatetime").queryList();

            LinkedHashSet<String> invoiceIds = new LinkedHashSet<>();
            for (GenericValue billing : EntityQuery.use(delegator).from("OrderItemBilling")
                    .where("orderId", orderId).queryList()) {
                if (billing.getString("invoiceId") != null) {
                    invoiceIds.add(billing.getString("invoiceId"));
                }
            }

            boolean paid = !invoiceIds.isEmpty();
            List<Map<String, Object>> invoices = new ArrayList<>();
            LinkedHashSet<String> paymentIds = new LinkedHashSet<>();
            for (String invoiceId : invoiceIds) {
                GenericValue invoice = EntityQuery.use(delegator).from("Invoice").where("invoiceId", invoiceId).queryOne();
                if (invoice != null) {
                    invoices.add(dto(invoice));
                    if (!"INVOICE_PAID".equals(invoice.getString("statusId"))) {
                        paid = false;
                    }
                }
                for (GenericValue application : EntityQuery.use(delegator).from("PaymentApplication")
                        .where("invoiceId", invoiceId).queryList()) {
                    if (application.getString("paymentId") != null) {
                        paymentIds.add(application.getString("paymentId"));
                    }
                }
            }
            List<Map<String, Object>> payments = new ArrayList<>();
            for (String paymentId : paymentIds) {
                GenericValue payment = EntityQuery.use(delegator).from("Payment").where("paymentId", paymentId).queryOne();
                if (payment != null) {
                    payments.add(dto(payment));
                }
            }
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("orderHeader", dto(header));
            out.put("orderItems", dtoList(items));
            out.put("orderStatuses", dtoList(statuses));
            out.put("invoices", invoices);
            out.put("payments", payments);
            out.put("paid", paid);
            return out;
        } catch (Exception e) {
            return error("Unable to get order: " + e.getMessage());
        }
    }

    public static Map<String, Object> createSalesOrder(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        try {
            GenericValue userLogin = login(context);
            if (userLogin == null) {
                return error("Authenticated userLogin is required");
            }
            String idempotencyKey = str(context, "idempotencyKey");
            if (idempotencyKey != null) {
                GenericValue existing = EntityQuery.use(delegator).from("OrderHeader")
                        .where("externalId", idempotencyKey).queryFirst();
                if (existing != null) {
                    Map<String, Object> out = ServiceUtil.returnSuccess();
                    out.put("orderId", existing.getString("orderId"));
                    out.put("grandTotal", existing.getBigDecimal("grandTotal"));
                    out.put("duplicate", Boolean.TRUE);
                    return out;
                }
            }

            String partyId = str(context, "partyId");
            String storeId = str(context, "productStoreId");
            String productId = str(context, "productId");
            BigDecimal quantity = decimal(context, "quantity", null);
            if (quantity == null || quantity.signum() <= 0) {
                return error("quantity must be > 0");
            }
            require(delegator, "Party", "partyId", partyId);
            GenericValue store = require(delegator, "ProductStore", "productStoreId", storeId);
            require(delegator, "Product", "productId", productId);

            String currency = str(context, "currencyUomId");
            if (currency == null) {
                currency = store.getString("defaultCurrencyUomId");
            }
            if (currency == null) {
                currency = DEFAULT_CURRENCY;
            }

            ShoppingCart cart = new ShoppingCart(delegator, storeId, Locale.ENGLISH, currency);
            cart.setUserLogin(userLogin, dispatcher);
            cart.setOrderType("SALES_ORDER");
            cart.setOrderPartyId(partyId);
            cart.setPlacingCustomerPartyId(partyId);
            cart.setBillToCustomerPartyId(partyId);
            cart.setShipToCustomerPartyId(partyId);
            cart.setEndUserCustomerPartyId(partyId);
            if (str(context, "facilityId") != null) {
                cart.setFacilityId(str(context, "facilityId"));
            }
            if (str(context, "salesChannelEnumId") != null) {
                cart.setChannelType(str(context, "salesChannelEnumId"));
            }
            if (idempotencyKey != null) {
                cart.setExternalId(idempotencyKey);
            }

            BigDecimal unitPrice = decimal(context, "unitPrice", BigDecimal.ZERO);
            boolean triggerPriceRules = context.get("unitPrice") == null;
            cart.addItemToEnd(productId, BigDecimal.ZERO, quantity, unitPrice,
                    new HashMap<String, GenericValue>(), new HashMap<String, Object>(),
                    str(context, "prodCatalogId"), "PRODUCT_ORDER_ITEM", dispatcher,
                    Boolean.TRUE, Boolean.valueOf(triggerPriceRules));

            CheckOutHelper checkout = new CheckOutHelper(dispatcher, delegator, cart);
            Map<String, Object> orderResult = checkout.createOrder(userLogin);
            if (orderResult == null || ServiceUtil.isError(orderResult)) {
                return error("Unable to create order: " + (orderResult == null
                        ? "no result" : ServiceUtil.getErrorMessage(orderResult)));
            }
            String orderId = (String) orderResult.get("orderId");
            GenericValue header = EntityQuery.use(delegator).from("OrderHeader").where("orderId", orderId).queryOne();
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("orderId", orderId);
            out.put("grandTotal", header == null ? cart.getGrandTotal() : header.getBigDecimal("grandTotal"));
            out.put("duplicate", Boolean.FALSE);
            return out;
        } catch (Exception e) {
            return error("Unable to create sales order: " + e.getMessage());
        }
    }

    private static String invoiceForOrder(Delegator delegator, String orderId) throws GenericEntityException {
        GenericValue billing = EntityQuery.use(delegator).from("OrderItemBilling")
                .where("orderId", orderId).queryFirst();
        return billing == null ? null : billing.getString("invoiceId");
    }

    public static Map<String, Object> createInvoiceForOrder(DispatchContext dctx, Map<String, Object> context) {
        try {
            String orderId = str(context, "orderId");
            require(dctx.getDelegator(), "OrderHeader", "orderId", orderId);
            String existingInvoiceId = invoiceForOrder(dctx.getDelegator(), orderId);
            if (existingInvoiceId != null) {
                Map<String, Object> out = ServiceUtil.returnSuccess();
                out.put("invoiceId", existingInvoiceId);
                out.put("duplicate", Boolean.TRUE);
                return out;
            }
            Map<String, Object> result = run(dctx.getDispatcher(), "createInvoiceForOrderAllItems",
                    map("orderId", orderId, "userLogin", login(context)));
            String invoiceId = (String) result.get("invoiceId");
            if (invoiceId == null) {
                return error("OFBiz did not create an invoice for order " + orderId);
            }
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("invoiceId", invoiceId);
            out.put("duplicate", Boolean.FALSE);
            return out;
        } catch (Exception e) {
            return error("Unable to create invoice: " + e.getMessage());
        }
    }

    private static GenericValue findPaymentByReference(Delegator delegator, String reference)
            throws GenericEntityException {
        if (reference == null) {
            return null;
        }
        return EntityQuery.use(delegator).from("Payment").where("paymentRefNum", reference).queryFirst();
    }

    public static Map<String, Object> createPayment(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String reference = str(context, "idempotencyKey");
            if (reference == null) {
                reference = str(context, "paymentRefNum");
            }
            GenericValue existing = findPaymentByReference(delegator, reference);
            if (existing != null) {
                Map<String, Object> out = ServiceUtil.returnSuccess();
                out.put("paymentId", existing.getString("paymentId"));
                out.put("duplicate", Boolean.TRUE);
                return out;
            }
            BigDecimal amount = decimal(context, "amount", null);
            if (amount == null || amount.signum() <= 0) {
                return error("amount must be > 0");
            }
            Map<String, Object> input = new HashMap<>();
            input.put("partyIdFrom", str(context, "partyIdFrom"));
            input.put("partyIdTo", str(context, "partyIdTo"));
            input.put("amount", amount);
            input.put("currencyUomId", str(context, "currencyUomId") == null
                    ? DEFAULT_CURRENCY : str(context, "currencyUomId"));
            input.put("paymentTypeId", str(context, "paymentTypeId") == null
                    ? DEFAULT_PAYMENT_TYPE : str(context, "paymentTypeId"));
            input.put("paymentMethodTypeId", str(context, "paymentMethodTypeId") == null
                    ? DEFAULT_PAYMENT_METHOD_TYPE : str(context, "paymentMethodTypeId"));
            input.put("statusId", str(context, "statusId") == null
                    ? DEFAULT_PAYMENT_STATUS : str(context, "statusId"));
            if (reference != null) {
                input.put("paymentRefNum", reference);
            }
            copyIfPresent(context, input, "comments");
            input.put("userLogin", login(context));
            Map<String, Object> result = run(dctx.getDispatcher(), "createPayment", input);
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("paymentId", result.get("paymentId"));
            out.put("duplicate", Boolean.FALSE);
            return out;
        } catch (Exception e) {
            return error("Unable to create payment: " + e.getMessage());
        }
    }

    public static Map<String, Object> applyPayment(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String paymentId = str(context, "paymentId");
            String invoiceId = str(context, "invoiceId");
            require(delegator, "Payment", "paymentId", paymentId);
            require(delegator, "Invoice", "invoiceId", invoiceId);
            GenericValue existing = EntityQuery.use(delegator).from("PaymentApplication")
                    .where("paymentId", paymentId, "invoiceId", invoiceId).queryFirst();
            if (existing != null) {
                run(dctx.getDispatcher(), "checkPaymentInvoices",
                        map("paymentId", paymentId, "userLogin", login(context)));
                Map<String, Object> out = ServiceUtil.returnSuccess();
                out.put("paymentApplicationId", existing.getString("paymentApplicationId"));
                out.put("duplicate", Boolean.TRUE);
                return out;
            }
            Map<String, Object> input = map("paymentId", paymentId, "invoiceId", invoiceId,
                    "userLogin", login(context));
            if (context.get("amountApplied") != null) {
                input.put("amountApplied", context.get("amountApplied"));
            }
            Map<String, Object> result = run(dctx.getDispatcher(), "createPaymentApplication", input);
            run(dctx.getDispatcher(), "checkPaymentInvoices",
                    map("paymentId", paymentId, "userLogin", login(context)));
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("paymentApplicationId", result.get("paymentApplicationId"));
            out.put("duplicate", Boolean.FALSE);
            return out;
        } catch (Exception e) {
            return error("Unable to apply payment: " + e.getMessage());
        }
    }

    public static Map<String, Object> markOrderPaid(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String orderId = str(context, "orderId");
            String paymentId = str(context, "paymentId");
            require(delegator, "OrderHeader", "orderId", orderId);
            GenericValue payment = require(delegator, "Payment", "paymentId", paymentId);
            if (!DEFAULT_PAYMENT_STATUS.equals(payment.getString("statusId"))) {
                run(dctx.getDispatcher(), "setPaymentStatus",
                        map("paymentId", paymentId, "statusId", DEFAULT_PAYMENT_STATUS,
                                "userLogin", login(context)));
            }
            run(dctx.getDispatcher(), "checkPaymentInvoices",
                    map("paymentId", paymentId, "userLogin", login(context)));

            String invoiceId = str(context, "invoiceId");
            if (invoiceId == null) {
                invoiceId = invoiceForOrder(delegator, orderId);
            }
            GenericValue invoice = invoiceId == null ? null
                    : EntityQuery.use(delegator).from("Invoice").where("invoiceId", invoiceId).queryOne();
            payment = EntityQuery.use(delegator).from("Payment").where("paymentId", paymentId).queryOne();
            boolean paid = invoice != null && "INVOICE_PAID".equals(invoice.getString("statusId"));
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("invoiceStatusId", invoice == null ? null : invoice.getString("statusId"));
            out.put("paymentStatusId", payment == null ? null : payment.getString("statusId"));
            out.put("paid", paid);
            return out;
        } catch (Exception e) {
            return error("Unable to reconcile paid state: " + e.getMessage());
        }
    }

    public static Map<String, Object> completePaidSale(DispatchContext dctx, Map<String, Object> context) {
        try {
            String orderIdempotency = str(context, "idempotencyKey");
            String kumulPayReference = str(context, "kumulPayReference");

            Map<String, Object> orderContext = new HashMap<>(context);
            orderContext.put("idempotencyKey", orderIdempotency);
            Map<String, Object> order = createSalesOrder(dctx, orderContext);
            if (ServiceUtil.isError(order)) {
                return order;
            }
            String orderId = (String) order.get("orderId");

            Map<String, Object> invoice = createInvoiceForOrder(dctx,
                    map("orderId", orderId, "userLogin", login(context)));
            if (ServiceUtil.isError(invoice)) {
                return invoice;
            }
            String invoiceId = (String) invoice.get("invoiceId");

            GenericValue store = require(dctx.getDelegator(), "ProductStore", "productStoreId",
                    str(context, "productStoreId"));
            GenericValue header = require(dctx.getDelegator(), "OrderHeader", "orderId", orderId);
            BigDecimal amount = decimal(context, "paymentAmount", header.getBigDecimal("grandTotal"));
            if (amount == null || amount.signum() <= 0) {
                return error("paymentAmount could not be derived or is not > 0");
            }

            Map<String, Object> paymentContext = map(
                    "partyIdFrom", str(context, "partyId"),
                    "partyIdTo", store.getString("payToPartyId"),
                    "amount", amount,
                    "currencyUomId", str(context, "currencyUomId") == null
                            ? header.getString("currencyUom") : str(context, "currencyUomId"),
                    "paymentMethodTypeId", str(context, "paymentMethodTypeId") == null
                            ? DEFAULT_PAYMENT_METHOD_TYPE : str(context, "paymentMethodTypeId"),
                    "paymentTypeId", DEFAULT_PAYMENT_TYPE,
                    "statusId", DEFAULT_PAYMENT_STATUS,
                    "paymentRefNum", kumulPayReference,
                    "idempotencyKey", kumulPayReference,
                    "comments", "KumulPay successful payment for order " + orderId,
                    "userLogin", login(context));
            Map<String, Object> payment = createPayment(dctx, paymentContext);
            if (ServiceUtil.isError(payment)) {
                return payment;
            }
            String paymentId = (String) payment.get("paymentId");

            Map<String, Object> application = applyPayment(dctx,
                    map("paymentId", paymentId, "invoiceId", invoiceId, "amountApplied", amount,
                            "userLogin", login(context)));
            if (ServiceUtil.isError(application)) {
                return application;
            }

            Map<String, Object> paid = markOrderPaid(dctx,
                    map("orderId", orderId, "invoiceId", invoiceId, "paymentId", paymentId,
                            "userLogin", login(context)));
            if (ServiceUtil.isError(paid)) {
                return paid;
            }

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("orderId", orderId);
            out.put("invoiceId", invoiceId);
            out.put("paymentId", paymentId);
            out.put("paymentApplicationId", application.get("paymentApplicationId"));
            out.put("grandTotal", header.getBigDecimal("grandTotal"));
            out.put("paid", paid.get("paid"));
            out.put("duplicate", order.get("duplicate"));
            return out;
        } catch (Exception e) {
            return error("Unable to complete paid sale: " + e.getMessage());
        }
    }

    /* =========================== Setup/admin APIs =========================== */

    public static Map<String, Object> adminCreateCompany(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String partyId = str(context, "partyId");
            GenericValue existing = EntityQuery.use(delegator).from("Party").where("partyId", partyId).queryOne();
            if (existing == null) {
                Map<String, Object> result = run(dctx.getDispatcher(), "createPartyGroup",
                        map("partyId", partyId, "groupName", str(context, "groupName"),
                                "preferredCurrencyUomId", str(context, "preferredCurrencyUomId") == null
                                        ? DEFAULT_CURRENCY : str(context, "preferredCurrencyUomId"),
                                "userLogin", login(context)));
                if (result.get("partyId") != null) {
                    partyId = result.get("partyId").toString();
                }
            }
            run(dctx.getDispatcher(), "ensurePartyRole",
                    map("partyId", partyId, "roleTypeId", "INTERNAL_ORGANIZATIO",
                            "userLogin", login(context)));
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("partyIdOut", partyId);
            out.put("duplicate", existing != null);
            return out;
        } catch (Exception e) {
            return error("Unable to create company: " + e.getMessage());
        }
    }

    public static Map<String, Object> adminCreateCatalog(DispatchContext dctx, Map<String, Object> context) {
        try {
            String id = str(context, "prodCatalogId");
            GenericValue existing = EntityQuery.use(dctx.getDelegator()).from("ProdCatalog")
                    .where("prodCatalogId", id).queryOne();
            if (existing == null) {
                run(dctx.getDispatcher(), "createProdCatalog",
                        map("prodCatalogId", id, "catalogName", str(context, "catalogName"),
                                "userLogin", login(context)));
            }
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("prodCatalogIdOut", id);
            out.put("duplicate", existing != null);
            return out;
        } catch (Exception e) {
            return error("Unable to create catalog: " + e.getMessage());
        }
    }

    public static Map<String, Object> adminCreateCategory(DispatchContext dctx, Map<String, Object> context) {
        try {
            String id = str(context, "productCategoryId");
            GenericValue existing = EntityQuery.use(dctx.getDelegator()).from("ProductCategory")
                    .where("productCategoryId", id).queryOne();
            if (existing == null) {
                run(dctx.getDispatcher(), "createProductCategory",
                        map("productCategoryId", id,
                                "productCategoryTypeId", str(context, "productCategoryTypeId") == null
                                        ? "CATALOG_CATEGORY" : str(context, "productCategoryTypeId"),
                                "categoryName", str(context, "categoryName"), "userLogin", login(context)));
            }
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("productCategoryIdOut", id);
            out.put("duplicate", existing != null);
            return out;
        } catch (Exception e) {
            return error("Unable to create category: " + e.getMessage());
        }
    }

    public static Map<String, Object> adminLinkCatalogCategory(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String catalogId = str(context, "prodCatalogId");
            String categoryId = str(context, "productCategoryId");
            require(delegator, "ProdCatalog", "prodCatalogId", catalogId);
            require(delegator, "ProductCategory", "productCategoryId", categoryId);
            Timestamp now = UtilDateTime.nowTimestamp();
            boolean exists = false;
            for (GenericValue rel : EntityQuery.use(delegator).from("ProdCatalogCategory")
                    .where("prodCatalogId", catalogId, "productCategoryId", categoryId).queryList()) {
                if (active(rel, now)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                run(dctx.getDispatcher(), "addProductCategoryToProdCatalog",
                        map("prodCatalogId", catalogId, "productCategoryId", categoryId,
                                "prodCatalogCategoryTypeId", str(context, "prodCatalogCategoryTypeId") == null
                                        ? "PCCT_BROWSE_ROOT" : str(context, "prodCatalogCategoryTypeId"),
                                "fromDate", now, "userLogin", login(context)));
            }
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("created", !exists);
            return out;
        } catch (Exception e) {
            return error("Unable to link catalog/category: " + e.getMessage());
        }
    }

    public static Map<String, Object> adminLinkCategoryChild(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String parentId = str(context, "parentProductCategoryId");
            String childId = str(context, "productCategoryId");
            require(delegator, "ProductCategory", "productCategoryId", parentId);
            require(delegator, "ProductCategory", "productCategoryId", childId);
            Timestamp now = UtilDateTime.nowTimestamp();
            boolean exists = false;
            for (GenericValue rel : EntityQuery.use(delegator).from("ProductCategoryRollup")
                    .where("parentProductCategoryId", parentId, "productCategoryId", childId).queryList()) {
                if (active(rel, now)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                GenericValue rel = delegator.makeValue("ProductCategoryRollup");
                rel.set("parentProductCategoryId", parentId);
                rel.set("productCategoryId", childId);
                rel.set("fromDate", now);
                delegator.create(rel);
            }
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("created", !exists);
            return out;
        } catch (Exception e) {
            return error("Unable to link child category: " + e.getMessage());
        }
    }

    public static Map<String, Object> adminCreateProduct(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String productId = str(context, "productId");
            GenericValue existing = EntityQuery.use(delegator).from("Product").where("productId", productId).queryOne();
            if (existing == null) {
                Map<String, Object> input = map(
                        "productId", productId,
                        "productTypeId", str(context, "productTypeId") == null
                                ? "FINISHED_GOOD" : str(context, "productTypeId"),
                        "internalName", str(context, "internalName"),
                        "requireInventory", str(context, "requireInventory") == null
                                ? "Y" : str(context, "requireInventory"),
                        "chargeShipping", "N",
                        "taxable", "N",
                        "isVirtual", "N",
                        "isVariant", "N",
                        "autoCreateKeywords", "Y",
                        "userLogin", login(context));
                copyIfPresent(context, input, "productName", "description");
                run(dctx.getDispatcher(), "createProduct", input);
            }

            String barcode = str(context, "barcode");
            if (barcode != null && !barcode.isBlank()) {
                String typeId = str(context, "goodIdentificationTypeId") == null
                        ? "SKU" : str(context, "goodIdentificationTypeId");
                GenericValue identification = EntityQuery.use(delegator).from("GoodIdentification")
                        .where("goodIdentificationTypeId", typeId, "productId", productId).queryOne();
                if (identification == null) {
                    run(dctx.getDispatcher(), "createGoodIdentification",
                            map("goodIdentificationTypeId", typeId, "productId", productId,
                                    "idValue", barcode, "userLogin", login(context)));
                } else if (!barcode.equals(identification.getString("idValue"))) {
                    identification.set("idValue", barcode);
                    identification.store();
                }
            }
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("productIdOut", productId);
            out.put("duplicate", existing != null);
            return out;
        } catch (Exception e) {
            return error("Unable to create product: " + e.getMessage());
        }
    }

    public static Map<String, Object> adminLinkProductCategory(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String productId = str(context, "productId");
            String categoryId = str(context, "productCategoryId");
            require(delegator, "Product", "productId", productId);
            require(delegator, "ProductCategory", "productCategoryId", categoryId);
            Timestamp now = UtilDateTime.nowTimestamp();
            boolean exists = false;
            for (GenericValue rel : EntityQuery.use(delegator).from("ProductCategoryMember")
                    .where("productId", productId, "productCategoryId", categoryId).queryList()) {
                if (active(rel, now)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                run(dctx.getDispatcher(), "addProductToCategory",
                        map("productId", productId, "productCategoryId", categoryId,
                                "fromDate", now, "userLogin", login(context)));
            }
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("created", !exists);
            return out;
        } catch (Exception e) {
            return error("Unable to link product/category: " + e.getMessage());
        }
    }

    public static Map<String, Object> adminCreateFacility(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String facilityId = str(context, "facilityId");
            GenericValue existing = EntityQuery.use(delegator).from("Facility").where("facilityId", facilityId).queryOne();
            if (existing == null) {
                // Core createFacility generates its ID, so Entity Engine is used to preserve the stable demo ID.
                GenericValue facility = delegator.makeValue("Facility");
                facility.set("facilityId", facilityId);
                facility.set("facilityTypeId", str(context, "facilityTypeId") == null
                        ? "WAREHOUSE" : str(context, "facilityTypeId"));
                facility.set("facilityName", str(context, "facilityName"));
                facility.set("ownerPartyId", str(context, "ownerPartyId"));
                facility.set("defaultInventoryItemTypeId", "NON_SERIAL_INV_ITEM");
                delegator.create(facility);
            }
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("facilityIdOut", facilityId);
            out.put("duplicate", existing != null);
            return out;
        } catch (Exception e) {
            return error("Unable to create facility: " + e.getMessage());
        }
    }

    public static Map<String, Object> adminCreateProductStore(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String storeId = str(context, "productStoreId");
            String facilityId = str(context, "inventoryFacilityId");
            GenericValue existing = EntityQuery.use(delegator).from("ProductStore").where("productStoreId", storeId).queryOne();
            if (existing == null) {
                // Core createProductStore generates its ID, so Entity Engine is used to preserve the stable demo ID.
                GenericValue store = delegator.makeValue("ProductStore");
                store.set("productStoreId", storeId);
                store.set("storeName", str(context, "storeName"));
                store.set("payToPartyId", str(context, "payToPartyId"));
                store.set("defaultCurrencyUomId", str(context, "defaultCurrencyUomId") == null
                        ? DEFAULT_CURRENCY : str(context, "defaultCurrencyUomId"));
                if (facilityId != null) {
                    store.set("inventoryFacilityId", facilityId);
                    store.set("oneInventoryFacility", "Y");
                }
                store.set("checkInventory", "Y");
                store.set("reserveInventory", "Y");
                store.set("balanceResOnOrderCreation", "Y");
                store.set("reserveOrderEnumId", "INVRO_FIFO_REC");
                store.set("requireInventory", "Y");
                store.set("defaultLocaleString", "en_US");
                store.set("headerApprovedStatus", "ORDER_APPROVED");
                store.set("itemApprovedStatus", "ITEM_APPROVED");
                store.set("digitalItemApprovedStatus", "ITEM_APPROVED");
                store.set("headerDeclinedStatus", "ORDER_REJECTED");
                store.set("itemDeclinedStatus", "ITEM_REJECTED");
                store.set("headerCancelStatus", "ORDER_CANCELLED");
                store.set("itemCancelStatus", "ITEM_CANCELLED");
                delegator.create(store);
            }

            if (facilityId != null) {
                Timestamp now = UtilDateTime.nowTimestamp();
                boolean hasActiveFacility = false;
                for (GenericValue rel : EntityQuery.use(delegator).from("ProductStoreFacility")
                        .where("productStoreId", storeId, "facilityId", facilityId).queryList()) {
                    if (active(rel, now)) {
                        hasActiveFacility = true;
                        break;
                    }
                }
                if (!hasActiveFacility) {
                    GenericValue relation = delegator.makeValue("ProductStoreFacility");
                    relation.set("productStoreId", storeId);
                    relation.set("facilityId", facilityId);
                    relation.set("fromDate", now);
                    delegator.create(relation);
                }
            }
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("productStoreIdOut", storeId);
            out.put("duplicate", existing != null);
            return out;
        } catch (Exception e) {
            return error("Unable to create ProductStore: " + e.getMessage());
        }
    }

    public static Map<String, Object> adminLinkStoreCatalog(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String storeId = str(context, "productStoreId");
            String catalogId = str(context, "prodCatalogId");
            require(delegator, "ProductStore", "productStoreId", storeId);
            require(delegator, "ProdCatalog", "prodCatalogId", catalogId);
            Timestamp now = UtilDateTime.nowTimestamp();
            boolean exists = false;
            for (GenericValue rel : EntityQuery.use(delegator).from("ProductStoreCatalog")
                    .where("productStoreId", storeId, "prodCatalogId", catalogId).queryList()) {
                if (active(rel, now)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                run(dctx.getDispatcher(), "createProductStoreCatalog",
                        map("productStoreId", storeId, "prodCatalogId", catalogId,
                                "fromDate", now, "userLogin", login(context)));
            }
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("created", !exists);
            return out;
        } catch (Exception e) {
            return error("Unable to link store/catalog: " + e.getMessage());
        }
    }

    public static Map<String, Object> adminSetProductPrice(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        try {
            String productId = str(context, "productId");
            require(delegator, "Product", "productId", productId);
            BigDecimal targetPrice = decimal(context, "price", null);
            if (targetPrice == null || targetPrice.signum() < 0) {
                return error("price must be >= 0");
            }
            String currency = str(context, "currencyUomId") == null ? DEFAULT_CURRENCY : str(context, "currencyUomId");
            String priceType = str(context, "productPriceTypeId") == null
                    ? "DEFAULT_PRICE" : str(context, "productPriceTypeId");
            String purpose = str(context, "productPricePurposeId") == null
                    ? "PURCHASE" : str(context, "productPricePurposeId");
            String storeGroup = str(context, "productStoreGroupId") == null
                    ? "_NA_" : str(context, "productStoreGroupId");
            Timestamp now = UtilDateTime.nowTimestamp();

            GenericValue activePrice = null;
            for (GenericValue productPrice : EntityQuery.use(delegator).from("ProductPrice")
                    .where("productId", productId, "productPriceTypeId", priceType,
                            "productPricePurposeId", purpose, "currencyUomId", currency,
                            "productStoreGroupId", storeGroup)
                    .orderBy("-fromDate").queryList()) {
                if (active(productPrice, now)) {
                    activePrice = productPrice;
                    break;
                }
            }

            boolean changed = activePrice == null || activePrice.getBigDecimal("price") == null
                    || activePrice.getBigDecimal("price").compareTo(targetPrice) != 0;
            if (changed) {
                if (activePrice != null) {
                    activePrice.set("thruDate", now);
                    activePrice.store();
                }
                run(dctx.getDispatcher(), "createProductPrice",
                        map("productId", productId, "productPriceTypeId", priceType,
                                "productPricePurposeId", purpose, "currencyUomId", currency,
                                "productStoreGroupId", storeGroup, "fromDate", now,
                                "price", targetPrice, "userLogin", login(context)));
            }
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("created", changed);
            return out;
        } catch (Exception e) {
            return error("Unable to set product price: " + e.getMessage());
        }
    }

    public static Map<String, Object> adminSetOpeningInventory(DispatchContext dctx, Map<String, Object> context) {
        Delegator delegator = dctx.getDelegator();
        LocalDispatcher dispatcher = dctx.getDispatcher();
        try {
            String productId = str(context, "productId");
            String facilityId = str(context, "facilityId");
            String ownerPartyId = str(context, "ownerPartyId");
            BigDecimal target = decimal(context, "quantity", BigDecimal.ZERO);
            if (target.signum() < 0) {
                return error("quantity must be >= 0");
            }
            require(delegator, "Product", "productId", productId);
            require(delegator, "Facility", "facilityId", facilityId);

            List<GenericValue> items = EntityQuery.use(delegator).from("InventoryItem")
                    .where("productId", productId, "facilityId", facilityId,
                            "inventoryItemTypeId", "NON_SERIAL_INV_ITEM").queryList();
            GenericValue item = items.isEmpty() ? null : items.get(0);
            boolean changed = false;

            if (item == null) {
                Map<String, Object> createInput = map(
                        "inventoryItemTypeId", "NON_SERIAL_INV_ITEM",
                        "productId", productId,
                        "facilityId", facilityId,
                        "userLogin", login(context));
                if (ownerPartyId != null) {
                    createInput.put("ownerPartyId", ownerPartyId);
                }
                Map<String, Object> created = run(dispatcher, "createInventoryItem", createInput);
                String inventoryItemId = (String) created.get("inventoryItemId");
                item = EntityQuery.use(delegator).from("InventoryItem")
                        .where("inventoryItemId", inventoryItemId).queryOne();
                if (target.signum() != 0) {
                    // release24.09 createInventoryItemDetail does NOT accept effectiveDate.
                    run(dispatcher, "createInventoryItemDetail",
                            map("inventoryItemId", inventoryItemId,
                                    "quantityOnHandDiff", target,
                                    "availableToPromiseDiff", target,
                                    "userLogin", login(context)));
                    changed = true;
                }
            } else if (bool(context, "resetExistingQuantity", false)) {
                Map<String, Object> totals = run(dispatcher, "getInventoryAvailableByFacility",
                        map("productId", productId, "facilityId", facilityId));
                BigDecimal currentQoh = totals.get("quantityOnHandTotal") instanceof BigDecimal
                        ? (BigDecimal) totals.get("quantityOnHandTotal") : BigDecimal.ZERO;
                BigDecimal currentAtp = totals.get("availableToPromiseTotal") instanceof BigDecimal
                        ? (BigDecimal) totals.get("availableToPromiseTotal") : BigDecimal.ZERO;
                BigDecimal qohDiff = target.subtract(currentQoh);
                BigDecimal atpDiff = target.subtract(currentAtp);
                if (qohDiff.signum() != 0 || atpDiff.signum() != 0) {
                    run(dispatcher, "createInventoryItemDetail",
                            map("inventoryItemId", item.getString("inventoryItemId"),
                                    "quantityOnHandDiff", qohDiff,
                                    "availableToPromiseDiff", atpDiff,
                                    "userLogin", login(context)));
                    changed = true;
                }
            }

            Map<String, Object> totals = run(dispatcher, "getInventoryAvailableByFacility",
                    map("productId", productId, "facilityId", facilityId));
            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("inventoryItemId", item.getString("inventoryItemId"));
            out.put("quantityOnHandTotal", totals.getOrDefault("quantityOnHandTotal", BigDecimal.ZERO));
            out.put("availableToPromiseTotal", totals.getOrDefault("availableToPromiseTotal", BigDecimal.ZERO));
            out.put("changed", changed);
            return out;
        } catch (Exception e) {
            return error("Unable to set opening inventory: " + e.getMessage());
        }
    }

    public static Map<String, Object> adminBootstrapNeaDemo(DispatchContext dctx, Map<String, Object> context) {
        try {
            GenericValue userLogin = login(context);
            Map<String, Object> summary = new LinkedHashMap<>();

            Map<String, Object> company = adminCreateCompany(dctx,
                    map("partyId", "NEA", "groupName", "National Energy Authority",
                            "preferredCurrencyUomId", DEFAULT_CURRENCY, "userLogin", userLogin));
            if (ServiceUtil.isError(company)) return company;
            summary.put("company", company);

            Map<String, Object> catalog = adminCreateCatalog(dctx,
                    map("prodCatalogId", "NEA_ONLINE_CATALOG", "catalogName", "NEA Online Services Catalog",
                            "userLogin", userLogin));
            if (ServiceUtil.isError(catalog)) return catalog;
            summary.put("catalog", catalog);

            Map<String, Object> category = adminCreateCategory(dctx,
                    map("productCategoryId", "NEA_CAT_ONLINE_ROOT", "categoryName", "NEA Online Services",
                            "productCategoryTypeId", "CATALOG_CATEGORY", "userLogin", userLogin));
            if (ServiceUtil.isError(category)) return category;
            summary.put("category", category);

            Map<String, Object> catalogCategory = adminLinkCatalogCategory(dctx,
                    map("prodCatalogId", "NEA_ONLINE_CATALOG", "productCategoryId", "NEA_CAT_ONLINE_ROOT",
                            "prodCatalogCategoryTypeId", "PCCT_BROWSE_ROOT", "userLogin", userLogin));
            if (ServiceUtil.isError(catalogCategory)) return catalogCategory;
            summary.put("catalogCategory", catalogCategory);

            Map<String, Object> facility = adminCreateFacility(dctx,
                    map("facilityId", "NEA_HQ_WAREHOUSE", "facilityName", "NEA Headquarters Warehouse",
                            "ownerPartyId", "NEA", "facilityTypeId", "WAREHOUSE", "userLogin", userLogin));
            if (ServiceUtil.isError(facility)) return facility;
            summary.put("facility", facility);

            Map<String, Object> store = adminCreateProductStore(dctx,
                    map("productStoreId", "NEA_ONLINE_STORE", "storeName", "NEA Online Store",
                            "payToPartyId", "NEA", "defaultCurrencyUomId", DEFAULT_CURRENCY,
                            "inventoryFacilityId", "NEA_HQ_WAREHOUSE", "userLogin", userLogin));
            if (ServiceUtil.isError(store)) return store;
            summary.put("productStore", store);

            Map<String, Object> storeCatalog = adminLinkStoreCatalog(dctx,
                    map("productStoreId", "NEA_ONLINE_STORE", "prodCatalogId", "NEA_ONLINE_CATALOG",
                            "userLogin", userLogin));
            if (ServiceUtil.isError(storeCatalog)) return storeCatalog;
            summary.put("storeCatalog", storeCatalog);

            Map<String, Object> product = adminCreateProduct(dctx,
                    map("productId", "NEA-TEST-001", "internalName", "NEA API Test Item",
                            "productName", "NEA API Test Item",
                            "description", "NEA / KumulPay integration API test item",
                            "productTypeId", "FINISHED_GOOD", "requireInventory", "Y",
                            "barcode", "NEA0001", "goodIdentificationTypeId", "SKU", "userLogin", userLogin));
            if (ServiceUtil.isError(product)) return product;
            summary.put("product", product);

            Map<String, Object> productCategory = adminLinkProductCategory(dctx,
                    map("productId", "NEA-TEST-001", "productCategoryId", "NEA_CAT_ONLINE_ROOT",
                            "userLogin", userLogin));
            if (ServiceUtil.isError(productCategory)) return productCategory;
            summary.put("productCategory", productCategory);

            Map<String, Object> price = adminSetProductPrice(dctx,
                    map("productId", "NEA-TEST-001", "price", new BigDecimal("10.00"),
                            "currencyUomId", DEFAULT_CURRENCY, "productPriceTypeId", "DEFAULT_PRICE",
                            "productPricePurposeId", "PURCHASE", "productStoreGroupId", "_NA_",
                            "userLogin", userLogin));
            if (ServiceUtil.isError(price)) return price;
            summary.put("price", price);

            Map<String, Object> inventory = adminSetOpeningInventory(dctx,
                    map("productId", "NEA-TEST-001", "facilityId", "NEA_HQ_WAREHOUSE",
                            "ownerPartyId", "NEA", "quantity", new BigDecimal("10"),
                            "resetExistingQuantity", bool(context, "resetExistingInventory", false),
                            "userLogin", userLogin));
            if (ServiceUtil.isError(inventory)) return inventory;
            summary.put("inventory", inventory);

            Map<String, Object> out = ServiceUtil.returnSuccess();
            out.put("companyPartyId", "NEA");
            out.put("prodCatalogId", "NEA_ONLINE_CATALOG");
            out.put("productCategoryId", "NEA_CAT_ONLINE_ROOT");
            out.put("productStoreId", "NEA_ONLINE_STORE");
            out.put("facilityId", "NEA_HQ_WAREHOUSE");
            out.put("productId", "NEA-TEST-001");
            out.put("barcode", "NEA0001");
            out.put("inventoryItemId", inventory.get("inventoryItemId"));
            out.put("price", new BigDecimal("10.00"));
            out.put("currencyUomId", DEFAULT_CURRENCY);
            out.put("openingQuantity", new BigDecimal("10"));
            out.put("summary", summary);
            return out;
        } catch (Exception e) {
            return error("Unable to bootstrap NEA demo data: " + e.getMessage());
        }
    }
}
