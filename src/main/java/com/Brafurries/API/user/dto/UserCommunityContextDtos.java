package com.Brafurries.API.user.dto;

import java.time.LocalDateTime;
import java.util.List;

public final class UserCommunityContextDtos {
    private UserCommunityContextDtos() {}

    public record MemberCommunityContextResponse(
        MemberCommunityRef community,
        MemberMembershipData membership,
        MemberProgressionData progression,
        MemberEconomyData economy,
        List<MemberInventoryItem> inventory,
        MemberModerationSummary moderation
    ) {}

    public record MemberCommunityRef(Integer communityId, String communityName, String guildId) {}

    public record MemberMembershipData(
        MemberDataState state,
        int recordCount,
        LocalDateTime memberSince,
        LocalDateTime lastJoinDate,
        Boolean approved,
        LocalDateTime approvedAt,
        Boolean present,
        LocalDateTime leftAt,
        Boolean vip,
        Boolean partner
    ) {}

    public record MemberProgressionData(MemberDataState state, int recordCount, Integer level, Long totalXp) {}

    public record MemberEconomyData(MemberDataState state, int recordCount, Integer balance) {}

    public record MemberInventoryItem(
        Integer inventoryId,
        Integer storeItemId,
        String name,
        String imageUrl,
        Integer quantity,
        Boolean service,
        LocalDateTime usedAt,
        LocalDateTime validUntil
    ) {}

    public record MemberModerationSummary(long totalWarnings, long activeWarnings, long totalBans, long activeBans) {}
}
