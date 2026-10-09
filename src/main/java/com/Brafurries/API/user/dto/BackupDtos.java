package com.Brafurries.API.user.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class BackupDtos {
    private BackupDtos() {}

    public record BackupContentSummary(
        int roles,
        int channels,
        int permissionOverwrites
    ) {}

    public record BackupItemResponse(
        Integer id,
        String name,
        String type,
        Instant createdAt,
        String creatorDiscordUserId,
        BackupContentSummary summary
    ) {}

    public record BackupsResponse(
        boolean canRestore,
        List<BackupItemResponse> backups
    ) {}

    public record BackupSettingsResponse(
        boolean periodicEnabled,
        String frequency
    ) {}

    public record BackupSettingsUpdateRequest(
        @NotNull Boolean periodicEnabled,
        @NotBlank @Pattern(regexp = "(?i)daily|weekly|monthly") String frequency
    ) {}

    public record CreateBackupRequest(
        @NotBlank @Size(max = 255) String name
    ) {}

    public record RestorePreviewRequest(
        @NotBlank @Pattern(regexp = "full|roles|channels|permissions") String scope
    ) {}

    public record BackupRestoreRoleCandidate(
        String id,
        String name,
        int position
    ) {}

    public record BackupRestoreAmbiguousRole(
        Integer backupRoleId,
        String backupDiscordId,
        String name,
        List<BackupRestoreRoleCandidate> candidates,
        boolean canCreateNew
    ) {}

    public record BackupRestoreBlocker(
        String code,
        String message,
        List<String> permissions,
        List<BackupRestoreRoleCandidate> roles,
        Integer count
    ) {}

    public record BackupRestoreWarning(
        String code,
        String message,
        List<Integer> types
    ) {}

    public record BackupRestoreBotState(
        boolean available,
        BackupRestoreRoleCandidate botTopRole,
        List<BackupRestoreRoleCandidate> rolesAboveBot,
        List<String> missingPermissions
    ) {}

    public record BackupRestoreRolePlan(
        int create,
        int update,
        int reuse,
        int ambiguous,
        int skipped
    ) {}

    public record BackupRestoreChannelPlan(
        int create,
        int update,
        int reuse,
        int unsupported,
        int skipped
    ) {}

    public record BackupRestorePermissionsPlan(int apply) {}

    public record BackupRestorePlan(
        BackupRestoreRolePlan roles,
        BackupRestoreChannelPlan channels,
        BackupRestorePermissionsPlan permissions
    ) {}

    public record BackupRestorePreviewResponse(
        BackupItemResponse backup,
        String scope,
        BackupRestoreBotState bot,
        BackupRestorePlan plan,
        List<BackupRestoreAmbiguousRole> ambiguousRoles,
        List<BackupRestoreWarning> warnings,
        List<BackupRestoreBlocker> blockers,
        boolean ready
    ) {}

    public record RestoreStartRequest(
        @NotBlank @Pattern(regexp = "full|roles|channels|permissions") String scope,
        JsonNode decision,
        @NotNull Boolean confirmed
    ) {}

    public record BackupOperationStepResponse(
        Long id,
        String code,
        String status,
        String message,
        int progressCurrent,
        int progressTotal,
        JsonNode detail,
        Instant createdAt
    ) {}

    public record SnapshotOperationResponse(
        Long id,
        String backupType,
        String requestedName,
        Integer backupId,
        String status,
        int progressCurrent,
        int progressTotal,
        String currentStep,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        String errorCode,
        JsonNode result,
        List<BackupOperationStepResponse> steps
    ) {}

    public record SnapshotOperationsResponse(
        List<SnapshotOperationResponse> operations
    ) {}

    public record RestoreOperationResponse(
        Long id,
        Integer backupId,
        String scope,
        String status,
        int progressCurrent,
        int progressTotal,
        String currentStep,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        String errorCode,
        JsonNode result,
        List<BackupOperationStepResponse> steps
    ) {}

    public record RestoreOperationsResponse(
        List<RestoreOperationResponse> operations
    ) {}
}
