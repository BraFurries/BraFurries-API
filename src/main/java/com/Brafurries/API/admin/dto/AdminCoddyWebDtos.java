package com.Brafurries.API.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Global Coddy administration. Guild IDs remain strings at the browser edge. */
public final class AdminCoddyWebDtos {
    private AdminCoddyWebDtos() {}

    public record Presence(
        String activityType,
        String activityText,
        String availability
    ) {}

    public record PresenceView(Presence desired, Presence current, boolean configured) {}

    public record PresenceUpdateRequest(
        @NotBlank @Pattern(regexp = "PLAYING|LISTENING|WATCHING|CUSTOM") String activityType,
        @NotBlank @Size(max = 128) String activityText,
        @NotBlank @Pattern(regexp = "online|idle|dnd|invisible") String availability
    ) {}

    public record ChannelTarget(String channelId, String name, String kind) {}
    public record GuildTarget(int communityId, String communityName,
                              String guildId, String guildName, List<ChannelTarget> channels) {}
    public record Targets(List<GuildTarget> guilds) {}

    public record SendRequest(
        @NotNull Integer communityId,
        @NotBlank String guildId,
        @NotBlank String channelId,
        @NotBlank @Size(max = 1800) String message,
        @NotBlank String requestKey
    ) {}

    public record SendResult(
        String requestKey,
        String outcome,
        String guildId,
        String channelId,
        String messageId
    ) {}
}
