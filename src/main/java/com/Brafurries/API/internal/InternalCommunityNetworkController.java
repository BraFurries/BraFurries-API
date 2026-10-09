package com.Brafurries.API.internal;

import static com.Brafurries.API.internal.dto.InternalCommunityNetworkDtos.*;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/community-networks/discord/{guildId}")
public class InternalCommunityNetworkController {
    private final CommunityNetworkLifecycleService service;
    private final InternalServiceTokenAuthenticator authenticator;

    public InternalCommunityNetworkController(
        CommunityNetworkLifecycleService service,
        InternalServiceTokenAuthenticator authenticator
    ) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @PutMapping
    public DiscordNetworkResponse observeNetwork(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @PathVariable String guildId,
        @Valid @RequestBody DiscordNetworkObservationRequest request
    ) {
        authenticator.authenticate(authorization);
        return service.observeDiscordNetwork(guildId, request);
    }

    @PutMapping("/ownership")
    public DiscordNetworkResponse observeOwnership(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @PathVariable String guildId,
        @Valid @RequestBody DiscordOwnershipObservationRequest request
    ) {
        authenticator.authenticate(authorization);
        return service.observeDiscordOwnership(guildId, request);
    }

    @PutMapping("/members/{discordUserId}")
    public MemberObservationResponse observeMember(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @PathVariable String guildId,
        @PathVariable String discordUserId,
        @Valid @RequestBody DiscordMemberObservationRequest request
    ) {
        authenticator.authenticate(authorization);
        return service.observeDiscordMember(guildId, discordUserId, request);
    }

    @PutMapping("/members/{discordUserId}/approval")
    public MemberObservationResponse observeMemberApproval(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @PathVariable String guildId,
        @PathVariable String discordUserId,
        @Valid @RequestBody DiscordMemberApprovalRequest request
    ) {
        authenticator.authenticate(authorization);
        return service.observeDiscordMemberApproval(guildId, discordUserId, request);
    }

    @DeleteMapping("/members/{discordUserId}")
    public MemberObservationResponse removeMember(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @PathVariable String guildId,
        @PathVariable String discordUserId
    ) {
        authenticator.authenticate(authorization);
        return service.removeDiscordMember(guildId, discordUserId);
    }

    @PutMapping("/members/snapshot/batch")
    public MemberSnapshotResponse applySnapshotBatch(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @PathVariable String guildId,
        @Valid @RequestBody DiscordMemberBatchRequest request
    ) {
        authenticator.authenticate(authorization);
        return service.applyDiscordMemberBatch(guildId, request);
    }

    @PutMapping("/members/snapshot/finalize")
    public MemberSnapshotResponse finalizeSnapshot(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @PathVariable String guildId,
        @Valid @RequestBody DiscordMemberFinalizeRequest request
    ) {
        authenticator.authenticate(authorization);
        return service.finalizeDiscordMemberSnapshot(guildId, request);
    }

    @PutMapping("/members/snapshot")
    public MemberSnapshotResponse applySnapshot(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @PathVariable String guildId,
        @Valid @RequestBody DiscordMemberSnapshotRequest request
    ) {
        authenticator.authenticate(authorization);
        return service.applyDiscordMemberSnapshot(guildId, request);
    }

    @PostMapping("/sync-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public SyncRunResponse createRun(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @PathVariable String guildId,
        @Valid @RequestBody SyncRunCreateRequest request
    ) {
        authenticator.authenticate(authorization);
        return service.createRuntimeRun(guildId, request);
    }

    @PatchMapping("/sync-runs/{runId}")
    public SyncRunResponse updateRun(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @PathVariable String guildId,
        @PathVariable Long runId,
        @Valid @RequestBody SyncRunUpdateRequest request
    ) {
        authenticator.authenticate(authorization);
        return service.updateRuntimeRun(guildId, runId, request);
    }
}
