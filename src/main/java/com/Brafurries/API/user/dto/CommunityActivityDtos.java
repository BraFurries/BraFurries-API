package com.Brafurries.API.user.dto;

import java.time.LocalDateTime;
import java.util.List;

public final class CommunityActivityDtos {
    private CommunityActivityDtos() {}

    public record ActivityPage(
        List<ActivityEvent> items,
        String nextCursor,
        boolean hasMore
    ) {}

    public record ActivityEvent(
        Long id,
        String action,
        String category,
        LocalDateTime createdAt,
        ActivityActor actor,
        ActivityTarget target,
        ActivityDetails details,
        boolean systemGenerated
    ) {}

    public record ActivityActor(
        Integer userId,
        String displayName,
        String avatarUrl
    ) {}

    public record ActivityActorOption(
        Integer userId,
        String displayName,
        String avatarUrl
    ) {}

    public record ActivityTarget(
        String type,
        String id,
        String displayName
    ) {}

    public record ActivityDetails(
        String capability,
        Boolean active,
        Integer roleId,
        String roleName,
        Integer userId,
        String userDisplayName,
        Integer memberUserId,
        String memberDisplayName,
        Integer parentRoleId,
        String parentRoleName,
        List<Integer> roleIds,
        List<String> roleNames,
        String source
    ) {}
}
