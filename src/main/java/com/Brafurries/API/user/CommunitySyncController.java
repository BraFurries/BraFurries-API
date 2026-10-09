package com.Brafurries.API.user;

import com.Brafurries.API.internal.CommunityNetworkLifecycleService;
import com.Brafurries.API.internal.dto.InternalCommunityNetworkDtos.SyncQueueResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/me/communities/{communityId}/sync")
public class CommunitySyncController {
    private final CommunityNetworkLifecycleService service;

    public CommunitySyncController(CommunityNetworkLifecycleService service) {
        this.service = service;
    }

    @Operation(summary = "Retorna a última sincronização da Community autorizada")
    @GetMapping
    public ResponseEntity<com.Brafurries.API.internal.dto.InternalCommunityNetworkDtos.SyncRunResponse> latest(
        Authentication authentication,
        @PathVariable Integer communityId
    ) {
        return service.latestCommunitySync(authentication, communityId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @Operation(summary = "Enfileira sincronização da Community autorizada")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SyncQueueResponse enqueue(
        Authentication authentication,
        @PathVariable Integer communityId
    ) {
        return service.enqueueCommunitySync(authentication, communityId);
    }
}
