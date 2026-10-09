package com.Brafurries.API.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

public final class AiConfigurationDtos {
    private AiConfigurationDtos() {}

    public record AiConfigResponse(
        String guildId,
        boolean enabled,
        String model,
        boolean tokenConfigured,
        LocalDateTime tokenUpdatedAt,
        boolean channelLimitEnabled,
        List<String> channelIds,
        boolean adminChannelBypassEnabled,
        List<String> adminUserIds
    ) {}

    public record AiConfigUpdateRequest(
        @NotNull Boolean enabled,
        @NotBlank @Size(max = 32) String model,
        @NotNull Boolean channelLimitEnabled,
        @NotNull List<
            @Pattern(regexp = "[1-9]\\d*", message = "channelId deve ser numérico e positivo")
            String
        > channelIds,
        @NotNull Boolean adminChannelBypassEnabled,
        @NotNull List<
            @Pattern(regexp = "[1-9]\\d*", message = "discordUserId deve ser numérico e positivo")
            String
        > adminUserIds
    ) {}

    public record AiTokenUpdateRequest(
        @NotBlank @Size(max = 4096) String token
    ) {}

    public record AiTokenStatusResponse(
        boolean configured,
        LocalDateTime updatedAt
    ) {}
}
