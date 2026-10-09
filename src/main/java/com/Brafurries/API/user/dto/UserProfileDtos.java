package com.Brafurries.API.user.dto;

import java.time.LocalDateTime;
import java.util.List;

public class UserProfileDtos {

    public record UserProfileResponse(
        Integer userId,
        String displayName,
        String username,
        String email,
        String profileImageUrl,
        List<UserServerSummary> servers,
        UserServerData selectedServer
    ) {
    }

    public record UserProfileImageResponse(
        Integer userId,
        String profileImageUrl
    ) {
    }

    public record UserServerSummary(
        Integer communityId,
        String communityName,
        Long guildId
    ) {
    }

    public record UserServerData(
        Integer communityId,
        String communityName,
        Long guildId,
        LocalDateTime memberSince,
        LocalDateTime lastJoinDate,
        Boolean approved,
        LocalDateTime approvedAt,
        Boolean isVip,
        Boolean isPartner,
        Boolean banned,
        Boolean birthdayMentionable,
        Integer bankBalance
    ) {
    }
}
