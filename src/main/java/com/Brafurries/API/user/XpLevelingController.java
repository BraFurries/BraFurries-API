package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.XpLevelingDtos.*;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/guilds/{guildId}/xp")
public class XpLevelingController {
    private final XpLevelingService service;

    public XpLevelingController(XpLevelingService service) {
        this.service = service;
    }

    @GetMapping
    public XpConfigResponse get(Authentication auth, @PathVariable String guildId) {
        return service.get(auth, guildId);
    }

    @PutMapping
    public XpConfigResponse update(
        Authentication auth,
        @PathVariable String guildId,
        @RequestBody @Valid XpConfigUpdateRequest request
    ) {
        return service.update(auth, guildId, request);
    }

    @PostMapping("/simulation")
    public XpSimulationResponse simulate(
        Authentication auth,
        @PathVariable String guildId,
        @RequestBody @Valid XpSimulationRequest request
    ) {
        return service.simulate(auth, guildId, request);
    }
}
