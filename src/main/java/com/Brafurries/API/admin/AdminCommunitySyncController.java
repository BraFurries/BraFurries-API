package com.Brafurries.API.admin;

import com.Brafurries.API.internal.CommunityNetworkLifecycleService;
import com.Brafurries.API.internal.dto.InternalCommunityNetworkDtos.SyncQueueResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/communities/sync")
public class AdminCommunitySyncController {
    private final CommunityNetworkLifecycleService service;

    public AdminCommunitySyncController(CommunityNetworkLifecycleService service) {
        this.service = service;
    }

    @Operation(summary = "Enfileira sincronização global das integrações ativas")
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SyncQueueResponse enqueueGlobal() {
        return service.enqueueGlobalSync();
    }
}
