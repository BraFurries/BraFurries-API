package com.Brafurries.API.user.dto;

import java.time.LocalDate;

public class UserGeneralInfoDtos {

    public record UserGeneralInfoResponse(
        Integer userId,
        String displayName,
        String email,
        String profileImageUrl,
        LocalDate birthday,
        String locale,
        UserDiscordInfo discord,
        UserTelegramInfo telegram
    ) {
    }

    public record UserDiscordInfo(
        Long discordUserId,
        String username,
        String displayName
    ) {
    }

    public record UserTelegramInfo(
        Integer telegramUserId,
        String username,
        String displayName
    ) {
    }
}
