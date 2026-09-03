package com.cloudcode.wantok.keycloak;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

public class OidcTokenService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public Map<String,Object> discovery(OidcTenantConfig cfg) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.issuerUrl + "/.well-known/openid-configuration")).GET().build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 300) {
            throw new IllegalStateException("Discovery failed: " + res.statusCode() + " " + res.body());
        }
        return MAPPER.readValue(res.body(), new TypeReference<Map<String,Object>>(){});
    }


    public String authorizationUrl(OidcTenantConfig cfg, String state, String nonce) throws Exception {
        Map<String,Object> disc = discovery(cfg);
        String authorizationEndpoint = String.valueOf(disc.get("authorization_endpoint"));
        if (authorizationEndpoint == null || authorizationEndpoint.isBlank() || "null".equals(authorizationEndpoint)) {
            throw new IllegalStateException("OIDC discovery did not return authorization_endpoint");
        }
        String organizationScope = (cfg.organizationAlias == null || cfg.organizationAlias.isBlank())
                ? "organization"
                : "organization:" + cfg.organizationAlias;
        String scope = "openid profile email " + organizationScope;
        return authorizationEndpoint
                + "?response_type=code"
                + "&client_id=" + enc(cfg.clientId)
                + "&redirect_uri=" + enc(cfg.redirectUri)
                + "&scope=" + enc(scope)
                + "&state=" + enc(state)
                + "&nonce=" + enc(nonce);
    }

    public Map<String,Object> exchangeCode(OidcTenantConfig cfg, String code) throws Exception {
        Map<String,Object> disc = discovery(cfg);
        String tokenEndpoint = String.valueOf(disc.get("token_endpoint"));
        String body = "grant_type=authorization_code" +
                "&code=" + enc(code) +
                "&redirect_uri=" + enc(cfg.redirectUri) +
                "&client_id=" + enc(cfg.clientId) +
                "&client_secret=" + enc(cfg.clientSecret);
        HttpRequest req = HttpRequest.newBuilder(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 300) {
            throw new IllegalStateException("Token exchange failed: " + res.statusCode() + " " + res.body());
        }
        return MAPPER.readValue(res.body(), new TypeReference<Map<String,Object>>(){});
    }

    public Map<String,Object> validateIdToken(OidcTenantConfig cfg, String idToken, String expectedNonce) throws Exception {
        Map<String,Object> disc = discovery(cfg);
        String jwksUri = String.valueOf(disc.get("jwks_uri"));
        HttpResponse<String> jwksRes = client.send(HttpRequest.newBuilder(URI.create(jwksUri)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (jwksRes.statusCode() >= 300) {
            throw new IllegalStateException("JWKS fetch failed: " + jwksRes.statusCode() + " " + jwksRes.body());
        }

        JWKSet jwkSet = JWKSet.parse(jwksRes.body());
        SignedJWT jwt = SignedJWT.parse(idToken);
        if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
            throw new IllegalStateException("Unsupported ID token alg: " + jwt.getHeader().getAlgorithm());
        }
        JWK jwk = jwkSet.getKeyByKeyId(jwt.getHeader().getKeyID());
        if (jwk == null) {
            throw new IllegalStateException("No JWKS key found for kid " + jwt.getHeader().getKeyID());
        }
        JWSVerifier verifier = new RSASSAVerifier((RSAKey) jwk);
        if (!jwt.verify(verifier)) {
            throw new IllegalStateException("Invalid ID token signature");
        }

        Map<String,Object> claims = jwt.getJWTClaimsSet().getClaims();
        if (!cfg.issuerUrl.equals(claims.get("iss"))) {
            throw new IllegalStateException("Invalid issuer: " + claims.get("iss"));
        }

        Object aud = claims.get("aud");
        boolean audOk = aud instanceof String
                ? cfg.clientId.equals(aud)
                : aud instanceof Collection && ((Collection<?>) aud).contains(cfg.clientId);
        if (!audOk) {
            throw new IllegalStateException("Invalid audience: " + aud);
        }

        Date exp = jwt.getJWTClaimsSet().getExpirationTime();
        if (exp == null || exp.toInstant().isBefore(Instant.now())) {
            throw new IllegalStateException("ID token expired");
        }

        String tokenNonce = WanKeycloakUtil.stringClaim(claims, "nonce");
        if (expectedNonce == null || expectedNonce.isBlank()) {
            throw new IllegalStateException("Expected nonce missing from server session");
        }
        if (tokenNonce == null || !expectedNonce.equals(tokenNonce)) {
            throw new IllegalStateException("Nonce mismatch");
        }

        return claims;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
