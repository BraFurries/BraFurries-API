package com.Brafurries.API.user.dto;

import java.time.LocalDateTime;

public final class CommunityTeamAssignmentDtos {
    private CommunityTeamAssignmentDtos() {}

    public record AssignmentResponse(
        Integer roleId,
        Integer userId,
        LocalDateTime createdAt
    ) {}

    public record TeamMemberRef(
        Integer userId,
        String displayName,
        String username,
        String profileImageUrl
    ) {}

    public record TeamMemberCandidatePagination(
        int page,
        int pageSize,
        long totalElements,
        int totalPages
    ) {}

    public record TeamMemberCandidateResponse(
        java.util.List<TeamMemberRef> items,
        TeamMemberCandidatePagination pagination
    ) {}
}
