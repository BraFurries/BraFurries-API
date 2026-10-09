package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.GuildManagementDtos.StaffRolesRequest;
import static com.Brafurries.API.user.dto.ModerationAccessDtos.*;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/guilds/{guildId}")
public class ModerationAccessController {
    private final ModerationAccessService service;

    public ModerationAccessController(ModerationAccessService service) {
        this.service = service;
    }

    @GetMapping("/moderation-access")
    public ModerationAccessResponse moderationAccess(
        Authentication authentication,
        @PathVariable String guildId
    ) {
        return service.get(authentication, guildId);
    }

    @PutMapping("/moderation-access/staff-roles")
    public ModerationAccessResponse updateStaffRoles(
        Authentication authentication,
        @PathVariable String guildId,
        @RequestBody @Valid StaffRolesRequest request
    ) {
        return service.updateStaff(authentication, guildId, request);
    }

    @PutMapping("/moderation-access/collaborative-moderation")
    public ModerationAccessResponse updateCollaborativeModeration(
        Authentication authentication,
        @PathVariable String guildId,
        @RequestBody @Valid CollaborativeModerationUpdateRequest request
    ) {
        return service.updateCollaborativeModeration(authentication, guildId, request);
    }

    @GetMapping("/portaria/bypasses")
    public PortariaBypassesResponse bypasses(
        Authentication authentication,
        @PathVariable String guildId
    ) {
        return service.bypasses(authentication, guildId);
    }

    @PostMapping("/portaria/bypasses")
    public PortariaBypassResponse createBypass(
        Authentication authentication,
        @PathVariable String guildId,
        @RequestBody @Valid PortariaBypassCreateRequest request
    ) {
        return service.createBypass(authentication, guildId, request);
    }

    @PutMapping("/portaria/bypasses/{type}/{bypassId}")
    public PortariaBypassResponse updateBypass(
        Authentication authentication,
        @PathVariable String guildId,
        @PathVariable String type,
        @PathVariable long bypassId,
        @RequestBody PortariaBypassUpdateRequest request
    ) {
        return service.updateBypass(authentication, guildId, type, bypassId, request);
    }

    @DeleteMapping("/portaria/bypasses/{type}/{bypassId}")
    public void removeBypass(
        Authentication authentication,
        @PathVariable String guildId,
        @PathVariable String type,
        @PathVariable long bypassId
    ) {
        service.removeBypass(authentication, guildId, type, bypassId);
    }
}
