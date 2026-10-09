package com.Brafurries.API.admin.dto;

public class AdminMetricsDtos {

    public record GeneralBotMetricsResponse(
        long totalDiscordCommunities,
        long activeDiscordCommunities,
        long inactiveDiscordCommunities,
        long totalMembersReachedActiveCommunities,
        long totalMembersReachedAllCommunities,
        long totalRegisteredUsers,
        long totalLinkedDiscordUsers,
        long totalLinkedTelegramUsers
    ) {
    }
}
