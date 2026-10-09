package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.VipConfigurationDtos.*;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/guilds/{guildId}/vip")
public class VipConfigurationController {
    private final VipConfigurationService service;

    public VipConfigurationController(VipConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    public VipConfigResponse get(Authentication auth, @PathVariable String guildId) {
        return service.get(auth, guildId);
    }

    @PutMapping
    public VipConfigResponse update(
        Authentication auth,
        @PathVariable String guildId,
        @RequestBody @Valid VipConfigUpdateRequest request
    ) {
        return service.update(auth, guildId, request);
    }
}
