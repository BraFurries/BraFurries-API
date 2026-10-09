package com.Brafurries.API.user.dto;

public class UserManagedServerDtos {

    public record ManagedServerResponse(
        String guildId,
        String name,
        Long memberCount,
        boolean botOnline,
        String iconUrl
    ) {
    }
}
