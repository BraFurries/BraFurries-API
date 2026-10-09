package com.Brafurries.API.user.dto;

import java.util.List;

public final class CommunityProvisioningDtos {
    private CommunityProvisioningDtos() {}

    public record CommunityMemberSummary(
        Integer userId,
        String displayName,
        String username,
        String profileImageUrl,
        String status,
        List<Integer> roleIds
    ) {}

    public record CommunityMemberSummaryPagination(
        int page,
        int pageSize,
        long totalElements,
        int totalPages
    ) {}

    public record CommunityMemberSummaryResponse(
        List<CommunityMemberSummary> items,
        CommunityMemberSummaryPagination pagination
    ) {}
}
