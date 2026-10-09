package com.Brafurries.API.user.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class MemberProfileDtos {
    private MemberProfileDtos() {}

    public record MemberProfileResponse(
        Integer userId,
        String displayName,
        String username,
        String profileImageUrl,
        LocalDate birthday,
        ExternalAccountState discord,
        ExternalAccountState telegram,
        int communityCount,
        FirstKnownCommunity firstKnownCommunity,
        List<MemberCommunitySummary> communities
    ) {}

    public record ExternalAccountState(
        MemberDataState state,
        int recordCount,
        String username,
        String displayName
    ) {}

    public record MemberCommunitySummary(
        Integer communityId,
        String communityName,
        String guildId,
        MemberDataState membershipState,
        int membershipRecordCount
    ) {}

    public record FirstKnownCommunity(
        MemberDataState state,
        Integer communityId,
        String communityName,
        LocalDateTime memberSince
    ) {}
}
