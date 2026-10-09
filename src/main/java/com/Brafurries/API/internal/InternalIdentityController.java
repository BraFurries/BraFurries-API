package com.Brafurries.API.internal;

import com.Brafurries.API.internal.dto.InternalIdentityDtos.InternalIdentityResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/identity")
public class InternalIdentityController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final InternalIdentityService identityService;
    private final String serviceToken;

    public InternalIdentityController(
        InternalIdentityService identityService,
        @Value("${app.bot.status-token:}") String serviceToken
    ) {
        this.identityService = identityService;
        this.serviceToken = serviceToken;
    }

    @GetMapping("/users/{userId}")
    public InternalIdentityResponse getByUserId(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @PathVariable Integer userId,
        @RequestParam(required = false) Integer communityId
    ) {
        authenticate(authorization);
        return identityService.getByUserId(userId, communityId);
    }

    @GetMapping("/discord-users/{discordUserId}")
    public InternalIdentityResponse getByDiscordUserId(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @PathVariable Long discordUserId,
        @RequestParam(required = false) Integer communityId
    ) {
        authenticate(authorization);
        return identityService.getByDiscordUserId(discordUserId, communityId);
    }

    private void authenticate(String authorization) {
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
