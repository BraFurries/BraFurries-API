package com.Brafurries.API.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

public final class InternalCommunityNetworkDtos {
    private InternalCommunityNetworkDtos() {}

    public enum SyncTrigger {
        GUILD_JOIN, STARTUP, PERIODIC, MANUAL, OWNERSHIP_CHANGE, DRIFT, RECOVERY
    }

    public enum MembershipSyncState {
        RECONCILIATION_REQUIRED, RECONCILING, HEALTHY, DEGRADED
    }

    public enum SyncStatus {
        QUEUED, RUNNING, SUCCESS, FAILED
    }

    public record DiscordNetworkObservationRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank String ownerDiscordUserId,
        @NotNull Boolean active,
        @NotNull @PositiveOrZero Integer observedMembers
    ) {}

    public record DiscordOwnershipObservationRequest(
        @NotBlank String ownerDiscordUserId
    ) {}

    public record DiscordNetworkResponse(
        Integer communityId,
        String guildId,
        boolean active,
        Integer ownerUserId,
        String ownerDiscordUserId,
        boolean ownershipChanged,
        MembershipSyncState membershipSyncState,
        boolean membershipReconciliationRunning,
        long trackedPresentMembers,
        LocalDateTime lastFullReconciliationAt
    ) {}

    public record DiscordNetworkPresenceSnapshotRequest(
        @NotNull Boolean complete,
        @NotNull List<@NotBlank String> guildIds
    ) {}

    public record DiscordNetworkPresenceSnapshotResponse(
        int observedGuilds,
        int deactivatedGuilds
    ) {}

    public record DiscordMemberSnapshot(
        @NotBlank String discordUserId,
        @NotBlank String username,
        String globalDisplayName,
        @Size(max = 100) String displayName,
        @NotNull Boolean approved,
        LocalDateTime joinedAt
    ) {}

    public record DiscordMemberSnapshotRequest(
        Long runId,
        @NotNull Boolean complete,
        @NotNull List<@Valid DiscordMemberSnapshot> members
    ) {}

    public record DiscordMemberBatchRequest(
        Long runId,
        @NotNull @Size(min = 1, max = 250) List<@Valid DiscordMemberSnapshot> members
    ) {}

    public record DiscordMemberFinalizeRequest(
        Long runId,
        @NotNull Boolean complete,
        @PositiveOrZero Integer observedMembers,
        @Size(max = 20000) List<@NotBlank String> observedDiscordUserIds
    ) {}

    public record DiscordMemberObservationRequest(
        @NotBlank String username,
        String globalDisplayName,
        @Size(max = 100) String displayName,
        @NotNull Boolean approved,
        LocalDateTime joinedAt
    ) {}

    public record DiscordMemberApprovalRequest(
        @NotNull Boolean approved
    ) {}

    public record MemberObservationResponse(
        Integer communityId,
        String guildId,
        String discordUserId,
        boolean identityKnown,
        boolean membershipUpdated
    ) {}

    public record MemberSnapshotResponse(
        Integer communityId,
        String guildId,
        int observedMembers,
        int updatedMembers
    ) {}

    public record SyncRunCreateRequest(
        @NotNull SyncTrigger trigger,
        @NotNull SyncStatus status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        @PositiveOrZero Integer observedMembers,
        @PositiveOrZero Integer updatedMembers,
        @Size(max = 64) String errorCode
    ) {}

    public record SyncRunUpdateRequest(
        @NotNull SyncStatus status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        @PositiveOrZero Integer observedMembers,
        @PositiveOrZero Integer updatedMembers,
        @Size(max = 64) String errorCode
    ) {}

    public record SyncRunResponse(
        Long runId,
        Integer communityId,
        String networkType,
        String externalNetworkId,
        SyncTrigger trigger,
        SyncStatus status,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        Integer observedMembers,
        Integer updatedMembers,
        String errorCode
    ) {}

    public record SyncQueueResponse(List<Long> runIds, int queuedNetworks) {}
}
