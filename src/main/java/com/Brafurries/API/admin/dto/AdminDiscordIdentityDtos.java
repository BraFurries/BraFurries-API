package com.Brafurries.API.admin.dto;

import com.Brafurries.API.admin.dto.UserIdentityDtos.IdentityLinkView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AdminDiscordIdentityDtos {

    private AdminDiscordIdentityDtos() {
    }

    public record DiscordUserState(
        String discordUserId,
        String username,
        String displayName,
        String avatarUrl,
        boolean bot
    ) {
    }

    public record DiscordIdentityPreview(
        String discordUserId,
        String username,
        String displayName,
        String avatarUrl,
        boolean bot,
        Integer existingUserId,
        String existingUserDisplayName,
        boolean alreadyInConfirmedCluster
    ) {
    }

    public record LinkDiscordIdentityRequest(
        @NotBlank
        @Pattern(regexp = "\\d{1,20}", message = "Discord User ID deve conter somente números")
        String discordUserId,
        @NotBlank
        @Size(max = 500)
        String reason
    ) {
    }

    public record LinkDiscordIdentityResponse(
        Integer targetUserId,
        boolean createdUser,
        IdentityLinkView link
    ) {
    }
}
