package com.Brafurries.API.user.dto;

import java.time.LocalDateTime;
import java.util.List;

public class UserDashboardDtos {

    public record UserDashboardResponse(
            DiscordConnectionStatus discord,
            MyStatus myStatus,
            List<DashboardServerItem> servers,
            List<DashboardAnalyticsItem> analytics,
            DashboardManagedEvent nextManagedEvent,
            List<DashboardEventItem> events,
            List<DashboardEventItem> meets
    ) {
    }

    public record DiscordConnectionStatus(
            Boolean connected,
            Long discordUserId,
            String username,
            String displayName
    ) {
    }

    public record MyStatus(
            Integer level,
            String eventsAtended,
            String lastBadge
    ) {
    }

    public record DashboardServerItem(
            String name,
            Integer totalMembers,
            String newMembers7D,
            String engagement
    ) {
    }

    public record DashboardAnalyticsItem(
            String name,
            String data
    ) {
    }

    public record DashboardManagedEvent(
            Integer id,
            String eventName,
            String point,
            LocalDateTime startingDatetime,
            LocalDateTime endingDatetime,
            String eventLogoUrl
    ) {
    }

    public record DashboardEventItem(
            Integer id,
            String eventName,
            String point,
            LocalDateTime startingDatetime,
            LocalDateTime endingDatetime,
            String eventLogoUrl,
            Boolean isEvent,
            Boolean partnerEvent
    ) {
    }
}
