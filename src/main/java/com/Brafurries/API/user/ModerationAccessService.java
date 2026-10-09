package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.GuildManagementDtos.*;
import static com.Brafurries.API.user.dto.ModerationAccessDtos.*;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.GuildConfigurationStore.CollaborativeModerationStored;
import com.Brafurries.API.user.GuildConfigurationStore.PortariaBypassStored;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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
public class ModerationAccessService {
    public static final String PARTICIPANT_MODE = "ALL_NON_BOT_MEMBERS";

    private final GuildManagementAccessService access;
    private final GuildConfigurationStore store;
    private final CoddyGuildManagementClient coddy;
    private final CommunityDiscordRepository discordLinks;
    private final CommunityAuditLogRepository auditLogs;
    private final UserRepository users;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public ModerationAccessService(
        GuildManagementAccessService access,
        GuildConfigurationStore store,
        CoddyGuildManagementClient coddy,
        CommunityDiscordRepository discordLinks,
        CommunityAuditLogRepository auditLogs,
        UserRepository users,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        this.access = access;
        this.store = store;
        this.coddy = coddy;
        this.discordLinks = discordLinks;
        this.auditLogs = auditLogs;
        this.users = users;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ModerationAccessResponse get(Authentication authentication, String guildId) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        long id = parseGuildId(guildId);
        return moderationResponse(id, authorized);
    }

    public ModerationAccessResponse updateStaff(
        Authentication authentication,
        String guildId,
        StaffRolesRequest request
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        if (request == null || request.roleIds() == null) {
            throw badRequest("Lista de cargos staff obrigatória");
        }

        List<String> roleIds = request.roleIds().stream().distinct().toList();
        requireSnowflakes(roleIds, "roleId");
        requireGuildRoles(authorized, roleIds);

        long id = parseGuildId(guildId);
        List<String> before = store.readStaffRoleIds(id);
        if (!before.equals(roleIds)) {
            User actor = authenticatedUser(authentication);
            CommunityDiscord link = linkedDiscord(id);
            transactions.executeWithoutResult(status -> {
                store.writeStaffRoleIds(id, roleIds);
                saveAudit(
                    link,
                    actor,
                    "MODERATION_STAFF_ROLES_UPDATED",
                    id,
                    Map.of("changedFields", List.of("roleIds"))
                );
            });
        }
        return moderationResponse(id, authorized);
    }

    public ModerationAccessResponse updateCollaborativeModeration(
        Authentication authentication,
        String guildId,
        CollaborativeModerationUpdateRequest request
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        if (request == null || request.enabled() == null || request.minReactions() == null) {
            throw badRequest("Configuração de moderação colaborativa obrigatória");
        }

        String emoji = request.emoji() == null ? null : request.emoji().trim();
        if (emoji != null && emoji.isEmpty()) emoji = null;
        if (emoji != null && emoji.length() > 64) {
            throw invalid("Emoji da moderação colaborativa é muito longo");
        }
        if (request.enabled() && emoji == null) {
            throw invalid("Escolha um emoji antes de ativar a moderação colaborativa");
        }
        if (request.minReactions() < 1) {
            throw invalid("A quantidade mínima de reações deve ser pelo menos 1");
        }

        long id = parseGuildId(guildId);
        CollaborativeModerationStored before = store.readCollaborativeModeration(id);
        boolean changed = before.enabled() != request.enabled()
            || !Objects.equals(normalizeEmoji(before.emoji()), emoji)
            || before.minReactions() != request.minReactions();

        if (changed) {
            User actor = authenticatedUser(authentication);
            CommunityDiscord link = linkedDiscord(id);
            String finalEmoji = emoji;
            transactions.executeWithoutResult(status -> {
                store.writeCollaborativeModeration(
                    id,
                    request.enabled(),
                    finalEmoji,
                    request.minReactions()
                );
                saveAudit(
                    link,
                    actor,
                    "COLLABORATIVE_MODERATION_UPDATED",
                    id,
                    Map.of("changedFields", changedCollaborativeFields(
                        before,
                        request.enabled(),
                        finalEmoji,
                        request.minReactions()
                    ))
                );
            });
        }
        return moderationResponse(id, authorized);
    }

