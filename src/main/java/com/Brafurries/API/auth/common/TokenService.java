package com.Brafurries.API.auth.common;

import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

    private final SecureRandom secureRandom = new SecureRandom();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final long accessExpirationSeconds;
    private final long refreshExpirationSeconds;
    private final String jwtBaseKey;

    public TokenService(
        @Value("${jwt.base-key}") String jwtBaseKey,
        @Value("${jwt.expiration:900}") long accessExpirationSeconds,
        @Value("${jwt.refresh-expiration:1209600}") long refreshExpirationSeconds
    ) {
        if (jwtBaseKey == null || jwtBaseKey.isBlank()) {
            throw new IllegalStateException("JWT_BASE_KEY must be configured with a non-blank signing key");
        }
        this.jwtBaseKey = jwtBaseKey;
        this.accessExpirationSeconds = accessExpirationSeconds;
        this.refreshExpirationSeconds = refreshExpirationSeconds;
    }

    public String generateAccessToken(String principal, Collection<String> roles, Collection<String> permissions) {
        try {
            String headerJson = objectMapper.writeValueAsString(java.util.Map.of("alg", "HS256", "typ", "JWT"));
            Instant expiresAt = accessExpiresAt();
            String payloadJson = objectMapper.writeValueAsString(java.util.Map.of(
                "sub", principal,
                "roles", roles,
                "permissions", permissions,
                "exp", expiresAt.getEpochSecond()
            ));

            String header = Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
            String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signature = sign(header + "." + payload);
            return header + "." + payload + "." + signature;
        } catch (Exception ex) {
            throw new IllegalStateException("Falha ao gerar access token", ex);
        }
    }

    @SuppressWarnings("unchecked")
    public Authentication authenticateAccessToken(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return null;
            }

            String signedContent = parts[0] + "." + parts[1];
            if (!sign(signedContent).equals(parts[2])) {
                return null;
            }

            String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            java.util.Map<String, Object> payload = objectMapper.readValue(payloadJson, java.util.Map.class);
            Number exp = (Number) payload.get("exp");
            if (exp == null || Instant.ofEpochSecond(exp.longValue()).isBefore(Instant.now())) {
                return null;
            }

            String principal = (String) payload.get("sub");
            List<String> roles = (List<String>) payload.getOrDefault("roles", List.of());
            List<String> permissions = (List<String>) payload.getOrDefault("permissions", List.of());
            return new UsernamePasswordAuthenticationToken(principal, token, toAuthorities(roles, permissions));
        } catch (Exception ex) {
            return null;
        }
    }

    private Collection<GrantedAuthority> toAuthorities(Collection<String> roles, Collection<String> permissions) {
        java.util.List<GrantedAuthority> authorities = new java.util.ArrayList<>();
        roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT))));
        permissions.forEach(permission -> authorities.add(new SimpleGrantedAuthority(permission)));
        return authorities;
    }

    private String sign(String content) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(jwtBaseKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signature = hmac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }

    public String generateToken(int size) {
        byte[] bytes = new byte[size];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public Instant accessExpiresAt() {
        return Instant.now().plus(accessExpirationSeconds, ChronoUnit.SECONDS);
    }

    public Instant refreshExpiresAt() {
        return Instant.now().plus(refreshExpirationSeconds, ChronoUnit.SECONDS);
    }

    public int accessExpiresInSeconds() {
        return Math.toIntExact(accessExpirationSeconds);
    }
}
