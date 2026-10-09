package com.Brafurries.API.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class InternalServiceTokenAuthenticator {
    private static final String BEARER_PREFIX = "Bearer ";
    private final String serviceToken;

    public InternalServiceTokenAuthenticator(
        @org.springframework.beans.factory.annotation.Value("${app.bot.status-token:}") String serviceToken
    ) {
        this.serviceToken = serviceToken;
    }

    public void authenticate(String authorization) {
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Autenticação interna não configurada");
        }
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token interno inválido");
        }
        byte[] supplied = authorization.substring(BEARER_PREFIX.length()).getBytes(StandardCharsets.UTF_8);
        byte[] expected = serviceToken.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(supplied, expected)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token interno inválido");
        }
    }
}
