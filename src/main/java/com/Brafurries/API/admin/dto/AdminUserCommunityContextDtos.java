package com.Brafurries.API.admin.dto;

import java.util.List;

public final class AdminUserCommunityContextDtos {

    private AdminUserCommunityContextDtos() {
    }

    public record CommunityContextResponse(
        Integer userId,
        Integer communityId,
        String communityName,
        String guildId,
        List<MembershipRecord> memberships,
        Progression progression,
        Economy economy,
        List<InventoryItem> inventory,
        Vip vip,
        List<TemporaryRole> temporaryRoles
    ) {
    }

    public record MembershipRecord(
        Integer id,
        String memberSince,
        String lastJoinDate,
        Boolean approved,
        String approvedAt,
        String inviteLinkUsed,
        String invitedBy,
        Boolean vip,
        Boolean partner,
        Boolean banned,
        Boolean present,
        String leftAt,
        Boolean birthdayMentionable
    ) {
    }

    public record Progression(
        String state,
        int recordCount,
        Integer level,
        Long totalXp,
        Integer dailyRecsCount,
        Integer weeklyBumpXp,
        Integer totalXpToday,
        String totalXpDay,
        Integer textXpToday,
        String textXpDay,
        Integer voiceXpToday,
        String voiceXpDay
    ) {
    }

    public record Economy(String state, int recordCount, List<EconomyRecord> records) {
    }

    public record EconomyRecord(Integer id, Integer balance) {
    }

    public record InventoryItem(
        Integer id,
        Integer storeItemId,
        String name,
        Integer quantity,
        boolean service,
        String usedIn,
        String validUntil
    ) {
    }

    public record Vip(Boolean membershipVip, List<CustomRole> customRoles) {
    }

    public record CustomRole(Integer id, String color, String color2, String roleId, String iconId) {
    }

    public record TemporaryRole(Integer id, String roleId, String expiringAt, String reason) {
    }
}
