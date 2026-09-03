package com.cloudcode.wantok.keycloak;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

public final class WanKeycloakUtil {
    private static final SecureRandom RANDOM = new SecureRandom();
    private WanKeycloakUtil() {}

    public static String randomUrlToken(int bytes) {
        byte[] data = new byte[bytes];
        RANDOM.nextBytes(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    public static String b64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    public static String fromB64Url(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    public static String stringClaim(Map<String, Object> claims, String name) {
        Object v = claims.get(name);
        if (v == null) return null;
        if (v instanceof String) return (String) v;
        return String.valueOf(v);
    }
}
