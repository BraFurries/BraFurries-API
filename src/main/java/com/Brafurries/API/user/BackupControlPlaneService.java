package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.BackupDtos.*;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.BackupControlPlaneStore.BackupSettingsStored;
import com.Brafurries.API.user.BackupControlPlaneStore.BackupStored;
import com.Brafurries.API.user.BackupControlPlaneStore.OperationStepStored;
import com.Brafurries.API.user.BackupControlPlaneStore.RestoreOperationStored;
import com.Brafurries.API.user.BackupControlPlaneStore.SnapshotOperationStored;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BackupControlPlaneService {
    private static final Pattern IDEMPOTENCY_KEY =
        Pattern.compile("[A-Za-z0-9._:-]{1,96}");
    private static final List<String> TERMINAL_STATUSES =
        List.of("SUCCEEDED", "PARTIAL", "FAILED");

    private final GuildManagementAccessService access;
    private final BackupControlPlaneStore store;
    private final CoddyGuildManagementClient coddy;
    private final CommunityDiscordRepository discordLinks;
    private final CommunityAuditLogRepository auditLogs;
    private final UserRepository users;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public BackupControlPlaneService(
        GuildManagementAccessService access,
        BackupControlPlaneStore store,
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

    public BackupsResponse list(Authentication authentication, String guildId) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        long id = parseGuildId(guildId);
        boolean canRestore = Objects.equals(
            coddy.getGuildOwner(guildId),
            authorized.discordUserId()
        );
        return new BackupsResponse(
            canRestore,
            store.listBackups(id).stream().map(this::toBackupResponse).toList()
        );
    }

    public void delete(
        Authentication authentication,
        String guildId,
        int backupId
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        requireOwner(guildId, authorized);
        long id = parseGuildId(guildId);
        BackupStored backup = requireBackup(id, backupId);
        User actor = authenticatedUser(authentication);
        CommunityDiscord link = linkedDiscord(id);

        transactions.executeWithoutResult(status -> {
            if (store.hasActiveRestore(id, backupId)) {
                throw conflict(
                    "Este Backup está sendo restaurado agora e não pode ser excluído"
                );
            }
            if (!store.deleteBackupIfIdle(id, backupId)) {
                if (store.hasActiveRestore(id, backupId)) {
                    throw conflict(
                        "Este Backup está sendo restaurado agora e não pode ser excluído"
                    );
                }
                throw notFound("Backup não encontrado nesta guild");
            }
            saveAudit(
                link,
                actor,
                "BACKUP_DELETED",
                "BACKUP",
                String.valueOf(backupId),
                Map.of(
                    "guildId", String.valueOf(id),
                    "backupId", String.valueOf(backupId),
                    "type", "periodic".equalsIgnoreCase(backup.backupType())
                        ? "PERIODIC"
                        : "MANUAL"
                )
            );
        });
    }

    public BackupSettingsResponse settings(
        Authentication authentication,
        String guildId
    ) {
        access.authorize(authentication, guildId);
        return toSettingsResponse(store.readSettings(parseGuildId(guildId)));
    }

    public BackupSettingsResponse updateSettings(
        Authentication authentication,
        String guildId,
        BackupSettingsUpdateRequest request
    ) {
        access.authorize(authentication, guildId);
        if (request == null || request.periodicEnabled() == null) {
            throw badRequest("Configuração de Backup obrigatória");
        }

        String frequency = normalizeFrequency(request.frequency());
        long id = parseGuildId(guildId);
        BackupSettingsStored before = store.readSettings(id);
        BackupSettingsStored after = new BackupSettingsStored(
            request.periodicEnabled(),
            frequency
        );
        List<String> changed = changedSettings(before, after);
        if (changed.isEmpty()) {
            return toSettingsResponse(before);
        }

        User actor = authenticatedUser(authentication);
        CommunityDiscord link = linkedDiscord(id);
        int legacyMinutes = frequencyMinutes(frequency);
        transactions.executeWithoutResult(status -> {
            store.writeSettings(
                id,
                after.periodicEnabled(),
                after.frequency(),
                legacyMinutes
            );
            saveAudit(
                link,
                actor,
                "BACKUP_SETTINGS_UPDATED",
                "DISCORD_GUILD",
                String.valueOf(id),
                Map.of(
                    "guildId", String.valueOf(id),
                    "changedFields", changed,
                    "manualSlots", 1,
                    "periodicSlots", 1
                )
            );
        });
        return toSettingsResponse(after);
    }

    public SnapshotOperationResponse create(
        Authentication authentication,
        String guildId,
        CreateBackupRequest request,
        String idempotencyKey
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        String key = validateIdempotencyKey(idempotencyKey);
        String name = validateBackupName(request == null ? null : request.name());
        long id = parseGuildId(guildId);
        User actor = authenticatedUser(authentication);

        SnapshotOperationStored existing =
            store.findSnapshotOperationByKey(id, key);
        if (existing != null) {
            validateSnapshotIdempotency(
                existing,
                name,
                authorized.discordUserId()
            );
            auditSnapshotRequestedIfNeeded(id, existing, actor);
            return dispatchPendingSnapshot(
                guildId,
                authorized.discordUserId(),
                existing
            );
        }

        SnapshotOperationStored operation = store.createOrGetSnapshotOperation(
            id,
            key,
            name,
            authorized.discordUserId(),
            actor.getId()
        );
        if (operation == null) {
            throw serverError("Não foi possível criar a operação de Backup");
        }
        validateSnapshotIdempotency(
            operation,
            name,
            authorized.discordUserId()
        );
        auditSnapshotRequestedIfNeeded(id, operation, actor);
        return dispatchPendingSnapshot(
            guildId,
            authorized.discordUserId(),
            operation
        );
    }

    private SnapshotOperationResponse dispatchPendingSnapshot(
        String guildId,
        long discordUserId,
        SnapshotOperationStored operation
    ) {
        if ("PENDING".equals(operation.status())) {
            coddy.dispatchBackupSnapshot(
                guildId,
                discordUserId,
                operation.id()
            );
            SnapshotOperationStored refreshed = store.findSnapshotOperation(
                operation.guildId(),
                operation.id()
            );
            if (refreshed != null) {
                operation = refreshed;
            }
        }
        return toSnapshotResponse(operation);
    }

    public SnapshotOperationsResponse snapshotOperations(
        Authentication authentication,
        String guildId
    ) {
        access.authorize(authentication, guildId);
        long id = parseGuildId(guildId);
        List<SnapshotOperationStored> operations =
            store.listSnapshotOperations(id);
        for (SnapshotOperationStored operation : operations) {
            auditSnapshotCompletionIfNeeded(id, operation);
        }
        return new SnapshotOperationsResponse(
            operations.stream()
                .map(operation -> {
                    SnapshotOperationStored refreshed =
                        store.findSnapshotOperation(id, operation.id());
                    return toSnapshotResponse(
                        refreshed == null ? operation : refreshed
                    );
                })
                .toList()
        );
    }

    public SnapshotOperationResponse snapshotOperation(
        Authentication authentication,
        String guildId,
        long operationId
    ) {
        access.authorize(authentication, guildId);
        long id = parseGuildId(guildId);
        SnapshotOperationStored operation =
            store.findSnapshotOperation(id, operationId);
        if (operation == null) {
            throw notFound("Operação de Backup não encontrada nesta guild");
        }
        auditSnapshotCompletionIfNeeded(id, operation);
        SnapshotOperationStored refreshed =
            store.findSnapshotOperation(id, operationId);
        return toSnapshotResponse(refreshed == null ? operation : refreshed);
    }

    public BackupRestorePreviewResponse restorePreview(
        Authentication authentication,
        String guildId,
        int backupId,
        RestorePreviewRequest request
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        requireOwner(guildId, authorized);
        long id = parseGuildId(guildId);
        BackupStored backup = requireBackup(id, backupId);
        String scope = normalizeScope(request == null ? null : request.scope());

        JsonNode preview = coddy.backupRestorePreview(
            guildId,
            authorized.discordUserId(),
            backupId,
            scope
        );
        validatePreviewIdentity(preview, guildId, backupId, scope);
        return toRestorePreviewResponse(backup, scope, preview);
    }

    public RestoreOperationResponse startRestore(
        Authentication authentication,
        String guildId,
        int backupId,
        RestoreStartRequest request,
        String idempotencyKey
    ) {
        AuthorizedGuild authorized = access.authorize(authentication, guildId);
        requireOwner(guildId, authorized);
        String key = validateIdempotencyKey(idempotencyKey);
        if (request == null || !Boolean.TRUE.equals(request.confirmed())) {
            throw invalid("Confirmação explícita do restore é obrigatória");
        }

        long id = parseGuildId(guildId);
        String scope = normalizeScope(request.scope());
        JsonNode decision = request.decision() == null
            ? objectMapper.createObjectNode()
            : request.decision();
        if (!decision.isObject()) {
            throw invalid("Decisão de restore inválida");
        }

        User actor = authenticatedUser(authentication);
        RestoreOperationStored existing =
            store.findRestoreOperationByKey(id, key);
        if (existing != null) {
            validateRestoreIdempotency(
                existing,
                backupId,
                scope,
                authorized.discordUserId(),
                decision
            );
            auditRestoreRequestedIfNeeded(id, existing, actor);
            return dispatchPendingRestore(
                guildId,
                authorized.discordUserId(),
                existing
            );
        }

        requireBackup(id, backupId);
        JsonNode preview = coddy.backupRestorePreview(
            guildId,
            authorized.discordUserId(),
            backupId,
            scope
        );
        validatePreviewIdentity(preview, guildId, backupId, scope);
        validateRestoreDecision(preview, decision);

        RestoreOperationStored operation = store.createOrGetRestoreOperation(
            id,
            backupId,
            key,
            scope,
            authorized.discordUserId(),
            actor.getId(),
            writeJson(decision)
        );
        if (operation == null) {
            throw notFound("Backup não encontrado nesta guild");
        }
        validateRestoreIdempotency(
            operation,
            backupId,
            scope,
            authorized.discordUserId(),
            decision
        );
        auditRestoreRequestedIfNeeded(id, operation, actor);
        return dispatchPendingRestore(
            guildId,
            authorized.discordUserId(),
            operation
        );
    }

    private RestoreOperationResponse dispatchPendingRestore(
        String guildId,
        long discordUserId,
        RestoreOperationStored operation
    ) {
        if ("PENDING".equals(operation.status())) {
            coddy.dispatchBackupRestore(
                guildId,
                discordUserId,
                operation.backupId(),
                operation.id(),
                operation.scope()
            );
            RestoreOperationStored refreshed = store.findRestoreOperation(
                operation.guildId(),
                operation.id()
            );
            if (refreshed != null) {
                operation = refreshed;
            }
        }
        return toRestoreResponse(operation);
    }

    public RestoreOperationsResponse restoreOperations(
        Authentication authentication,
        String guildId
    ) {
        access.authorize(authentication, guildId);
        long id = parseGuildId(guildId);
        List<RestoreOperationStored> operations = store.listRestoreOperations(id);
        for (RestoreOperationStored operation : operations) {
            auditRestoreCompletionIfNeeded(id, operation);
        }
        return new RestoreOperationsResponse(
            operations.stream()
                .map(operation -> {
                    RestoreOperationStored refreshed =
                        store.findRestoreOperation(id, operation.id());
                    return toRestoreResponse(
                        refreshed == null ? operation : refreshed
                    );
                })
                .toList()
        );
    }

    public RestoreOperationResponse restoreOperation(
        Authentication authentication,
        String guildId,
        long operationId
    ) {
        access.authorize(authentication, guildId);
        long id = parseGuildId(guildId);
        RestoreOperationStored operation =
            store.findRestoreOperation(id, operationId);
        if (operation == null) {
            throw notFound("Operação de restore não encontrada nesta guild");
        }
        auditRestoreCompletionIfNeeded(id, operation);
        RestoreOperationStored refreshed =
            store.findRestoreOperation(id, operationId);
        return toRestoreResponse(refreshed == null ? operation : refreshed);
    }

    // Called only by the authenticated internal progress hub after validating
    // a persisted step. Never expose this method through a user endpoint.
    Object trustedOperationState(long guildId, String kind, long operationId) {
        if ("SNAPSHOT".equals(kind)) {
            SnapshotOperationStored snapshot = store.findSnapshotOperation(guildId, operationId);
            if (snapshot == null) return null;
            if (TERMINAL_STATUSES.contains(snapshot.status())) {
                auditSnapshotCompletionIfNeeded(guildId, snapshot);
                SnapshotOperationStored refreshed = store.findSnapshotOperation(guildId, operationId);
                if (refreshed != null) snapshot = refreshed;
            }
            return toSnapshotResponse(snapshot);
        }
        if ("RESTORE".equals(kind)) {
            RestoreOperationStored restore = store.findRestoreOperation(guildId, operationId);
            if (restore == null) return null;
            if (TERMINAL_STATUSES.contains(restore.status())) {
                auditRestoreCompletionIfNeeded(guildId, restore);
                RestoreOperationStored refreshed = store.findRestoreOperation(guildId, operationId);
                if (refreshed != null) restore = refreshed;
            }
            return toRestoreResponse(restore);
        }
        throw invalid("Tipo de operação inválido");
    }

    private void auditSnapshotRequestedIfNeeded(
        long guildId,
        SnapshotOperationStored operation,
        User actor
    ) {
        CommunityDiscord link = linkedDiscord(guildId);
        transactions.executeWithoutResult(status -> {
            if (!store.claimSnapshotRequestedAudit(guildId, operation.id())) {
                return;
            }
            saveAudit(
                link,
                actor,
                "BACKUP_CREATE_REQUESTED",
                "BACKUP_SNAPSHOT",
                String.valueOf(operation.id()),
                Map.of(
                    "guildId", String.valueOf(guildId),
                    "operationId", String.valueOf(operation.id()),
                    "backupType", backupType(operation.backupType()),
                    "status", operation.status()
                )
            );
        });
    }

    private void auditSnapshotCompletionIfNeeded(
        long guildId,
        SnapshotOperationStored operation
    ) {
        if (!TERMINAL_STATUSES.contains(operation.status())) {
            return;
        }
        if (operation.actorUserId() == null) {
            return;
        }
        User actor = users.findById(operation.actorUserId()).orElse(null);
        if (actor == null) {
            return;
        }
        CommunityDiscord link = linkedDiscord(guildId);
        transactions.executeWithoutResult(status -> {
            if (!store.claimSnapshotCompletionAudit(guildId, operation.id())) {
                return;
            }
            Map<String, Object> metadata = new java.util.LinkedHashMap<>();
            metadata.put("guildId", String.valueOf(guildId));
            metadata.put("operationId", String.valueOf(operation.id()));
            metadata.put("backupType", backupType(operation.backupType()));
            metadata.put("status", operation.status());
            if (operation.backupId() != null) {
                metadata.put("backupId", String.valueOf(operation.backupId()));
            }
            saveAudit(
                link,
                actor,
                "BACKUP_CREATE_COMPLETED",
                "BACKUP_SNAPSHOT",
                String.valueOf(operation.id()),
                metadata
            );
        });
    }

    private void auditRestoreRequestedIfNeeded(
        long guildId,
        RestoreOperationStored operation,
        User actor
    ) {
        CommunityDiscord link = linkedDiscord(guildId);
        transactions.executeWithoutResult(status -> {
            if (!store.claimRestoreRequestedAudit(guildId, operation.id())) {
                return;
            }
            saveAudit(
                link,
                actor,
                "BACKUP_RESTORE_REQUESTED",
                "BACKUP_RESTORE",
                String.valueOf(operation.id()),
                Map.of(
                    "guildId", String.valueOf(guildId),
                    "backupId", String.valueOf(operation.backupId()),
                    "scope", operation.scope(),
                    "status", operation.status()
                )
            );
        });
    }

    private void auditRestoreCompletionIfNeeded(
        long guildId,
        RestoreOperationStored operation
    ) {
        if (!TERMINAL_STATUSES.contains(operation.status())) {
            return;
        }
        if (operation.actorUserId() == null) {
            return;
        }
        User actor = users.findById(operation.actorUserId()).orElse(null);
        if (actor == null) {
            return;
        }
        CommunityDiscord link = linkedDiscord(guildId);
        transactions.executeWithoutResult(status -> {
            if (!store.claimRestoreCompletionAudit(guildId, operation.id())) {
                return;
            }
            saveAudit(
                link,
                actor,
                "BACKUP_RESTORE_COMPLETED",
                "BACKUP_RESTORE",
                String.valueOf(operation.id()),
                Map.of(
                    "guildId", String.valueOf(guildId),
                    "backupId", String.valueOf(operation.backupId()),
                    "scope", operation.scope(),
                    "status", operation.status()
                )
            );
        });
    }

    private void requireOwner(String guildId, AuthorizedGuild authorized) {
        Long ownerId = coddy.getGuildOwner(guildId);
        if (!Objects.equals(ownerId, authorized.discordUserId())) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Apenas o dono do servidor pode executar esta operação de Backup"
            );
        }
    }

    private BackupStored requireBackup(long guildId, int backupId) {
        BackupStored backup = store.findBackup(guildId, backupId);
        if (backup == null) {
            throw notFound("Backup não encontrado nesta guild");
        }
        return backup;
    }

    private BackupRestorePreviewResponse toRestorePreviewResponse(
        BackupStored backup,
        String scope,
        JsonNode preview
    ) {
        JsonNode blockersNode = preview.path("blockers");
        List<BackupRestoreBlocker> blockers = new ArrayList<>();
        if (blockersNode.isArray()) {
            for (JsonNode blocker : blockersNode) {
                String code = blocker.path("code").asText("UNKNOWN");
                List<String> permissions = new ArrayList<>();
                if (blocker.path("permissions").isArray()) {
                    blocker.path("permissions").forEach(item ->
                        permissions.add(item.asText())
                    );
                }
                List<BackupRestoreRoleCandidate> roles =
                    roleCandidates(blocker.path("roles"));
                Integer count = null;
                if (blocker.path("backupRoleIds").isArray()) {
                    count = blocker.path("backupRoleIds").size();
                } else if (!roles.isEmpty()) {
                    count = roles.size();
                }
                blockers.add(new BackupRestoreBlocker(
                    code,
                    blockerMessage(code),
                    List.copyOf(permissions),
                    roles,
                    count
                ));
            }
        }

        List<BackupRestoreWarning> warnings = new ArrayList<>();
        JsonNode warningsNode = preview.path("warnings");
        if (warningsNode.isArray()) {
            for (JsonNode warning : warningsNode) {
                String code = warning.path("code").asText("UNKNOWN");
                List<Integer> types = new ArrayList<>();
                if (warning.path("channelTypes").isArray()) {
                    warning.path("channelTypes").forEach(item ->
                        types.add(item.asInt())
                    );
                }
                warnings.add(new BackupRestoreWarning(
                    code,
                    warningMessage(code),
                    List.copyOf(types)
                ));
            }
        }

        List<BackupRestoreAmbiguousRole> ambiguous = new ArrayList<>();
        JsonNode duplicates = preview.path("duplicateRoles");
        boolean canCreateNew = "full".equals(scope) || "roles".equals(scope);
        if (duplicates.isArray()) {
            for (JsonNode item : duplicates) {
                ambiguous.add(new BackupRestoreAmbiguousRole(
                    item.path("backupRoleId").asInt(),
                    item.path("backupDiscordRoleId").asText(),
                    item.path("name").asText(),
                    roleCandidates(item.path("candidates")),
                    canCreateNew
                ));
            }
        }

        JsonNode permissionBlocker = null;
        boolean available = true;
        for (JsonNode blocker : blockersNode) {
            String code = blocker.path("code").asText();
            if ("BOT_MEMBER_UNAVAILABLE".equals(code)) {
                available = false;
            } else if ("BOT_MISSING_PERMISSIONS".equals(code)) {
                permissionBlocker = blocker;
            }
        }

        List<String> missingPermissions = new ArrayList<>();
        if (permissionBlocker != null
            && permissionBlocker.path("permissions").isArray()) {
            permissionBlocker.path("permissions").forEach(item ->
                missingPermissions.add(item.asText())
            );
        }

        JsonNode rolePlan = preview.path("rolePlan");
        JsonNode channelPlan = preview.path("channelPlan");
        boolean permissionScope =
            "full".equals(scope) || "permissions".equals(scope);

        return new BackupRestorePreviewResponse(
            toBackupResponse(backup),
            scope,
            new BackupRestoreBotState(
                available,
                roleCandidate(preview.path("bot").path("topRole")),
                roleCandidates(preview.path("bot").path("rolesAboveBot")),
                List.copyOf(missingPermissions)
            ),
            new BackupRestorePlan(
                new BackupRestoreRolePlan(
                    rolePlan.path("create").asInt(0),
                    rolePlan.path("update").asInt(0),
                    rolePlan.path("reuse").asInt(0),
                    rolePlan.path("ambiguous").asInt(0),
                    rolePlan.path("skip").asInt(0)
                ),
                new BackupRestoreChannelPlan(
                    channelPlan.path("create").asInt(0),
                    channelPlan.path("update").asInt(0),
                    channelPlan.path("reuse").asInt(0),
                    channelPlan.path("unsupported").asInt(0),
                    channelPlan.path("skip").asInt(0)
                ),
                new BackupRestorePermissionsPlan(
                    permissionScope ? backup.overwriteCount() : 0
                )
            ),
            List.copyOf(ambiguous),
            List.copyOf(warnings),
            List.copyOf(blockers),
            preview.path("ready").asBoolean(blockers.isEmpty())
        );
    }

    private List<BackupRestoreRoleCandidate> roleCandidates(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<BackupRestoreRoleCandidate> roles = new ArrayList<>();
        for (JsonNode item : node) {
            BackupRestoreRoleCandidate role = roleCandidate(item);
            if (role != null) {
                roles.add(role);
            }
        }
        return List.copyOf(roles);
    }

    private BackupRestoreRoleCandidate roleCandidate(JsonNode item) {
        if (item == null || item.isMissingNode() || item.isNull()) {
            return null;
        }
        String id = item.path("id").asText("");
        if (id.isBlank()) {
            return null;
        }
        return new BackupRestoreRoleCandidate(
            id,
            item.path("name").asText(""),
            item.path("position").asInt(0)
        );
    }

    private String blockerMessage(String code) {
        return switch (code) {
            case "BOT_MEMBER_UNAVAILABLE" ->
                "O Coddy não está disponível como membro deste servidor.";
            case "BOT_MISSING_PERMISSIONS" ->
                "O Coddy não possui todas as permissões necessárias para este escopo.";
            case "ROLE_RESOLUTION_REQUIRED" ->
                "Há cargos duplicados que exigem uma decisão explícita.";
            default -> "O preflight encontrou um bloqueio: " + code;
        };
    }

    private String warningMessage(String code) {
        return switch (code) {
            case "UNSUPPORTED_CHANNEL_TYPES" ->
                "Alguns tipos de canal não são suportados nesta versão e serão ignorados.";
            case "ROLE_POSITIONS_CLAMPED_BELOW_BOT" ->
                "Cargos originalmente acima do Coddy serão restaurados abaixo dele, preservando suas especificações e a ordem relativa possível.";
            default -> "O preflight retornou um aviso: " + code;
        };
    }

    private void validatePreviewIdentity(
        JsonNode preview,
        String guildId,
        int backupId,
        String scope
    ) {
        if (preview == null
            || preview.path("backupId").asInt(0) != backupId
            || !guildId.equals(preview.path("guildId").asText())
            || !scope.equals(preview.path("scope").asText())) {
            throw serverError("O Coddy retornou um preflight de Backup inconsistente");
        }
    }

    private void validateRestoreDecision(JsonNode preview, JsonNode decision) {
        for (JsonNode blocker : preview.path("blockers")) {
            String code = blocker.path("code").asText();
            if (!"ROLE_RESOLUTION_REQUIRED".equals(code)) {
                throw invalid(
                    "O preflight possui blocker: "
                        + blocker.path("message").asText(code)
                );
            }
        }

        JsonNode ambiguous = preview.path("duplicateRoles");
        if (!ambiguous.isArray() || ambiguous.isEmpty()) {
            return;
        }
        String strategy =
            decision.path("duplicateStrategy").asText("").toLowerCase(Locale.ROOT);
        String scope = preview.path("scope").asText("");
        boolean roleMutationAllowed =
            "full".equals(scope) || "roles".equals(scope);
        if ("merge".equals(strategy)) {
            if (!roleMutationAllowed) {
                throw invalid(
                    "Unificação de cargos só é permitida nos escopos full ou roles"
                );
            }
            return;
        }
        if (!"explicit".equals(strategy)) {
            throw invalid("Escolha como resolver todos os cargos duplicados");
        }
        JsonNode resolutions = decision.path("roleResolutions");
        if (!resolutions.isObject()) {
            throw invalid("Resoluções explícitas de cargos são obrigatórias");
        }
        for (JsonNode item : ambiguous) {
            String roleId = item.path("backupRoleId").asText();
            if (!resolutions.has(roleId)
                || resolutions.path(roleId).asText("").isBlank()) {
                throw invalid("Todos os cargos ambíguos precisam de decisão explícita");
            }

            String target = resolutions.path(roleId).asText("");
            if ("CREATE_NEW".equals(target)) {
                if (!roleMutationAllowed) {
                    throw invalid(
                        "Criação de cargo só é permitida nos escopos full ou roles"
                    );
                }
                continue;
            }
            boolean candidate = false;
            for (JsonNode option : item.path("candidates")) {
                if (target.equals(option.path("id").asText())) {
                    candidate = true;
                    break;
                }
            }
            if (!candidate) {
                throw invalid("A resolução de cargo não pertence ao preflight atual");
            }
        }
    }

    private void validateSnapshotIdempotency(
        SnapshotOperationStored operation,
        String name,
        long actorDiscordUserId
    ) {
        if (!name.equals(operation.requestedName())
            || !"normal".equals(operation.backupType())
            || !Objects.equals(
                operation.actorDiscordUserId(),
                actorDiscordUserId
            )) {
            throw conflict("Idempotency-Key já foi usada para outra operação de Backup");
        }
    }

    private void validateRestoreIdempotency(
        RestoreOperationStored operation,
        int backupId,
        String scope,
        long actorDiscordUserId,
        JsonNode decision
    ) {
        if (!Objects.equals(operation.backupId(), backupId)
            || !scope.equals(operation.scope())
            || operation.actorDiscordUserId() != actorDiscordUserId) {
            throw conflict("Idempotency-Key já foi usada para outro restore");
        }
        JsonNode persisted = readJson(operation.decisionJson());
        if (!persisted.equals(decision)) {
            throw conflict("Idempotency-Key já foi usada com outra decisão de restore");
        }
    }

    private BackupItemResponse toBackupResponse(BackupStored backup) {
        return new BackupItemResponse(
            backup.id(),
            backup.name(),
            backupType(backup.backupType()),
            backup.createdAt(),
            backup.creatorDiscordUserId() == null
                ? null
                : String.valueOf(backup.creatorDiscordUserId()),
            new BackupContentSummary(
                backup.roleCount(),
                backup.channelCount(),
                backup.overwriteCount()
            )
        );
    }

    private BackupSettingsResponse toSettingsResponse(
        BackupSettingsStored settings
    ) {
        return new BackupSettingsResponse(
            settings.periodicEnabled(),
            settings.frequency()
        );
    }

    private SnapshotOperationResponse toSnapshotResponse(
        SnapshotOperationStored operation
    ) {
        return new SnapshotOperationResponse(
            operation.id(),
            backupType(operation.backupType()),
            operation.requestedName(),
            operation.backupId(),
            operation.status(),
            operation.progressCurrent(),
            operation.progressTotal(),
            operation.currentStep(),
            operation.startedAt(),
            operation.finishedAt(),
            operation.createdAt(),
            operation.errorCode(),
            readJsonNullable(operation.resultJson()),
            operationSteps(operation.guildId(), "SNAPSHOT", operation.id())
        );
    }

    private RestoreOperationResponse toRestoreResponse(
        RestoreOperationStored operation
    ) {
        return new RestoreOperationResponse(
            operation.id(),
            operation.backupId(),
            operation.scope(),
            operation.status(),
            operation.progressCurrent(),
            operation.progressTotal(),
            operation.currentStep(),
            operation.startedAt(),
            operation.finishedAt(),
            operation.createdAt(),
            operation.errorCode(),
            readJsonNullable(operation.resultJson()),
            operationSteps(operation.guildId(), "RESTORE", operation.id())
        );
    }

    private List<BackupOperationStepResponse> operationSteps(
        long guildId,
        String kind,
        long operationId
    ) {
        return store.listOperationSteps(guildId, kind, operationId)
            .stream()
            .map(this::toOperationStep)
            .toList();
    }

    private BackupOperationStepResponse toOperationStep(
        OperationStepStored step
    ) {
        return new BackupOperationStepResponse(
            step.id(),
            step.stepCode(),
            step.stepStatus(),
            step.message(),
            step.progressCurrent(),
            step.progressTotal(),
            readJsonNullable(step.detailJson()),
            step.createdAt()
        );
    }

    private String backupType(String value) {
        return "periodic".equalsIgnoreCase(value) ? "PERIODIC" : "MANUAL";
    }

    private String normalizeFrequency(String raw) {
        String value = raw == null
            ? ""
            : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "daily", "weekly", "monthly" -> value;
            default -> throw invalid("Frequência deve ser daily, weekly ou monthly");
        };
    }

    private int frequencyMinutes(String frequency) {
        return switch (frequency) {
            case "daily" -> 1440;
            case "monthly" -> 43200;
            default -> 10080;
        };
    }

    private String normalizeScope(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "full", "roles", "channels", "permissions" -> value;
            default -> throw invalid("Escopo de restore inválido");
        };
    }

    private String validateBackupName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > 255) {
            throw invalid("Nome do Backup deve ter entre 1 e 255 caracteres");
        }
        if (name.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Nome do Backup contém caracteres de controle inválidos");
        }
        return name;
    }

    private String validateIdempotencyKey(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!IDEMPOTENCY_KEY.matcher(value).matches()) {
            throw badRequest("Idempotency-Key inválida");
        }
        return value;
    }

    private List<String> changedSettings(
        BackupSettingsStored before,
        BackupSettingsStored after
    ) {
        List<String> changed = new ArrayList<>();
        if (before.periodicEnabled() != after.periodicEnabled()) {
            changed.add("periodicEnabled");
        }
        if (!Objects.equals(before.frequency(), after.frequency())) {
            changed.add("frequency");
        }
        return List.copyOf(changed);
    }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Usuário não autenticado"
            );
        }
        return users.findByEmail(
            authentication.getName().trim().toLowerCase()
        ).orElseThrow(() -> new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "Usuário autenticado não encontrado"
        ));
    }

    private CommunityDiscord linkedDiscord(long guildId) {
        return discordLinks.findByGuildId(guildId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Servidor não vinculado a uma Community"
            ));
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
            throw serverError("Não foi possível serializar a operação de Backup");
        }
    }

    private JsonNode readJson(String value) {
        try {
            return value == null
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw serverError("Estado persistido do Backup está inválido");
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
        return new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            message
        );
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private ResponseStatusException serverError(String message) {
        return new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR,
            message
        );
    }
}
