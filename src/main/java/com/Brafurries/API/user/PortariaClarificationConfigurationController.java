package com.Brafurries.API.user;

import com.Brafurries.API.user.PortariaClarificationConfigurationService.ClarificationConfig;
import com.Brafurries.API.user.PortariaClarificationConfigurationService.UpdateClarificationConfig;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/guilds/{guildId}/portaria/clarification")
public class PortariaClarificationConfigurationController {
    private final PortariaClarificationConfigurationService service;

    public PortariaClarificationConfigurationController(
        PortariaClarificationConfigurationService service
    ) {
        this.service = service;
    }

    @GetMapping
    public ClarificationConfig get(Authentication auth, @PathVariable String guildId) {
        return service.get(auth, guildId);
    }

    @PutMapping
    public ClarificationConfig update(
        Authentication auth,
        @PathVariable String guildId,
        @RequestBody UpdateClarificationConfig request
    ) {
        return service.update(auth, guildId, request);
    }
}
