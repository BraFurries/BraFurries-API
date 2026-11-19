package com.Brafurries.API.auth.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.MapPropertySource;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenServiceTest {

    @Test
    void refusesMissingOrBlankJwtSigningKey() {
        assertThrows(IllegalStateException.class, () -> new TokenService(null, 900, 1209600));
        assertThrows(IllegalStateException.class, () -> new TokenService("", 900, 1209600));
        assertThrows(IllegalStateException.class, () -> new TokenService("   ", 900, 1209600));
    }

    @Test
    void defaultRefreshLifetimeIsThirtyDaysAndAccessRemainsShort() throws Exception {
        Properties properties = new Properties();
        try (var stream = TokenServiceTest.class.getResourceAsStream("/application.properties")) {
            assertNotNull(stream);
            properties.load(stream);
        }
        // Isolate the default from environment variables on the test runner.
        var environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        long access = Long.parseLong(environment.resolveRequiredPlaceholders(properties.getProperty("jwt.expiration")));
        long refresh = Long.parseLong(environment.resolveRequiredPlaceholders(properties.getProperty("jwt.refresh-expiration")));
        assertEquals(900, access);
        assertEquals(Duration.ofDays(30).toSeconds(), refresh);
        var annotation = TokenService.class.getConstructors()[0].getParameters()[2].getAnnotation(Value.class);
        assertEquals("${jwt.refresh-expiration:2592000}", annotation.value());
        var tokens = new TokenService("integration-test-jwt-signing-key", access, refresh);
        Instant before = Instant.now();
        Instant expires = tokens.refreshExpiresAt();
        assertTrue(!expires.isBefore(before.plus(Duration.ofDays(30))));
        assertTrue(!expires.isAfter(Instant.now().plus(Duration.ofDays(30))));
        assertEquals(900, tokens.accessExpiresInSeconds());

        // Explicit deployment policy is still configurable.
        environment.getPropertySources().addFirst(new MapPropertySource("override", Map.of("JWT_REFRESH_EXPIRATION", "3600")));
        assertEquals("3600", environment.resolveRequiredPlaceholders(properties.getProperty("jwt.refresh-expiration")));
    }

    @Test
    void acceptsExplicitSigningKeyAndPreservesTokenValidation() {
        TokenService tokens = new TokenService("integration-test-jwt-signing-key", 900, 1209600);

        String signed = tokens.generateAccessToken("test@example.com", List.of("ADMIN"), List.of());

        assertNotNull(tokens.authenticateAccessToken(signed));
    }
}
