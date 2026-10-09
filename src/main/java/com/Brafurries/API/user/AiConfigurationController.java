package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.AiConfigurationDtos.*;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/guilds/{guildId}/ai")
public class AiConfigurationController {
    private final AiConfigurationService service;

    public AiConfigurationController(AiConfigurationService service) {
        this.service = service;
    }

    @GetMapping
    public AiConfigResponse get(
        Authentication authentication,
        @PathVariable String guildId
    ) {
        return service.get(authentication, guildId);
    }

    @PutMapping
    public AiConfigResponse update(
        Authentication authentication,
        @PathVariable String guildId,
        @RequestBody @Valid AiConfigUpdateRequest request
    ) {
        return service.update(authentication, guildId, request);
    }

    @PutMapping("/token")
    public AiTokenStatusResponse rotateToken(
        Authentication authentication,
        @PathVariable String guildId,
        @RequestBody @Valid AiTokenUpdateRequest request
    ) {
        return service.rotateToken(authentication, guildId, request);
    }

    @DeleteMapping("/token")
    public AiTokenStatusResponse removeToken(
        Authentication authentication,
        @PathVariable String guildId
    ) {
        return service.removeToken(authentication, guildId);
    }
}
