package com.Brafurries.API.admin.dto;

import java.util.List;

/** Read-only data intentionally limited to fields persisted by the current schema. */
public final class AdminUserProfileDtos {

    private AdminUserProfileDtos() {
    }

    public record AdminUserProfileResponse(
        Identity identity,
        String firstKnownAt,
        FirstKnownCommunity firstKnownCommunity,
        List<CommunityMembership> communities,
        Moderation moderation
    ) {
    }

    public record Identity(
        Integer id,
        String displayName,
        String username,
        String email,
        String avatarUrl,
        String discordUsername,
        String telegramUsername,
        String status,
        String role,
        String birthDate,
        Boolean plus18,
        Boolean birthdayVerified,
        Boolean birthdayRegistered,
        Integer localeId,
        String localeAbbrev,
        String localeName,
        boolean localeAmbiguous,
        boolean discordAmbiguous,
        boolean telegramAmbiguous
    ) {
    }

    public record FirstKnownCommunity(Integer id, String name) {
    }

    public record CommunityMembership(
        Integer statusRecordId,
        Integer id,
        String name,
        String guildId,
        String memberSince,
        String lastJoinDate,
        String approvedAt,
        Boolean isPresent,
        String leftAt,
        String status,
        Boolean approved,
        Boolean banned,
        boolean vip,
        boolean partner,
        Boolean birthdayMentionable,
        String inviteLinkUsed,
        String invitedBy,
        boolean discordActive
    ) {
    }

    public record Moderation(
        long totalWarnings,
        long activeWarnings,
        long totalBans,
        long activeBans,
        long expiredBans,
        long revokedBans,
        List<ModerationRecord> records,
        AdminDtos.AdminPagination pagination
    ) {
    }

    public record ModerationRecord(
        Integer id,
        String type,
        String reason,
        String moderator,
        String communityName,
        String occurredAt,
        boolean active,
        String status,
        Boolean canAppeal,
        String validUntil,
        String registeredAt,
        String revokedAt,
        Integer revokedById,
        String revokedByName,
        String revocationReason
    ) {
    }
}
