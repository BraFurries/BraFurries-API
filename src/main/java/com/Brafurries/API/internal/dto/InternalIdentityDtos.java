package com.Brafurries.API.internal.dto;

import java.util.List;

public final class InternalIdentityDtos {

    private InternalIdentityDtos() {
    }

    public record ConfirmedIdentity(Integer userId, List<String> discordUserIds) {
    }

    public record CommunityModerationSummary(
        Integer communityId,
        long warningCount,
        long activeWarningCount,
        long activeBanCount
    ) {
    }

    public record InternalIdentityResponse(
        Integer requestedUserId,
        List<ConfirmedIdentity> confirmedIdentities,
        int otherAccountCount,
        CommunityModerationSummary moderation
    ) {
    }
}
