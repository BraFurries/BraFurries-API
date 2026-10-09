package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.AiConfigurationDtos.*;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.GuildConfigurationStore.AiStoredConfig;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiConfigurationService {
    private static final Set<String> AI_CHANNEL_TYPES = Set.of("text", "news");

    private final GuildManagementAccessService access;
    private final GuildConfigurationStore store;
    private final OpenAiCredentialValidator openAi;
    private final AiCredentialCipher credentialCipher;
    private final CommunityDiscordRepository discordLinks;
    private final CommunityAuditLogRepository auditLogs;
    private final UserRepository users;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public AiConfigurationService(
        GuildManagementAccessService access,
        GuildConfigurationStore store,
        OpenAiCredentialValidator openAi,
        AiCredentialCipher credentialCipher,
        CommunityDiscordRepository discordLinks,
        CommunityAuditLogRepository auditLogs,
        UserRepository users,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        this.access = access;
        this.store = store;
        this.openAi = openAi;
        this.credentialCipher = credentialCipher;
        this.discordLinks = discordLinks;
        this.auditLogs = auditLogs;
        this.users = users;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public AiConfigResponse get(Authentication authentication, String guildId) {
        access.authorize(authentication, guildId);
        return toResponse(parseGuildId(guildId), store.readAiConfig(parseGuildId(guildId)));
    }

    public AiConfigResponse update(
        Authentication authentication,
        String guildId,
        AiConfigUpdateRequest request
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        long numericGuildId = parseGuildId(guildId);
        AiStoredConfig current = store.readAiConfig(numericGuildId);

        String model = normalizeModel(request.model());
        List<String> channelIds = normalizeSnowflakes(request.channelIds(), "channelId");
        List<String> adminUserIds = normalizeSnowflakes(request.adminUserIds(), "discordUserId");

        validateChannels(authorized, channelIds);
        if (Boolean.TRUE.equals(request.channelLimitEnabled()) && channelIds.isEmpty()) {
            throw invalid(
                "A limitação por canal está ativa. Selecione ao menos um canal da IA ou desative a limitação."
            );
        }

        if (current.tokenConfigured() && !Objects.equals(current.model(), model)) {
            String encryptedToken = store.readAiTokenCiphertext(numericGuildId);
            String token = credentialCipher.decrypt(encryptedToken);
            openAi.validate(token, model);
        }

        List<String> changed = changedFields(
            current,
            request.enabled(),
            model,
            request.channelLimitEnabled(),
            channelIds,
            request.adminChannelBypassEnabled(),
            adminUserIds
        );
        if (changed.isEmpty()) {
            return toResponse(numericGuildId, current);
        }

        User actor = authenticatedUser(authentication);
        PersistedAiConfig persisted = transactions.execute(status -> {
            store.writeAiConfig(
                numericGuildId,
                request.enabled(),
                model,
                request.channelLimitEnabled(),
                channelIds,
                request.adminChannelBypassEnabled(),
                adminUserIds
            );
            AiStoredConfig saved = store.readAiConfig(numericGuildId);
            saveAudit(
                linkedDiscord(numericGuildId),
                actor,
                "AI_CONFIG_UPDATED",
                numericGuildId,
                Map.of(
                    "guildId", String.valueOf(numericGuildId),
                    "changedFields", changed
                )
            );
            return new PersistedAiConfig(saved);
        });

        if (persisted == null) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Não foi possível salvar a configuração de IA"
            );
        }
        return toResponse(numericGuildId, persisted.config());
    }

    public AiTokenStatusResponse rotateToken(
        Authentication authentication,
        String guildId,
        AiTokenUpdateRequest request
    ) {
        access.authorize(authentication, guildId);
        long numericGuildId = parseGuildId(guildId);
        AiStoredConfig current = store.readAiConfig(numericGuildId);
        String model = normalizeModel(current.model());
        String token = normalizeToken(request.token());

        // Resolve every local prerequisite before contacting the provider.
        User actor = authenticatedUser(authentication);
        CommunityDiscord link = linkedDiscord(numericGuildId);

        openAi.validate(token, model);
        String encryptedToken = credentialCipher.encrypt(token);

        LocalDateTime updatedAt = transactions.execute(status -> {
            LocalDateTime persistedAt = store.writeAiTokenCredential(
                numericGuildId,
                encryptedToken
            );
            saveAudit(
                link,
                actor,
                "AI_TOKEN_ROTATED",
                numericGuildId,
                Map.of("guildId", String.valueOf(numericGuildId))
            );
            return persistedAt;
        });
        if (updatedAt == null) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Não foi possível salvar a credencial de IA"
            );
        }
        return new AiTokenStatusResponse(true, updatedAt);
    }

    public AiTokenStatusResponse removeToken(
        Authentication authentication,
        String guildId
    ) {
        access.authorize(authentication, guildId);
        long numericGuildId = parseGuildId(guildId);
        // Fail before the transaction when the guild configuration does not exist.
        store.readAiConfig(numericGuildId);
        User actor = authenticatedUser(authentication);
        CommunityDiscord link = linkedDiscord(numericGuildId);

        transactions.executeWithoutResult(status -> {
            store.removeAiTokenCredential(numericGuildId);
            saveAudit(
                link,
                actor,
                "AI_TOKEN_REMOVED",
                numericGuildId,
                Map.of("guildId", String.valueOf(numericGuildId))
            );
        });
        return new AiTokenStatusResponse(false, null);
    }

    private String normalizeModel(String raw) {
        String model = raw == null ? "" : raw.trim();
        if (model.isEmpty() || model.length() > 32) {
            throw invalid("Modelo OpenAI inválido");
        }
        return model;
    }

    private String normalizeToken(String raw) {
        String token = raw == null ? "" : raw.trim();
        if (token.isEmpty() || token.length() > 4096) {
            throw invalid("Credencial OpenAI inválida");
        }
        return token;
    }

    private List<String> normalizeSnowflakes(List<String> raw, String field) {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " obrigatório");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String value : raw) {
            String normalized = value == null ? "" : value.trim();
            if (!normalized.matches("[1-9]\\d*")) {
                throw invalid(field + " deve ser numérico e positivo");
            }
            values.add(normalized);
        }
        return List.copyOf(values);
    }

    private void validateChannels(AuthorizedGuild authorized, List<String> channelIds) {
        for (String channelId : channelIds) {
            boolean valid = authorized.resources().channels().stream()
                .anyMatch(channel ->
                    channelId.equals(channel.id())
                        && AI_CHANNEL_TYPES.contains(channel.type())
                );
            if (!valid) {
                throw invalid("Canal de IA inválido para esta guild");
            }
        }
    }

    private List<String> changedFields(
        AiStoredConfig current,
        boolean enabled,
        String model,
        boolean channelLimitEnabled,
        List<String> channelIds,
        boolean adminChannelBypassEnabled,
        List<String> adminUserIds
    ) {
        List<String> changed = new ArrayList<>();
        changed(changed, "enabled", current.enabled(), enabled);
        changed(changed, "model", current.model(), model);
        changed(changed, "channelLimitEnabled", current.channelLimitEnabled(), channelLimitEnabled);
        changed(changed, "channelIds", current.channelIds(), channelIds);
        changed(
            changed,
            "adminChannelBypassEnabled",
            current.adminChannelBypassEnabled(),
            adminChannelBypassEnabled
        );
        changed(changed, "adminUserIds", current.adminUserIds(), adminUserIds);
        return List.copyOf(changed);
    }

    private void changed(List<String> fields, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            fields.add(field);
        }
    }

    private AiConfigResponse toResponse(long guildId, AiStoredConfig config) {
        return new AiConfigResponse(
            String.valueOf(guildId),
            config.enabled(),
            config.model(),
            config.tokenConfigured(),
            config.tokenUpdatedAt(),
            config.channelLimitEnabled(),
            config.channelIds(),
            config.adminChannelBypassEnabled(),
            config.adminUserIds()
        );
    }

    private CommunityDiscord linkedDiscord(long guildId) {
        return discordLinks.findByGuildId(guildId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Servidor não vinculado a uma Community"
            ));
    }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário não autenticado");
        }
        return users.findByEmail(authentication.getName().trim().toLowerCase())
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Usuário autenticado não encontrado"
            ));
    }

    private void saveAudit(
        CommunityDiscord link,
        User actor,
        String action,
        long guildId,
        Map<String, ?> metadata
    ) {
        CommunityAuditLog audit = new CommunityAuditLog();
        audit.setCommunity(link.getCommunity());
        audit.setActorUser(actor);
        audit.setAction(action);
        audit.setTargetType("DISCORD_GUILD");
        audit.setTargetId(String.valueOf(guildId));
        audit.setMetadata(auditMetadata(metadata));
        auditLogs.save(audit);
    }

    private String auditMetadata(Map<String, ?> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Não foi possível registrar a auditoria de IA"
            );
        }
    }

    private long parseGuildId(String guildId) {
        try {
            return Long.parseLong(guildId);
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Servidor não encontrado");
        }
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private record PersistedAiConfig(AiStoredConfig config) {}
}
