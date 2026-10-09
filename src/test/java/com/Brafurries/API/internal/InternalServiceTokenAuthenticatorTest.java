package com.Brafurries.API.internal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class InternalServiceTokenAuthenticatorTest {

    @Test
    void acceptsConfiguredBearerToken() {
        InternalServiceTokenAuthenticator authenticator =
            new InternalServiceTokenAuthenticator("runtime-secret");

        assertDoesNotThrow(() -> authenticator.authenticate("Bearer runtime-secret"));
    }

    @Test
    void rejectsMissingOrDifferentBearerToken() {
        InternalServiceTokenAuthenticator authenticator =
            new InternalServiceTokenAuthenticator("runtime-secret");

        ResponseStatusException missing = assertThrows(
            ResponseStatusException.class,
            () -> authenticator.authenticate(null)
        );
        ResponseStatusException different = assertThrows(
            ResponseStatusException.class,
            () -> authenticator.authenticate("Bearer other-secret")
        );

        assertEquals(HttpStatus.UNAUTHORIZED, missing.getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, different.getStatusCode());
    }

    @Test
    void failsClosedWhenInternalTokenIsNotConfigured() {
        InternalServiceTokenAuthenticator authenticator =
            new InternalServiceTokenAuthenticator(" ");

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> authenticator.authenticate("Bearer anything")
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatusCode());
    }
}
