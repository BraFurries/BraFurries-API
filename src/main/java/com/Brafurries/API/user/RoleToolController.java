package com.Brafurries.API.user;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/guilds/{guildId}/role-tools")
public class RoleToolController {
    private final RoleToolService service;

    public RoleToolController(RoleToolService service) {
        this.service = service;
    }

    @GetMapping("/operations")
    public List<RoleToolService.Operation> list(Authentication actor, @PathVariable String guildId) {
        return service.list(actor, guildId);
    }

    @GetMapping("/operations/{operationId}")
    public RoleToolService.Operation get(Authentication actor, @PathVariable String guildId,
                                         @PathVariable long operationId) {
        return service.get(actor, guildId, operationId);
    }

    @PostMapping("/operations/{operationId}/cancel")
    public RoleToolService.Operation cancelPending(Authentication actor, @PathVariable String guildId,
                                                  @PathVariable long operationId) {
        return service.cancelPending(actor, guildId, operationId);
    }

    @PostMapping("/bulk")
    public RoleToolService.Operation bulk(Authentication actor, @PathVariable String guildId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody RoleToolService.BulkRequest request) {
        return service.bulk(actor, guildId, request, key);
    }

    @PostMapping("/recovery/scans")
    public RoleToolService.Operation scan(Authentication actor, @PathVariable String guildId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody RoleToolService.ScanRequest request) {
        return service.scan(actor, guildId, request, key);
    }

    @PostMapping("/recovery/apply")
    public RoleToolService.Operation apply(Authentication actor, @PathVariable String guildId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestBody RoleToolService.ApplyRequest request) {
        return service.apply(actor, guildId, request, key);
    }
}
