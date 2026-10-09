package com.Brafurries.API.auth.common;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class TokenServiceTest {

    @Test
    void refusesMissingOrBlankJwtSigningKey() {
        assertThrows(IllegalStateException.class, () -> new TokenService(null, 900, 1209600));
        assertThrows(IllegalStateException.class, () -> new TokenService("", 900, 1209600));
        assertThrows(IllegalStateException.class, () -> new TokenService("   ", 900, 1209600));
    }

    @Test
    void acceptsExplicitSigningKeyAndPreservesTokenValidation() {
        TokenService tokens = new TokenService("integration-test-jwt-signing-key", 900, 1209600);

        String signed = tokens.generateAccessToken("test@example.com", List.of("ADMIN"), List.of());

        assertNotNull(tokens.authenticateAccessToken(signed));
    }
}
