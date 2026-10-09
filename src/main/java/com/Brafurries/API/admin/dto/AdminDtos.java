package com.Brafurries.API.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;

public class AdminDtos {

    public record AdminSession(
        Integer userId,
        String name,
        String role,
        List<String> permissions
    ) {
    }

    public record AdminOverview(
        String systemStatus,
        AdminOverviewMetrics metrics,
        List<AdminRecentActivity> recentActivities
    ) {
    }

    public record AdminOverviewMetrics(
        long totalUsers,
        long newUsersWeek,
        long activePartners,
        long pendingPartners,
        String botStatus,
        long botPingMs,
        long botServers,
        long serverGrowthWeek,
        long botReachedUsers
    ) {
    }

    public record AdminRecentActivity(
        Integer id,
        String type,
        String targetName,
        String reason,
        String createdAt,
        String adminName
    ) {
    }

    public record AdminUsersResponse(
        List<AdminUserItem> items,
        AdminPagination pagination
    ) {
    }

    public record AdminUserItem(
        Integer id,
        String name,
        String avatarUrl,
        String email,
        String discordUsername,
        boolean discordAmbiguous,
        String telegramUsername,
        String status,
        String role,
        String joinedAt
    ) {
    }

    public record AdminPagination(
        int page,
        int pageSize,
        long totalItems,
        int totalPages
    ) {
    }

    public record UpdateUserStatusRequest(
        @NotBlank String status,
        String reason
    ) {
    }

    public record UpdateUserRoleRequest(
        @NotBlank String role
    ) {
    }

    public record AdminPartnersResponse(
        List<AdminPartnerItem> items
    ) {
    }

    public record AdminPartnerItem(
        Integer id,
        String name,
        String representativeName,
        Integer representativeUserId,
        PartnerRepresentative representative,
        String status,
        String category,
        String description,
        String imageUrl,
        String websiteUrl,
        String contactName,
        String contactEmail,
        PartnerLinkedResource linkedResource,
        List<PartnerExternalLinkItem> links,
        long clicks,
        long invites,
        String createdAt
    ) {
        public AdminPartnerItem(
            Integer id, String name, String representativeName, Integer representativeUserId,
            String status, String category, String description, String imageUrl, String websiteUrl,
            String contactName, String contactEmail, PartnerLinkedResource linkedResource,
            List<PartnerExternalLinkItem> links, long clicks, long invites, String createdAt
        ) {
            this(id, name, representativeName, representativeUserId, null, status, category, description,
                imageUrl, websiteUrl, contactName, contactEmail, linkedResource, links, clicks, invites, createdAt);
        }
    }

    public record PartnerRepresentative(
        Integer id,
        String name,
        String avatarUrl,
        String email,
        String discordUsername,
        String telegramUsername
    ) {
    }

    public record PartnerImageResponse(Integer partnerId, String imageUrl) {}

    public record PartnerLinkedResource(
        String type,
        Integer id,
        String externalRef,
        String label
    ) {
    }

    public record PartnerExternalLinkItem(
        String type,
        String label,
        String url
    ) {
    }

    public record PartnerLinkCandidate(
        String resourceType,
        Integer id,
        String externalRef,
        String name,
        String subtitle,
        String imageUrl,
        PartnerPrefill prefill
    ) {
    }

    public record PartnerPrefill(
        String name,
        String description,
        String imageUrl,
        String websiteUrl,
        String contactName,
        String contactEmail,
        List<PartnerExternalLinkItem> links
    ) {
    }

    public record CreatePartnerRequest(
        @NotBlank String name,
        String slug,
        @NotBlank String category,
        String status,
        String description,
        String imageUrl,
        String websiteUrl,
        String contactName,
        String contactEmail,
        Integer representativeUserId,
        Integer communityId,
        String representativeName,
        PartnerLinkedResource linkedResource,
        List<PartnerExternalLinkItem> links
    ) {
    }

    public record UpdatePartnerRequest(
        String name,
        String slug,
        String category,
        String status,
        String description,
        String imageUrl,
        String websiteUrl,
        String contactName,
        String contactEmail,
        Integer representativeUserId,
        Boolean clearRepresentativeUser,
        Integer communityId,
        String representativeName,
        PartnerLinkedResource linkedResource,
        Boolean clearLinkedResource,
        List<PartnerExternalLinkItem> links
    ) {
        public UpdatePartnerRequest(
            String name, String slug, String category, String status, String description, String imageUrl,
            String websiteUrl, String contactName, String contactEmail, Integer representativeUserId,
            Integer communityId, String representativeName, PartnerLinkedResource linkedResource,
            Boolean clearLinkedResource, List<PartnerExternalLinkItem> links
        ) {
            this(name, slug, category, status, description, imageUrl, websiteUrl, contactName, contactEmail,
                representativeUserId, null, communityId, representativeName, linkedResource, clearLinkedResource, links);
        }
    }

    public record UpdatePartnerStatusRequest(
        @NotBlank String status
    ) {
    }

    public record AdminBotStatus(
        String status,
        Boolean ready,
        Boolean connected,
        Long uptimeSeconds,
        String startedAt,
        String lastReadyAt,
        Double memoryUsedMb,
        Double memoryLimitMb,
        Double cpuUsagePercent,
        Double pingMs,
        Long guilds,
        Long users,
        Long commandsLoaded,
        Long cogsLoaded,
        String pythonVersion,
        String discordPyVersion,
        Long processId
    ) {
    }

    public record AdminBotLogs(
        String status,
        List<AdminBotLogEntry> items,
        int totalBuffered,
        int limit,
        Long oldestSequence,
        Long latestSequence,
        Long nextSequence,
        boolean cursorExpired
    ) {
    }

    public record AdminBotLogEntry(
        Long sequence,
        String timestamp,
        String level,
        String logger,
        String message
    ) {
    }

    public record AdminMetrics(
        AdminMetricsSummary summary,
        List<AdminMetricPoint> communityGrowth,
        List<AdminPlatformDistribution> platformDistribution,
        List<AdminTopCommand> topCommands
    ) {
    }

    public record AdminMetricsSummary(
        long commandsExecuted,
        double commandsGrowthPercent,
        double discordLinkRate,
        double telegramLinkRate,
        long dailyUniqueVisits,
        long newRegistrations
    ) {
    }

    public record AdminMetricPoint(
        String label,
        long value
    ) {
    }

    public record AdminPlatformDistribution(
        String platform,
        long members,
        double percentage
    ) {
    }

    public record AdminTopCommand(
        String command,
        long uses,
        String description
    ) {
    }

    public record AnnouncementConfig(
        List<AnnouncementDestination> destinations,
        int maxImageSizeMb,
        List<String> allowedImageTypes
    ) {
    }

    public record AnnouncementDestination(
        String id,
        String label,
        boolean enabled,
        String targetName
    ) {
    }

    public record CreateAnnouncementRequest(
        @NotEmpty List<String> destinations,
        @NotBlank String title,
        @NotBlank String message
    ) {
    }

    public record CreateAnnouncementResponse(
        Integer id,
        String status,
        List<AnnouncementResult> results
    ) {
    }

    public record AnnouncementResult(
        String destination,
        String status,
        String error
    ) {
    }
}
