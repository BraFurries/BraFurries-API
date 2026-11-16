package com.Brafurries.API.admin;

import com.Brafurries.API.admin.AdminRecordBlacklistService.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/records")
@PreAuthorize("hasRole('ADMIN')")
public class AdminRecordBlacklistController {
    private final AdminRecordBlacklistService service;
    public AdminRecordBlacklistController(AdminRecordBlacklistService service) {
        this.service = service;
    }

    @GetMapping("/guilds")
    public List<Guild> guilds() { return service.guildOptions(); }

    @GetMapping("/communities/{communityId}/guilds/{guildId}/blacklist")
    public ListView list(@PathVariable int communityId, @PathVariable String guildId) {
        return service.list(communityId, guildId);
    }

    @PostMapping("/communities/{communityId}/guilds/{guildId}/blacklist")
    public ListView add(Authentication actor, @PathVariable int communityId,
                        @PathVariable String guildId, @RequestBody CreateRequest request) {
        return service.add(actor, communityId, guildId, request);
    }

    @DeleteMapping("/communities/{communityId}/guilds/{guildId}/blacklist/{entryId}")
    public ListView remove(Authentication actor, @PathVariable int communityId,
                           @PathVariable String guildId, @PathVariable int entryId) {
        return service.remove(actor, communityId, guildId, entryId);
    }
}
