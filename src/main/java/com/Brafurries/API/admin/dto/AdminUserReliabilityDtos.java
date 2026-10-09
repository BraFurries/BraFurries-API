package com.Brafurries.API.admin.dto;

import java.util.List;

public class AdminUserReliabilityDtos {

    public record UserReliabilityResponse(
        UserSummary user,
        ModerationSummary moderation,
        EventSummary events,
        CommunitySummary communities,
        AccountSummary account,
        ReliabilitySummary reliability
    ) {
    }

    public record UserSummary(
        Integer id,
        String email,
        String username,
        String displayName
    ) {
    }

    public record ModerationSummary(
        long totalWarnings,
        long activeWarnings,
        long expiredWarnings,
        long warnedServers,
        double averageWarningsPerWarnedServer,
        long totalBans,
        long activeBans,
        long expiredBans,
        long revokedBans,
        long bannedServers,
        List<WarningsByServer> warningsByServer
    ) {
    }

    public record WarningsByServer(
        Integer communityId,
        String communityName,
        long totalWarnings,
        long activeWarnings,
        long expiredWarnings
    ) {
    }

    public record EventSummary(
        long managedEvents,
        long ownedEvents,
        long ownedApprovedEvents,
        long ownedPendingEvents,
        long ownedRejectedEvents,
        long ownedPartnerEvents,
        long staffEvents,
        long staffEventsWithManageStaff,
        long staffEventsWithEditEvent,
        long staffEventsWithManageAgenda
    ) {
    }

    public record CommunitySummary(
        long memberships,
        long approvedMemberships,
        long bannedMemberships,
        long vipMemberships,
        long partnerMemberships
    ) {
    }

    public record AccountSummary(
        boolean linkedDiscord,
        Long discordUserId,
        String discordUsername,
        boolean linkedTelegram,
        Integer telegramUserId,
        String telegramUsername,
        boolean discordAmbiguous,
        boolean telegramAmbiguous
    ) {
    }

    public record ReliabilitySummary(
        int score,
        String level,
        List<String> signals
    ) {
    }
}
