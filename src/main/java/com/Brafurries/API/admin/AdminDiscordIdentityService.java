package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminDiscordIdentityDtos.DiscordIdentityPreview;
import com.Brafurries.API.admin.dto.AdminDiscordIdentityDtos.LinkDiscordIdentityResponse;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminDiscordIdentityService {

    private final UserRepository users;
    private final UserDiscordRepository discordAccounts;
    private final ConfirmedIdentityClusterService clusters;
    private final AdminDiscordIdentityClient client;
    private final AdminDiscordIdentityPersistenceService persistence;

    public AdminDiscordIdentityService(
        UserRepository users,
        UserDiscordRepository discordAccounts,
        ConfirmedIdentityClusterService clusters,
        AdminDiscordIdentityClient client,
        AdminDiscordIdentityPersistenceService persistence
    ) {
        this.users = users;
        this.discordAccounts = discordAccounts;
        this.clusters = clusters;
        this.client = client;
        this.persistence = persistence;
    }

    public DiscordIdentityPreview preview(Integer sourceUserId, String rawDiscordUserId) {
        requireSourceUser(sourceUserId);
        Long discordUserId = parseSnowflake(rawDiscordUserId);
        var state = client.getUser(String.valueOf(discordUserId));
        var existing = discordAccounts.findByDiscordUserId(discordUserId);
        Integer existingUserId = existing.map(link -> link.getUser().getId()).orElse(null);
        String existingDisplayName = existing
            .map(link -> firstNonBlank(link.getUser().getDisplayName(), link.getUser().getUsername()))
            .orElse(null);
        Set<Integer> cluster = clusters.resolveConfirmedUserIds(sourceUserId);

        return new DiscordIdentityPreview(
            String.valueOf(discordUserId),
            state.username(),
            state.displayName(),
            state.avatarUrl(),
            state.bot(),
            existingUserId,
            existingDisplayName,
            existingUserId != null && cluster.contains(existingUserId)
        );
    }

    public LinkDiscordIdentityResponse confirm(
        Integer sourceUserId,
        String rawDiscordUserId,
        String reason,
        String actorEmail
    ) {
        requireSourceUser(sourceUserId);
        Long discordUserId = parseSnowflake(rawDiscordUserId);
        var state = client.getUser(String.valueOf(discordUserId));
        return persistence.createOrLink(
            sourceUserId,
            discordUserId,
            state,
            normalizeReason(reason),
            actorEmail
        );
    }

    private void requireSourceUser(Integer userId) {
        if (!users.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário de origem não encontrado");
        }
    }

    private Long parseSnowflake(String raw) {
        if (raw == null || raw.isBlank() || !raw.trim().matches("\\d{1,20}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Discord User ID inválido");
        }
        try {
            long value = Long.parseLong(raw.trim());
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Discord User ID inválido");
        }
    }

    private String normalizeReason(String reason) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Motivo é obrigatório");
        }
        if (normalized.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Motivo deve ter no máximo 500 caracteres");
        }
        return normalized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
