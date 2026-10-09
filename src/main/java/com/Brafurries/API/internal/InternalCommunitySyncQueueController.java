package com.Brafurries.API.internal;

import com.Brafurries.API.internal.dto.InternalCommunityNetworkDtos.DiscordNetworkPresenceSnapshotRequest;
import com.Brafurries.API.internal.dto.InternalCommunityNetworkDtos.DiscordNetworkPresenceSnapshotResponse;
import com.Brafurries.API.internal.dto.InternalCommunityNetworkDtos.SyncRunResponse;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/community-networks/discord")
public class InternalCommunitySyncQueueController {
    private final CommunityNetworkLifecycleService service;
    private final InternalServiceTokenAuthenticator authenticator;

    public InternalCommunitySyncQueueController(
        CommunityNetworkLifecycleService service,
        InternalServiceTokenAuthenticator authenticator
    ) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @PutMapping("/presence-snapshot")
    public DiscordNetworkPresenceSnapshotResponse applyPresenceSnapshot(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @Valid @RequestBody DiscordNetworkPresenceSnapshotRequest request
    ) {
        authenticator.authenticate(authorization);
        return service.applyDiscordNetworkPresenceSnapshot(request);
    }

    @PostMapping("/sync-runs/claim")
    public ResponseEntity<SyncRunResponse> claimNext(
        @RequestHeader(name = "Authorization", required = false) String authorization
    ) {
        authenticator.authenticate(authorization);
        Optional<SyncRunResponse> claimed = service.claimNextDiscordRun();
        return claimed.map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