    public PortariaBypassesResponse bypasses(Authentication authentication, String guildId) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        long id = parseGuildId(guildId);
        User actor = authenticatedUser(authentication);
        CommunityDiscord link = linkedDiscord(id);

        store.migrateLegacyAccountBypasses(id);
        auditDueExpirations(id, actor, link);
        boolean portariaEnabled = store.readPortariaEnabled(id);
        return new PortariaBypassesResponse(
            portariaEnabled,
            store.readPortariaBypasses(id).stream()
                .map(bypass -> toResponse(bypass, portariaEnabled))
                .toList()
        );
    }

    public PortariaBypassResponse createBypass(
        Authentication authentication,
        String guildId,
        PortariaBypassCreateRequest request
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        if (request == null) throw badRequest("Bypass obrigatório");

        String type = normalizeType(request.type());
        String rawValue = request.value() == null ? "" : request.value().trim();
        if (rawValue.isEmpty()) throw invalid("Alvo do bypass obrigatório");

        JsonNode validated = validateLiveTarget(guildId, authorized, type, rawValue);
        String value = validated.path("value").asText("").trim();
        if (value.isEmpty()) throw invalid("O Coddy não confirmou o alvo do bypass");

        long id = parseGuildId(guildId);
        User actor = authenticatedUser(authentication);
        CommunityDiscord link = linkedDiscord(id);
        store.migrateLegacyAccountBypasses(id);
        auditDueExpirations(id, actor, link);

        if (store.findPortariaBypassByValue(id, type, value) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este bypass já existe nesta guild");
        }

        boolean active = request.active() == null || request.active();
        Instant expiresAtInstant = validateExpiration(request.expiresAt(), active);
        LocalDateTime expiresAt = toDatabaseTime(expiresAtInstant);
        String accessMode = "account".equals(type)
            ? normalizeAccessMode(request.accessMode())
            : null;
        boolean requiresForm = "account".equals(type)
            && (request.requiresForm() == null || request.requiresForm());

        Long bypassId = transactions.execute(status -> {
            Long persistedId = "invite".equals(type)
                ? store.upsertInviteBypass(id, value, active, expiresAt)
                : store.upsertAccountBypass(
                    id,
                    Long.parseLong(value),
                    accessMode,
                    requiresForm,
                    active,
                    expiresAt
                );
            saveAudit(
                link,
                actor,
                "PORTARIA_BYPASS_CREATED",
                id,
                Map.of(
                    "bypassType", type,
                    "bypassId", String.valueOf(persistedId),
                    "hasExpiration", expiresAt != null
                )
            );
            return persistedId;
        });
        if (bypassId == null) throw serverError("Não foi possível salvar o bypass");

        PortariaBypassStored stored = store.findPortariaBypass(id, type, bypassId);
        if (stored == null) throw serverError("Bypass salvo não pôde ser relido");
        return toResponse(stored, store.readPortariaEnabled(id));
    }

    public PortariaBypassResponse updateBypass(
        Authentication authentication,
        String guildId,
        String typePath,
        long bypassId,
        PortariaBypassUpdateRequest request
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        String type = normalizeType(typePath);
        long id = parseGuildId(guildId);
        User actor = authenticatedUser(authentication);
        CommunityDiscord link = linkedDiscord(id);
        store.migrateLegacyAccountBypasses(id);
        auditDueExpirations(id, actor, link);

        PortariaBypassStored current = store.findPortariaBypass(id, type, bypassId);
        if (current == null || current.removedAt() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bypass não encontrado nesta guild");
        }
        if (request == null) throw badRequest("Alteração do bypass obrigatória");
        if (Boolean.TRUE.equals(request.clearExpiration()) && request.expiresAt() != null) {
            throw invalid("Não envie expiresAt ao mesmo tempo que clearExpiration");
        }

        boolean active = request.active() == null ? current.active() : request.active();
        if (active && !current.active()) {
            JsonNode validated = validateLiveTarget(guildId, authorized, type, current.value());
            String canonical = validated.path("value").asText("").trim();
            if (canonical.isEmpty() || !canonical.equals(current.value())) {
                throw invalid("O Coddy não confirmou o alvo atual deste bypass");
            }
        }
        Instant currentExpiresAt = toApiTime(current.expiresAt());
        Instant expiresAtInstant = Boolean.TRUE.equals(request.clearExpiration())
            ? null
            : request.expiresAt() != null ? request.expiresAt() : currentExpiresAt;
        expiresAtInstant = validateExpiration(expiresAtInstant, active);
        LocalDateTime expiresAt = toDatabaseTime(expiresAtInstant);

        String accessMode = current.accessMode();
        boolean requiresForm = Boolean.TRUE.equals(current.requiresForm());
        if ("account".equals(type)) {
            accessMode = request.accessMode() == null
                ? normalizeAccessMode(current.accessMode())
                : normalizeAccessMode(request.accessMode());
            requiresForm = request.requiresForm() == null
                ? Boolean.TRUE.equals(current.requiresForm())
                : request.requiresForm();
        } else if (request.accessMode() != null || request.requiresForm() != null) {
            throw invalid("Bypass por convite não possui modo de acesso ou exigência de ficha");
        }

        List<String> changed = new ArrayList<>();
        changed(changed, "active", current.active(), active);
        changed(changed, "expiresAt", current.expiresAt(), expiresAt);
        if ("account".equals(type)) {
            changed(changed, "accessMode", current.accessMode(), accessMode);
            changed(changed, "requiresForm", current.requiresForm(), requiresForm);
        }
        if (changed.isEmpty()) return toResponse(current, store.readPortariaEnabled(id));

        String finalAccessMode = accessMode;
        boolean finalRequiresForm = requiresForm;
        LocalDateTime finalExpiresAt = expiresAt;
        transactions.executeWithoutResult(status -> {
            if ("invite".equals(type)) {
                store.updateInviteBypass(id, bypassId, active, finalExpiresAt);
            } else {
                store.updateAccountBypass(
                    id,
                    bypassId,
                    finalAccessMode,
                    finalRequiresForm,
                    active,
                    finalExpiresAt
                );
            }
            saveAudit(
                link,
                actor,
                "PORTARIA_BYPASS_UPDATED",
                id,
                Map.of(
                    "bypassType", type,
                    "bypassId", String.valueOf(bypassId),
                    "changedFields", List.copyOf(changed)
                )
            );
        });

        PortariaBypassStored updated = store.findPortariaBypass(id, type, bypassId);
        if (updated == null) throw serverError("Bypass atualizado não pôde ser relido");
        return toResponse(updated, store.readPortariaEnabled(id));
    }

    public void removeBypass(
        Authentication authentication,
        String guildId,
        String typePath,
        long bypassId
    ) {
        access.authorize(authentication, guildId);
        String type = normalizeType(typePath);
        long id = parseGuildId(guildId);
        User actor = authenticatedUser(authentication);
        CommunityDiscord link = linkedDiscord(id);
        store.migrateLegacyAccountBypasses(id);

        PortariaBypassStored current = store.findPortariaBypass(id, type, bypassId);
        if (current == null || current.removedAt() != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bypass não encontrado nesta guild");
        }

        transactions.executeWithoutResult(status -> {
            if (!store.removePortariaBypass(id, type, bypassId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bypass não encontrado nesta guild");
            }
            saveAudit(
                link,
                actor,
                "PORTARIA_BYPASS_REMOVED",
                id,
                Map.of(
                    "bypassType", type,
                    "bypassId", String.valueOf(bypassId)
                )
            );
        });
    }

    private ModerationAccessResponse moderationResponse(long guildId, AuthorizedGuild authorized) {
        List<String> staffIds = store.readStaffRoleIds(guildId);
        StaffRolesResponse staff = new StaffRolesResponse(
            staffIds,
            authorized.resources().roles().stream()
                .filter(role -> staffIds.contains(role.id()))
                .toList()
        );
        CollaborativeModerationStored collaborative = store.readCollaborativeModeration(guildId);
        return new ModerationAccessResponse(
            String.valueOf(guildId),
            staff,
            new CollaborativeModerationResponse(
                collaborative.enabled(),
                normalizeEmoji(collaborative.emoji()),
                collaborative.minReactions(),
                PARTICIPANT_MODE,
                staffIds
            )
        );
    }

    private void auditDueExpirations(long guildId, User actor, CommunityDiscord link) {
        transactions.executeWithoutResult(status -> {
            List<PortariaBypassStored> expired = store.expireDuePortariaBypasses(guildId);
            for (PortariaBypassStored bypass : expired) {
                saveAudit(
                    link,
                    actor,
                    "PORTARIA_BYPASS_EXPIRED",
                    guildId,
                    Map.of(
                        "bypassType", bypass.type(),
                        "bypassId", String.valueOf(bypass.id()),
                        "automatic", true
                    )
                );
            }
        });
    }

    private JsonNode validateLiveTarget(
        String guildId,
        AuthorizedGuild authorized,
        String type,
        String value
    ) {
        return coddy.operation(
            guildId,
            authorized.discordUserId(),
            Map.of(
                "operation", "portaria-bypass-validate",
                "bypassType", type,
                "value", value
            )
        );
    }

    private void requireGuildRoles(AuthorizedGuild authorized, List<String> ids) {
        Set<String> available = new java.util.HashSet<>(
            authorized.resources().roles().stream().map(GuildRole::id).toList()
        );
        if (!available.containsAll(ids)) {
            throw invalid("Cargo de staff inválido ou pertencente a outra guild");
        }
    }

    private void requireSnowflakes(List<String> values, String field) {
        if (values.stream().anyMatch(value -> value == null || !value.matches("[1-9]\\d*"))) {
            throw invalid(field + " deve ser numérico e positivo");
        }
    }

    private String normalizeType(String raw) {
        String type = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("invite", "account").contains(type)) {
            throw invalid("Tipo de bypass deve ser invite ou account");
        }
        return type;
    }

    private String normalizeAccessMode(String raw) {
        String mode = raw == null || raw.isBlank()
            ? "completo"
            : raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("provisorio", "completo").contains(mode)) {
            throw invalid("Modo de acesso deve ser provisorio ou completo");
        }
        return mode;
    }

    private Instant validateExpiration(Instant expiresAt, boolean active) {
        if (active && expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw invalid("A expiração de um bypass ativo precisa estar no futuro");
        }
        return expiresAt;
    }

    private LocalDateTime toDatabaseTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private Instant toApiTime(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private PortariaBypassResponse toResponse(
        PortariaBypassStored stored,
        boolean portariaEnabled
    ) {
        Instant expiresAt = toApiTime(stored.expiresAt());
        boolean expired = stored.expiredAt() != null
            || (expiresAt != null && !expiresAt.isAfter(Instant.now()));
        boolean configuredActive = stored.active() && stored.removedAt() == null;
        boolean effective = portariaEnabled && configuredActive && !expired;
        return new PortariaBypassResponse(
            stored.id(),
            stored.type(),
            stored.value(),
            stored.accessMode(),
            stored.requiresForm(),
            configuredActive,
            effective,
            expiresAt,
            expired,
            toApiTime(stored.createdAt()),
            toApiTime(stored.updatedAt())
        );
    }

    private List<String> changedCollaborativeFields(
        CollaborativeModerationStored before,
        boolean enabled,
        String emoji,
        int minReactions
    ) {
        List<String> changed = new ArrayList<>();
        changed(changed, "enabled", before.enabled(), enabled);
        changed(changed, "emoji", normalizeEmoji(before.emoji()), emoji);
        changed(changed, "minReactions", before.minReactions(), minReactions);
        return List.copyOf(changed);
    }

    private void changed(List<String> values, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) values.add(field);
    }

    private String normalizeEmoji(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
        try {
            java.util.LinkedHashMap<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("guildId", String.valueOf(guildId));
            payload.putAll(metadata);
            audit.setMetadata(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            throw serverError("Não foi possível registrar a auditoria");
        }
        auditLogs.save(audit);
    }

    private long parseGuildId(String guildId) {
        try {
            return Long.parseLong(guildId);
        } catch (NumberFormatException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Servidor não encontrado");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private ResponseStatusException serverError(String message) {
        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
