package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminDiscordIdentityDtos.DiscordIdentityPreview;
import com.Brafurries.API.admin.dto.AdminDiscordIdentityDtos.LinkDiscordIdentityRequest;
import com.Brafurries.API.admin.dto.AdminDiscordIdentityDtos.LinkDiscordIdentityResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminDiscordIdentityController {

    private final AdminDiscordIdentityService service;

    public AdminDiscordIdentityController(AdminDiscordIdentityService service) {
        this.service = service;
    }

    @GetMapping("/{userId}/discord-identities/{discordUserId}/preview")
    public DiscordIdentityPreview preview(
        @PathVariable Integer userId,
        @PathVariable String discordUserId
    ) {
        return service.preview(userId, discordUserId);
    }

    @PostMapping("/{userId}/discord-identities")
    public LinkDiscordIdentityResponse link(
        Authentication authentication,
        @PathVariable Integer userId,
        @Valid @RequestBody LinkDiscordIdentityRequest request
    ) {
        return service.confirm(
            userId,
            request.discordUserId(),
            request.reason(),
            authentication.getName()
        );
    }
}
