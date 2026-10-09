package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.VipConfigurationDtos.*;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.GuildConfigurationStore.VipStoredConfig;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.Brafurries.API.user.dto.GuildManagementDtos.BotTopRole;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VipConfigurationService {
    private static final Logger log = LoggerFactory.getLogger(VipConfigurationService.class);
    private static final String DEFAULT_PREFIX = "VIP";

    private final GuildManagementAccessService access;
    private final GuildConfigurationStore store;
    private final CommunityDiscordRepository discordLinks;
    private final CommunityAuditLogRepository auditLogs;
    private final UserRepository users;
    private final CoddyGuildManagementClient coddy;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public VipConfigurationService(
        GuildManagementAccessService access,
        GuildConfigurationStore store,
        CommunityDiscordRepository discordLinks,
        CommunityAuditLogRepository auditLogs,
        UserRepository users,
        CoddyGuildManagementClient coddy,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        this.access = access;
        this.store = store;
        this.discordLinks = discordLinks;
        this.auditLogs = auditLogs;
        this.users = users;
        this.coddy = coddy;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public VipConfigResponse get(Authentication authentication, String guildId) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        long numericGuildId = parseGuildId(guildId);
        VipStoredConfig stored = store.readVipConfig(numericGuildId);
        return toResponse(
            numericGuildId,
            stored,
            authorized,
            stored.reconcileRequired(),
            List.of()
        );
    }

    public VipConfigResponse update(
        Authentication authentication,
        String guildId,
        VipConfigUpdateRequest request
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        long numericGuildId = parseGuildId(guildId);
        VipStoredConfig current = store.readVipConfig(numericGuildId);

        List<String> roleIds = normalizeRoleIds(request.roleIds());
        String prefix = normalizePrefix(request.customRolePrefix());
        String topRoleId = normalizeOptionalSnowflake(request.customRoleRange().topRoleId());
        String bottomRoleId = normalizeOptionalSnowflake(request.customRoleRange().bottomRoleId());

        validate(authorized, roleIds, topRoleId, bottomRoleId);

        List<String> changed = changedFields(
            current,
            roleIds,
            prefix,
            topRoleId,
            bottomRoleId,
            request.allowStaffColors()
        );

        VipStoredConfig persisted = current;
        if (!changed.isEmpty()) {
            User actor = authenticatedUser(authentication);
            CommunityDiscord link = linkedDiscord(numericGuildId);
            persisted = transactions.execute(status -> {
                store.writeVipConfig(
                    numericGuildId,
                    roleIds,
                    prefix,
                    topRoleId,
                    bottomRoleId,
                    request.allowStaffColors()
                );
                VipStoredConfig saved = store.readVipConfig(numericGuildId);
                saveAudit(link, actor, numericGuildId, changed);
                return saved;
            });
            if (persisted == null) {
                throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Não foi possível salvar a configuração VIP"
                );
            }
        } else if (!current.reconcileRequired()) {
            return toResponse(numericGuildId, current, authorized, false, List.of());
        }

        try {
            JsonNode runtime = coddy.reconcileVip(guildId, authorized.discordUserId());
            boolean acknowledged = store.markVipReconciled(
                numericGuildId,
                persisted.reconcileRevision()
            );
            VipStoredConfig latest = store.readVipConfig(numericGuildId);
            List<String> warnings = new ArrayList<>(runtimeWarnings(runtime));
            if (!acknowledged && latest.reconcileRequired()) {
                warnings.add(
                    "Uma configuração VIP mais nova foi salva durante a reconciliação; o Coddy ainda precisa confirmar a versão atual."
                );
            }
            return toResponse(
                numericGuildId,
                latest,
                authorized,
                latest.reconcileRequired(),
                List.copyOf(warnings)
            );
        } catch (ResponseStatusException exception) {
            log.warn(
                "VIP config saved but runtime reconcile failed: guildId={}, status={}",
                guildId,
                exception.getStatusCode().value()
            );
            return toResponse(
                numericGuildId,
                persisted,
                authorized,
                true,
                List.of(
                    "Configuração salva, mas o Coddy não confirmou a reconciliação dos cargos VIP agora."
                )
            );
        }
    }

    private void validate(
        AuthorizedGuild authorized,
        List<String> roleIds,
        String topRoleId,
        String bottomRoleId
    ) {
        Map<String, GuildRole> roles = new LinkedHashMap<>();
        for (GuildRole role : authorized.resources().roles()) {
            roles.put(role.id(), role);
        }

        for (String roleId : roleIds) {
            if (!roles.containsKey(roleId)) {
                throw invalid("Cargo VIP inválido ou pertencente a outro servidor");
            }
        }

        if (bottomRoleId != null && topRoleId == null) {
            throw invalid("A âncora inferior exige uma âncora superior");
        }
        if (topRoleId == null) {
            return;
        }
        if (topRoleId.equals(bottomRoleId)) {
            throw invalid("As âncoras superior e inferior precisam ser diferentes");
        }

        GuildRole top = roles.get(topRoleId);
        GuildRole bottom = bottomRoleId == null ? null : roles.get(bottomRoleId);
        if (top == null || (bottomRoleId != null && bottom == null)) {
            throw invalid("Âncora VIP inválida ou pertencente a outro servidor");
        }

        if (!authorized.resources().botCapabilities().canManageRoles()) {
            throw invalid("O Coddy não possui Manage Roles para organizar cargos VIP");
        }

        BotTopRole botTop = authorized.resources().botCapabilities().botTopRole();
        Integer topPosition = top.position();
        Integer botPosition = botTop == null ? null : botTop.position();
        if (topPosition == null || botPosition == null || topPosition >= botPosition) {
            throw invalid("O Coddy não possui hierarquia suficiente para usar a âncora superior escolhida");
        }
        if (topPosition <= 1) {
            throw invalid("A âncora superior não deixa uma posição válida para cargos VIP");
        }

        if (bottom != null) {
            Integer bottomPosition = bottom.position();
            if (bottomPosition == null || bottomPosition >= topPosition - 1) {
                throw invalid("A faixa VIP precisa ter ao menos uma posição entre as âncoras");
            }
        }
    }

    private List<String> normalizeRoleIds(List<String> raw) {
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lista de cargos VIP obrigatória");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : raw) {
            String normalized = normalizeOptionalSnowflake(value);
            if (normalized == null) {
                throw invalid("roleId deve ser numérico e positivo");
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private String normalizeOptionalSnowflake(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim();
        if (!normalized.matches("[1-9]\\d*")) {
            throw invalid("ID de cargo deve ser numérico e positivo");
        }
        return normalized;
    }

    private String normalizePrefix(String raw) {
        String prefix = raw == null ? "" : raw.trim();
        if (prefix.isEmpty() || prefix.length() > 67) {
            throw invalid("Prefixo VIP deve ter entre 1 e 67 caracteres");
        }
        if (prefix.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("Prefixo VIP não pode conter quebras de linha ou caracteres de controle");
        }
        return prefix;
    }

    private List<String> changedFields(
        VipStoredConfig current,
        List<String> roleIds,
        String prefix,
        String topRoleId,
        String bottomRoleId,
        boolean allowStaffColors
    ) {
        List<String> changed = new ArrayList<>();
        changed(changed, "roleIds", current.roleIds(), roleIds);
        changed(changed, "customRolePrefix", effectivePrefix(current.customRolePrefix()), prefix);
        changed(changed, "customRoleRange.topRoleId", current.topRoleId(), topRoleId);
        changed(changed, "customRoleRange.bottomRoleId", current.bottomRoleId(), bottomRoleId);
        changed(changed, "allowStaffColors", current.allowStaffColors(), allowStaffColors);
        return List.copyOf(changed);
    }

    private void changed(List<String> fields, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            fields.add(field);
        }
    }

    private List<String> runtimeWarnings(JsonNode runtime) {
        if (runtime == null || !runtime.path("warnings").isArray()) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        for (JsonNode warning : runtime.path("warnings")) {
            String code = warning.asText("");
            if ("vip_roles_unavailable".equals(code)) {
                warnings.add(
                    "Nenhum cargo que concede VIP pôde ser resolvido no Discord; os cargos personalizados existentes foram preservados."
                );
            } else if (!code.isBlank()) {
                warnings.add("O Coddy concluiu a reconciliação VIP com aviso: " + code);
            }
        }
        return List.copyOf(warnings);
    }

    private VipConfigResponse toResponse(
        long guildId,
        VipStoredConfig stored,
        AuthorizedGuild authorized,
        boolean runtimeSyncPending,
        List<String> warnings
    ) {
        List<GuildRole> selectedRoles = authorized.resources().roles().stream()
            .filter(role -> stored.roleIds().contains(role.id()))
            .toList();
        return new VipConfigResponse(
            String.valueOf(guildId),
            stored.roleIds(),
            selectedRoles,
            effectivePrefix(stored.customRolePrefix()),
            new VipCustomRoleRange(stored.topRoleId(), stored.bottomRoleId()),
            stored.allowStaffColors(),
            runtimeSyncPending,
            warnings
        );
    }

    private String effectivePrefix(String value) {
        return value == null || value.isBlank() ? DEFAULT_PREFIX : value.trim();
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
        long guildId,
        List<String> changedFields
    ) {
        CommunityAuditLog audit = new CommunityAuditLog();
        audit.setCommunity(link.getCommunity());
        audit.setActorUser(actor);
        audit.setAction("VIP_CONFIG_UPDATED");
        audit.setTargetType("DISCORD_GUILD");
        audit.setTargetId(String.valueOf(guildId));
        try {
            audit.setMetadata(objectMapper.writeValueAsString(Map.of(
                "guildId", String.valueOf(guildId),
                "changedFields", changedFields
            )));
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Não foi possível registrar a auditoria VIP"
            );
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

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
