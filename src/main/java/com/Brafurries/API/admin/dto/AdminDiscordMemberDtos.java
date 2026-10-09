package com.Brafurries.API.admin.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import java.util.List;

public final class AdminDiscordMemberDtos {

    private AdminDiscordMemberDtos() {
    }

    public record MemberState(
        @JsonAlias({"guild_id", "guildId"}) String guildId,
        @JsonAlias({"discord_user_id", "discordUserId"}) String discordUserId,
        String username,
        @JsonAlias({"display_name", "displayName"}) String displayName,
        String nickname,
        @JsonAlias({"joined_at", "joinedAt"}) String joinedAt,
        List<MemberRole> roles
    ) {
    }

    public record MemberRole(String id, String name, String color, int position, boolean managed) {
    }
}
