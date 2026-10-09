package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.ThemeDtos.*;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.Brafurries.API.user.ThemeControlPlaneStore.ApplicationStored;
import com.Brafurries.API.user.ThemeControlPlaneStore.OperationStepStored;
import com.Brafurries.API.user.ThemeControlPlaneStore.OperationStored;
import com.Brafurries.API.user.ThemeControlPlaneStore.ResourceChangeStored;
import com.Brafurries.API.user.ThemeControlPlaneStore.ResourceWrite;
import com.Brafurries.API.user.ThemeControlPlaneStore.ThemeAssetStored;
import com.Brafurries.API.user.ThemeControlPlaneStore.ThemeStored;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildCategory;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildChannel;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildResources;
import com.Brafurries.API.user.dto.GuildManagementDtos.GuildRole;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ThemeControlPlaneService {
    private static final Logger log = LoggerFactory.getLogger(ThemeControlPlaneService.class);
    private static final Set<String> ASSET_ACTIONS = Set.of("UNCHANGED", "SET", "REMOVE");
    private static final Set<String> RESOURCE_TYPES = Set.of("CHANNEL", "CATEGORY", "ROLE");
    private static final Set<String> TERMINAL_OPERATION_STATUSES =
        Set.of("SUCCEEDED", "PARTIAL", "FAILED");
    private static final Pattern IDEMPOTENCY_KEY =
        Pattern.compile("[A-Za-z0-9._:-]{1,96}");

    private final GuildManagementAccessService access;
    private final ThemeControlPlaneStore store;
    private final CoddyGuildManagementClient coddy;
    private final ThemeAssetStorageService assets;
    private final CommunityDiscordRepository discordLinks;
    private final CommunityAuditLogRepository auditLogs;
    private final UserRepository users;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public ThemeControlPlaneService(
        GuildManagementAccessService access,
        ThemeControlPlaneStore store,
        CoddyGuildManagementClient coddy,
        ThemeAssetStorageService assets,
        CommunityDiscordRepository discordLinks,
        CommunityAuditLogRepository auditLogs,
        UserRepository users,
        ObjectMapper objectMapper,
        PlatformTransactionManager transactionManager
    ) {
        this.access = access;
        this.store = store;
        this.coddy = coddy;
        this.assets = assets;
        this.discordLinks = discordLinks;
        this.auditLogs = auditLogs;
        this.users = users;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ThemesResponse list(Authentication authentication, String guildId) {
        access.authorize(authentication, guildId);
        long guild = parseGuildId(guildId);
        ApplicationStored active = store.findActiveApplication(guild);
        if (active != null) {
            auditLatestOperationIfNeeded(guild, active);
            cleanupRollbackAssetsIfEligible(guild, active);
            active = store.findApplication(guild, active.id());
        }
        return new ThemesResponse(
            store.listThemes(guild).stream().map(this::toThemeResponse).toList(),
            active == null ? null : toApplicationResponse(active)
        );
    }

    public ThemeResponse get(
        Authentication authentication,
        String guildId,
        long themeId
    ) {
        access.authorize(authentication, guildId);
        return toThemeResponse(requireTheme(parseGuildId(guildId), themeId));
    }

    public ThemeResourcesResponse resources(
        Authentication authentication,
        String guildId
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        long guild = parseGuildId(guildId);
        GuildResources live = authorized.resources();
        Set<Long> vipCustomRoleIds = store.vipCustomRoleIds(guild);

        List<ThemeEditableRole> roles = live.roles().stream().map(role -> {
            String reason = unavailableRoleReason(guild, live, role, vipCustomRoleIds);
            return new ThemeEditableRole(
                role.id(),
                role.name(),
                role.position(),
                reason == null,
                reason
            );
        }).toList();

        return new ThemeResourcesResponse(
            live.channels().stream()
                .map(channel -> new ThemeEditableChannel(
                    channel.id(), channel.name(), channel.type(), channel.categoryId()
                ))
                .toList(),
            live.categories().stream()
                .map(category -> new ThemeEditableCategory(category.id(), category.name()))
                .toList(),
            roles
        );
    }

    public ThemeResponse create(
        Authentication authentication,
        String guildId,
        ThemeSaveRequest request
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        long guild = parseGuildId(guildId);
        NormalizedTheme normalized = normalizeTheme(
            guild,
            authorized.resources(),
            request,
            store.vipCustomRoleIds(guild)
        );
        User actor = authenticatedUser(authentication);
        long themeId;
        try {
            themeId = transactions.execute(status -> {
                long created = store.createTheme(
                    guild,
                    normalized.name(),
                    normalized.description(),
                    authorized.discordUserId(),
                    actor.getId(),
                    normalized.iconAction(),
                    normalized.bannerAction(),
                    normalized.resources()
                );
                saveAudit(
                    linkedDiscord(guild),
                    actor,
                    "THEME_CREATED",
                    "DISCORD_THEME",
                    String.valueOf(created),
                    Map.of(
                        "guildId", String.valueOf(guild),
                        "themeId", created,
                        "changedResourceCount", normalized.resources().size()
                    )
                );
                return created;
            });
        } catch (DuplicateKeyException ex) {
            throw conflict("Já existe um Theme com esse nome nesta guild");
        }
        return toThemeResponse(requireTheme(guild, themeId));
    }

    public ThemeResponse update(
        Authentication authentication,
        String guildId,
        long themeId,
        ThemeSaveRequest request
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        long guild = parseGuildId(guildId);
        ThemeStored current = requireTheme(guild, themeId);
        NormalizedTheme normalized = normalizeTheme(
            guild,
            authorized.resources(),
            request,
            store.vipCustomRoleIds(guild)
        );
        if ("SET".equals(normalized.iconAction()) && current.iconAssetKey() == null) {
            throw invalid("Envie o ícone do Theme antes de marcar a ação como SET");
        }
        if ("SET".equals(normalized.bannerAction()) && current.bannerAssetKey() == null) {
            throw invalid("Envie o banner do Theme antes de marcar a ação como SET");
        }

        User actor = authenticatedUser(authentication);
        try {
            transactions.executeWithoutResult(status -> {
                if (!store.updateTheme(
                    guild,
                    themeId,
                    normalized.name(),
                    normalized.description(),
                    normalized.iconAction(),
                    normalized.bannerAction(),
                    normalized.resources()
                )) {
                    throw notFound("Theme não encontrado nesta guild");
                }
                saveAudit(
                    linkedDiscord(guild),
                    actor,
                    "THEME_UPDATED",
                    "DISCORD_THEME",
                    String.valueOf(themeId),
                    Map.of(
                        "guildId", String.valueOf(guild),
                        "themeId", themeId,
                        "changedResourceCount", normalized.resources().size()
                    )
                );
            });
        } catch (DuplicateKeyException ex) {
            throw conflict("Já existe um Theme com esse nome nesta guild");
        }
        return toThemeResponse(requireTheme(guild, themeId));
    }

    public void delete(
        Authentication authentication,
        String guildId,
        long themeId
    ) {
        access.authorize(authentication, guildId);
        long guild = parseGuildId(guildId);
        ThemeStored theme = store.findThemeIncludingDeleted(guild, themeId);
        if (theme == null) {
            throw notFound("Theme não encontrado nesta guild");
        }

        if (theme.deletedAt() == null) {
            User actor = authenticatedUser(authentication);
            transactions.executeWithoutResult(status -> {
                if (!store.softDeleteTheme(guild, themeId)) {
                    ApplicationStored active = store.findActiveApplication(guild);
                    if (active != null && active.themeId() == themeId) {
                        throw conflict("Restaure o Theme ativo antes de excluí-lo");
                    }
                    throw notFound("Theme não encontrado nesta guild");
                }
                saveAudit(
                    linkedDiscord(guild),
                    actor,
                    "THEME_DELETED",
                    "DISCORD_THEME",
                    String.valueOf(themeId),
                    Map.of(
                        "guildId", String.valueOf(guild),
                        "themeId", themeId
                    )
                );
            });
        }

        cleanupDeletedThemeAssets(guild, themeId);
    }

    private void cleanupDeletedThemeAssets(long guild, long themeId) {
        for (ThemeAssetStored asset : store.listThemeAssets(guild, themeId)) {
            try {
                assets.deleteDefinition(guild, themeId, asset.storageKey());
                store.deleteThemeAsset(guild, themeId, asset.id());
            } catch (RuntimeException failure) {
                log.warn(
                    "Theme asset cleanup failed after delete; retry remains available: guild={} theme={} asset={}",
                    guild,
                    themeId,
                    asset.id(),
                    failure
                );
            }
        }
    }

    public ThemeResponse uploadAsset(
        Authentication authentication,
        String guildId,
        long themeId,
        String assetType,
        MultipartFile file
    ) {
        access.authorize(authentication, guildId);
        long guild = parseGuildId(guildId);
        requireTheme(guild, themeId);
        String type = assets.normalizeAssetType(assetType);
        var stored = assets.uploadDefinition(guild, themeId, type, file);
        boolean committed = false;
        try {
            transactions.executeWithoutResult(status -> {
                long assetId = store.recordThemeAsset(
                    guild,
                    themeId,
                    type,
                    stored.key(),
                    stored.contentType(),
                    stored.sha256(),
                    stored.sizeBytes()
                );
                if (assetId <= 0 || !store.setThemeAsset(
                    guild,
                    themeId,
                    type,
                    stored.key(),
                    stored.contentType(),
                    stored.sha256(),
                    stored.sizeBytes()
                )) {
                    throw notFound("Theme não encontrado nesta guild");
                }
            });
            committed = true;
        } finally {
            if (!committed) {
                try {
                    assets.deleteDefinition(guild, themeId, stored.key());
                } catch (RuntimeException cleanupFailure) {
                    log.warn(
                        "Could not cleanup uncommitted Theme asset: guild={} theme={}",
                        guild,
                        themeId,
                        cleanupFailure
                    );
                }
            }
        }
        return toThemeResponse(requireTheme(guild, themeId));
    }

    public ThemePreviewResponse preview(
        Authentication authentication,
        String guildId,
        long themeId
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        long guild = parseGuildId(guildId);
        ThemeStored theme = requireTheme(guild, themeId);
        requireCompleteAssets(theme);
        JsonNode preview = coddy.themePreview(
            guildId,
            authorized.discordUserId(),
            buildFrozenDefinition(theme)
        );
        validatePreviewIdentity(preview, guild, themeId);
        return toPreviewResponse(preview);
    }

    public ThemeOperationResponse apply(
        Authentication authentication,
        String guildId,
        long themeId,
        ThemeApplyRequest request,
        String idempotencyKey
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        if (request == null || !Boolean.TRUE.equals(request.confirmed())) {
            throw invalid("Confirmação explícita para aplicar o Theme é obrigatória");
        }
        long guild = parseGuildId(guildId);
        String key = validateIdempotencyKey(idempotencyKey);
        User actor = authenticatedUser(authentication);

        OperationStored existing = store.findOperationByKey(guild, key);
        if (existing != null) {
            validateOperationRetry(
                existing,
                "APPLY",
                themeId,
                authorized.discordUserId(),
                null
            );
            auditRequestedIfNeeded(guild, existing, actor);
            return dispatchIfPending(guildId, authorized.discordUserId(), existing);
        }

        ThemeStored theme = requireTheme(guild, themeId);
        requireCompleteAssets(theme);
        if (store.findActiveApplication(guild) != null) {
            throw conflict("Restaure o Theme ativo antes de aplicar outro");
        }
        String frozen = writeJson(buildFrozenDefinition(theme));

        OperationStored operation;
        try {
            operation = transactions.execute(status -> store.createApplyApplication(
                guild,
                themeId,
                authorized.discordUserId(),
                actor.getId(),
                key,
                frozen
            ));
        } catch (DuplicateKeyException duplicate) {
            OperationStored winner = store.findOperationByKey(guild, key);
            if (winner == null) {
                throw conflict("Já existe um Theme ativo ou sendo aplicado nesta guild");
            }
            operation = winner;
        }
        if (operation == null) {
            throw notFound("Theme não encontrado nesta guild");
        }
        validateOperationRetry(
            operation,
            "APPLY",
            themeId,
            authorized.discordUserId(),
            null
        );
        auditRequestedIfNeeded(guild, operation, actor);
        return dispatchIfPending(guildId, authorized.discordUserId(), operation);
    }

    public ThemeApplicationsResponse applications(
        Authentication authentication,
        String guildId
    ) {
        access.authorize(authentication, guildId);
        long guild = parseGuildId(guildId);
        List<ApplicationStored> values = store.listApplications(guild);
        values.forEach(app -> {
            auditLatestOperationIfNeeded(guild, app);
            cleanupRollbackAssetsIfEligible(guild, app);
        });
        return new ThemeApplicationsResponse(
            values.stream()
                .map(app -> {
                    ApplicationStored refreshed = store.findApplication(guild, app.id());
                    return toApplicationResponse(refreshed == null ? app : refreshed);
                })
                .toList()
        );
    }

    public ThemeApplicationResponse activeApplication(
        Authentication authentication,
        String guildId
    ) {
        access.authorize(authentication, guildId);
        long guild = parseGuildId(guildId);
        ApplicationStored active = store.findActiveApplication(guild);
        if (active == null) return null;
        auditLatestOperationIfNeeded(guild, active);
        return toApplicationResponse(active);
    }

    public ThemeApplicationResponse application(
        Authentication authentication,
        String guildId,
        long applicationId
    ) {
        access.authorize(authentication, guildId);
        long guild = parseGuildId(guildId);
        ApplicationStored application = requireApplication(guild, applicationId);
        auditLatestOperationIfNeeded(guild, application);
        cleanupRollbackAssetsIfEligible(guild, application);
        ApplicationStored refreshed = store.findApplication(guild, applicationId);
        return toApplicationResponse(refreshed == null ? application : refreshed);
    }

    public ThemeOperationResponse restore(
        Authentication authentication,
        String guildId,
        long applicationId,
        ThemeRestoreRequest request,
        String idempotencyKey
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        if (request == null || !Boolean.TRUE.equals(request.confirmed())) {
            throw invalid("Confirmação explícita para restaurar o Theme é obrigatória");
        }
        long guild = parseGuildId(guildId);
        String key = validateIdempotencyKey(idempotencyKey);
        User actor = authenticatedUser(authentication);

        OperationStored existing = store.findOperationByKey(guild, key);
        if (existing != null) {
            validateOperationRetry(
                existing,
                "RESTORE",
                null,
                authorized.discordUserId(),
                Boolean.TRUE.equals(request.force())
            );
            if (existing.applicationId() != applicationId) {
                throw conflict("Idempotency-Key pertence a outra aplicação de Theme");
            }
            auditRequestedIfNeeded(guild, existing, actor);
            return dispatchIfPending(guildId, authorized.discordUserId(), existing);
        }

        ApplicationStored active = store.findActiveApplication(guild);
        if (active == null || active.id() != applicationId) {
            throw conflict("A aplicação informada não é o Theme ativo desta guild");
        }
        if (!Set.of("ACTIVE", "APPLY_PARTIAL", "RESTORE_PARTIAL").contains(active.status())) {
            throw conflict("A aplicação de Theme ainda não está pronta para restore");
        }

        OperationStored operation;
        try {
            operation = store.createRestoreOperation(
                guild,
                applicationId,
                authorized.discordUserId(),
                actor.getId(),
                key,
                Boolean.TRUE.equals(request.force())
            );
        } catch (DuplicateKeyException duplicate) {
            operation = store.findOperationByKey(guild, key);
        }
        if (operation == null) {
            throw conflict("A aplicação informada não é o Theme ativo desta guild");
        }
        validateOperationRetry(
            operation,
            "RESTORE",
            null,
            authorized.discordUserId(),
            Boolean.TRUE.equals(request.force())
        );
        auditRequestedIfNeeded(guild, operation, actor);
        return dispatchIfPending(guildId, authorized.discordUserId(), operation);
    }

    private ThemeOperationResponse dispatchIfPending(
        String guildId,
        long actorDiscordUserId,
        OperationStored operation
    ) {
        if ("PENDING".equals(operation.status())) {
            if ("APPLY".equals(operation.operationType())) {
                coddy.dispatchThemeApply(
                    guildId,
                    actorDiscordUserId,
                    operation.applicationId(),
                    operation.id()
                );
            } else {
                coddy.dispatchThemeRestore(
                    guildId,
                    actorDiscordUserId,
                    operation.applicationId(),
                    operation.id()
                );
            }
            OperationStored refreshed = store.findOperation(
                operation.guildId(),
                operation.id()
            );
            if (refreshed != null) operation = refreshed;
        }
        return toOperationResponse(operation);
    }

    private void auditRequestedIfNeeded(long guildId, OperationStored operation, User actor) {
        String action = "APPLY".equals(operation.operationType())
            ? "THEME_APPLY_REQUESTED"
            : "THEME_RESTORE_REQUESTED";
        CommunityDiscord discord = linkedDiscord(guildId);
        transactions.executeWithoutResult(status -> {
            if (!store.claimOperationRequestedAudit(guildId, operation.id())) return;
            saveAudit(
                discord,
                actor,
                action,
                "THEME_APPLICATION",
                String.valueOf(operation.applicationId()),
                Map.of(
                    "guildId", String.valueOf(guildId),
                    "applicationId", operation.applicationId(),
                    "operationId", operation.id(),
                    "status", operation.status()
                )
            );
        });
    }

    private void auditLatestOperationIfNeeded(long guildId, ApplicationStored application) {
        OperationStored operation = store.latestOperation(application.id());
        if (operation == null || !TERMINAL_OPERATION_STATUSES.contains(operation.status())) return;

        User actor = operation.actorUserId() == null
            ? null
            : users.findById(operation.actorUserId()).orElse(null);
        if (actor == null) {
            log.warn(
                "Theme completion audit remains unclaimed because actor is unavailable: guild={} operation={}",
                guildId,
                operation.id()
            );
            return;
        }

        String action;
        if ("APPLY".equals(operation.operationType())) {
            action = switch (operation.status()) {
                case "SUCCEEDED" -> "THEME_APPLIED";
                case "PARTIAL" -> "THEME_APPLY_PARTIAL";
                default -> "THEME_APPLY_FAILED";
            };
        } else {
            action = switch (operation.status()) {
                case "SUCCEEDED" -> "THEME_RESTORED";
                case "PARTIAL" -> "THEME_RESTORE_PARTIAL";
                default -> "THEME_RESTORE_FAILED";
            };
        }

        CommunityDiscord discord = linkedDiscord(guildId);
        transactions.executeWithoutResult(status -> {
            if (!store.claimOperationCompletionAudit(guildId, operation.id())) return;
            saveAudit(
                discord,
                actor,
                action,
                "THEME_APPLICATION",
                String.valueOf(application.id()),
                Map.of(
                    "guildId", String.valueOf(guildId),
                    "themeId", application.themeId(),
                    "applicationId", application.id(),
                    "operationId", operation.id(),
                    "status", operation.status(),
                    "changedResourceCount", frozenChangeCount(application.frozenDefinitionJson())
                )
            );
        });
    }

    private void cleanupRollbackAssetsIfEligible(long guildId, ApplicationStored application) {
        boolean fullyRestored = "RESTORED".equals(application.status());
        boolean failedWithoutEffects = "FAILED".equals(application.status())
            && !store.hasAppliedSnapshots(guildId, application.id());
        if ((!fullyRestored && !failedWithoutEffects)
            || application.rollbackAssetsCleanedAt() != null) {
            return;
        }
        boolean complete = true;
        for (var asset : store.listRollbackAssets(guildId, application.id())) {
            try {
                assets.deleteRollback(guildId, application.id(), asset.key());
            } catch (RuntimeException failure) {
                complete = false;
                log.warn(
                    "Theme rollback asset cleanup failed: guild={} application={} snapshot={}",
                    guildId,
                    application.id(),
                    asset.id(),
                    failure
                );
            }
        }
        if (complete) {
            store.markRollbackAssetsCleaned(guildId, application.id());
        }
    }

    private NormalizedTheme normalizeTheme(
        long guildId,
        GuildResources live,
        ThemeSaveRequest request,
        Set<Long> vipCustomRoleIds
    ) {
        if (request == null) throw badRequest("Theme obrigatório");
        String name = trimRequired(request.name(), "Nome do Theme");
        String description = trimOptional(request.description());
        String iconAction = normalizeAction(request.iconAction());
        String bannerAction = normalizeAction(request.bannerAction());

        List<ResourceWrite> resources = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, GuildChannel> channels = new HashMap<>();
        for (GuildChannel channel : live.channels()) channels.put(channel.id(), channel);
        Map<String, GuildCategory> categories = new HashMap<>();
        for (GuildCategory category : live.categories()) categories.put(category.id(), category);
        Map<String, GuildRole> roles = new HashMap<>();
        for (GuildRole role : live.roles()) roles.put(role.id(), role);

        for (ThemeResourceRequest item : request.resources()) {
            String type = normalizeResourceType(item.resourceType());
            String id = item.resourceId().trim();
            String target = trimRequired(item.targetName(), "Nome de destino");
            String identity = type + ":" + id;
            if (!seen.add(identity)) {
                throw invalid("O mesmo recurso não pode aparecer duas vezes no Theme");
            }
            switch (type) {
                case "CHANNEL" -> {
                    if (!channels.containsKey(id)) {
                        throw invalid("Canal não pertence a esta guild ou não existe");
                    }
                }
                case "CATEGORY" -> {
                    if (!categories.containsKey(id)) {
                        throw invalid("Categoria não pertence a esta guild ou não existe");
                    }
                }
                case "ROLE" -> {
                    GuildRole role = roles.get(id);
                    if (role == null) {
                        throw invalid("Cargo não pertence a esta guild ou não existe");
                    }
                    String reason = unavailableRoleReason(
                        guildId,
                        live,
                        role,
                        vipCustomRoleIds
                    );
                    if (reason != null) {
                        throw invalid("Cargo não disponível para Theme: " + reason);
                    }
                }
                default -> throw invalid("Tipo de recurso inválido");
            }
            resources.add(new ResourceWrite(type, Long.parseLong(id), target));
        }

        if (resources.isEmpty()
            && "UNCHANGED".equals(iconAction)
            && "UNCHANGED".equals(bannerAction)) {
            throw invalid("Theme precisa configurar pelo menos uma alteração visual");
        }

        return new NormalizedTheme(
            name,
            description,
            iconAction,
            bannerAction,
            List.copyOf(resources)
        );
    }

    private String unavailableRoleReason(
        long guildId,
        GuildResources live,
        GuildRole role,
        Set<Long> vipCustomRoleIds
    ) {
        if (String.valueOf(guildId).equals(role.id())) return "EVERYONE";
        long roleId;
        try {
            roleId = Long.parseLong(role.id());
        } catch (NumberFormatException invalidId) {
            return "INVALID_ID";
        }
        if (vipCustomRoleIds.contains(roleId)) return "CODDY_VIP_CUSTOM_ROLE";
        if (role.managed()) return "MANAGED_BY_DISCORD";
        if (!role.editableByBot()) return "HIERARCHY_OR_PERMISSION";
        if (live.botCapabilities() != null && !live.botCapabilities().canManageRoles()) {
            return "BOT_MISSING_MANAGE_ROLES";
        }
        return null;
    }

    private ObjectNode buildFrozenDefinition(ThemeStored theme) {
        ObjectNode definition = objectMapper.createObjectNode();
        definition.put("guildId", String.valueOf(theme.guildId()));
        definition.put("themeId", theme.id());
        definition.put("name", theme.name());
        if (theme.description() != null) definition.put("description", theme.description());
        definition.set(
            "icon",
            assetDefinition(
                theme.iconAction(),
                theme.iconAssetKey(),
                theme.iconAssetContentType(),
                theme.iconAssetSha256(),
                theme.iconAssetSizeBytes()
            )
        );
        definition.set(
            "banner",
            assetDefinition(
                theme.bannerAction(),
                theme.bannerAssetKey(),
                theme.bannerAssetContentType(),
                theme.bannerAssetSha256(),
                theme.bannerAssetSizeBytes()
            )
        );
        ArrayNode changes = definition.putArray("resources");
        for (ResourceChangeStored resource : store.listResourceChanges(theme.id())) {
            ObjectNode value = changes.addObject();
            value.put("resourceType", resource.resourceType());
            value.put("resourceId", String.valueOf(resource.resourceDiscordId()));
            value.put("targetName", resource.targetName());
        }
        return definition;
    }

    private ObjectNode assetDefinition(
        String action,
        String key,
        String contentType,
        String sha256,
        Long sizeBytes
    ) {
        ObjectNode asset = objectMapper.createObjectNode();
        asset.put("action", action);
        if (key != null) asset.put("assetKey", key);
        if (contentType != null) asset.put("contentType", contentType);
        if (sha256 != null) asset.put("sha256", sha256);
        if (sizeBytes != null) asset.put("sizeBytes", sizeBytes);
        return asset;
    }

    private void requireCompleteAssets(ThemeStored theme) {
        if ("SET".equals(theme.iconAction())
            && (theme.iconAssetKey() == null || theme.iconAssetSha256() == null)) {
            throw invalid("Theme exige um ícone enviado pela plataforma");
        }
        if ("SET".equals(theme.bannerAction())
            && (theme.bannerAssetKey() == null || theme.bannerAssetSha256() == null)) {
            throw invalid("Theme exige um banner enviado pela plataforma");
        }
    }

    private ThemeResponse toThemeResponse(ThemeStored theme) {
        return new ThemeResponse(
            theme.id(),
            String.valueOf(theme.guildId()),
            theme.name(),
            theme.description(),
            new ThemeAssetResponse(
                theme.iconAction(),
                assets.publicDefinitionUrl(
                    theme.guildId(),
                    theme.id(),
                    theme.iconAssetKey()
                ),
                theme.iconAssetContentType(),
                theme.iconAssetSha256(),
                theme.iconAssetSizeBytes()
            ),
            new ThemeAssetResponse(
                theme.bannerAction(),
                assets.publicDefinitionUrl(
                    theme.guildId(),
                    theme.id(),
                    theme.bannerAssetKey()
                ),
                theme.bannerAssetContentType(),
                theme.bannerAssetSha256(),
                theme.bannerAssetSizeBytes()
            ),
            store.listResourceChanges(theme.id()).stream()
                .map(item -> new ThemeResourceResponse(
                    item.id(),
                    item.resourceType(),
                    String.valueOf(item.resourceDiscordId()),
                    item.targetName()
                ))
                .toList(),
            theme.createdAt(),
            theme.updatedAt()
        );
    }

    private ThemeApplicationResponse toApplicationResponse(ApplicationStored application) {
        JsonNode frozen = readJson(application.frozenDefinitionJson());
        OperationStored operation = store.latestOperation(application.id());
        return new ThemeApplicationResponse(
            application.id(),
            application.themeId(),
            frozen.path("name").asText("Theme"),
            String.valueOf(application.guildId()),
            String.valueOf(application.actorDiscordUserId()),
            application.status(),
            application.appliedAt(),
            application.restoredAt(),
            application.errorSummary(),
            operation == null ? null : toOperationResponse(operation)
        );
    }

    private ThemeOperationResponse toOperationResponse(OperationStored operation) {
        return new ThemeOperationResponse(
            operation.id(),
            operation.applicationId(),
            operation.operationType(),
            operation.status(),
            operation.progressCurrent(),
            operation.progressTotal(),
            operation.currentStep(),
            readJsonNullable(operation.resultJson()),
            operation.errorCode(),
            operation.startedAt(),
            operation.finishedAt(),
            operation.createdAt(),
            store.listOperationSteps(operation.guildId(), operation.id()).stream()
                .map(this::toOperationStepResponse)
                .toList()
        );
    }

    private ThemeOperationStepResponse toOperationStepResponse(OperationStepStored step) {
        return new ThemeOperationStepResponse(
            step.id(),
            step.code(),
            step.status(),
            step.message(),
            step.progressCurrent(),
            step.progressTotal(),
            readJsonNullable(step.detailJson()),
            step.createdAt()
        );
    }

    private ThemePreviewResponse toPreviewResponse(JsonNode preview) {
        try {
            return objectMapper.treeToValue(preview, ThemePreviewResponse.class);
        } catch (JsonProcessingException invalidPreview) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Coddy retornou um preflight de Theme inválido",
                invalidPreview
            );
        }
    }

    private void validatePreviewIdentity(JsonNode preview, long guildId, long themeId) {
        if (!String.valueOf(guildId).equals(preview.path("guildId").asText())
            || preview.path("themeId").asLong(-1) != themeId) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Coddy retornou identidade de Theme inválida"
            );
        }
    }

    private void validateOperationRetry(
        OperationStored operation,
        String type,
        Long expectedThemeId,
        long actorDiscordUserId,
        Boolean force
    ) {
        if (!type.equals(operation.operationType())
            || operation.actorDiscordUserId() != actorDiscordUserId) {
            throw conflict("Idempotency-Key pertence a outra operação de Theme");
        }
        ApplicationStored application = requireApplication(
            operation.guildId(),
            operation.applicationId()
        );
        if (expectedThemeId != null && application.themeId() != expectedThemeId) {
            throw conflict("Idempotency-Key pertence a outro Theme");
        }
        if (force != null && operation.forceRestore() != force.booleanValue()) {
            throw conflict("Idempotency-Key foi usado com outra decisão de force restore");
        }
    }

    private ThemeStored requireTheme(long guildId, long themeId) {
        ThemeStored theme = store.findTheme(guildId, themeId);
        if (theme == null) throw notFound("Theme não encontrado nesta guild");
        return theme;
    }

    private ApplicationStored requireApplication(long guildId, long applicationId) {
        ApplicationStored application = store.findApplication(guildId, applicationId);
        if (application == null) {
            throw notFound("Aplicação de Theme não encontrada nesta guild");
        }
        return application;
    }

    private int frozenChangeCount(String frozenJson) {
        JsonNode frozen = readJson(frozenJson);
        int count = frozen.path("resources").isArray()
            ? frozen.path("resources").size()
            : 0;
        if (!"UNCHANGED".equals(frozen.path("icon").path("action").asText("UNCHANGED"))) count++;
        if (!"UNCHANGED".equals(frozen.path("banner").path("action").asText("UNCHANGED"))) count++;
        return count;
    }

    private String normalizeAction(String value) {
        String action = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!ASSET_ACTIONS.contains(action)) {
            throw badRequest("Ação de asset deve ser UNCHANGED, SET ou REMOVE");
        }
        return action;
    }

    private String normalizeResourceType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!RESOURCE_TYPES.contains(type)) throw badRequest("Tipo de recurso inválido");
        return type;
    }

    private String validateIdempotencyKey(String value) {
        if (value == null || !IDEMPOTENCY_KEY.matcher(value.trim()).matches()) {
            throw badRequest("Idempotency-Key é obrigatório e inválido");
        }
        return value.trim();
    }

    private String trimRequired(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw badRequest(field + " é obrigatório");
        }
        return value.trim();
    }

    private String trimOptional(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário não autenticado");
        }
        return users.findByEmail(authentication.getName().trim().toLowerCase())
            .orElseThrow(() -> notFound("Usuário autenticado não encontrado"));
    }

    private CommunityDiscord linkedDiscord(long guildId) {
        return discordLinks.findByGuildId(guildId)
            .orElseThrow(() -> notFound("Servidor não vinculado a uma Community"));
    }

    private void saveAudit(
        CommunityDiscord link,
        User actor,
        String action,
        String targetType,
        String targetId,
        Map<String, ?> metadata
    ) {
        CommunityAuditLog audit = new CommunityAuditLog();
        audit.setCommunity(link.getCommunity());
        audit.setActorUser(actor);
        audit.setAction(action);
        audit.setTargetType(targetType);
        audit.setTargetId(targetId);
        audit.setMetadata(writeJson(metadata));
        auditLogs.save(audit);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Não foi possível serializar o estado do Theme",
                exception
            );
        }
    }

    private JsonNode readJson(String value) {
        try {
            return value == null
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Estado persistido do Theme está inválido",
                exception
            );
        }
    }

    private JsonNode readJsonNullable(String value) {
        return value == null ? null : readJson(value);
    }

    private long parseGuildId(String guildId) {
        try {
            return Long.parseLong(guildId);
        } catch (NumberFormatException exception) {
            throw notFound("Servidor não encontrado");
        }
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private record NormalizedTheme(
        String name,
        String description,
        String iconAction,
        String bannerAction,
        List<ResourceWrite> resources
    ) {}
}
