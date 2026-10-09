package com.Brafurries.API.user.dto;

import java.time.LocalDateTime;
import java.util.List;

public final class CommunityMemberDtos {
    private CommunityMemberDtos() {}

    public record CommunityMember(
        Integer userId,
        String displayName,
        String username,
        String profileImageUrl,
        MemberDataState membershipDataState,
        int membershipRecordCount,
        LocalDateTime memberSince,
        String status,
        Boolean approved,
        Boolean approvalRequired,
        Boolean banned,
        Boolean present,
        Boolean vip,
        Boolean partner
    ) {}


    public record CommunityMemberDetail(
        Integer userId,
        String displayName,
        String username,
        String profileImageUrl,
        MemberDataState membershipDataState,
        int membershipRecordCount,
        LocalDateTime memberSince,
        LocalDateTime lastJoinDate,
        String status,
        Boolean approved,
        Boolean approvalRequired,
        LocalDateTime approvedAt,
        Boolean banned,
        Boolean present,
        LocalDateTime leftAt,
        Boolean vip,
        Boolean partner,
        List<Integer> roleIds
    ) {}

    public record CommunityMembersPagination(
        int page,
        int pageSize,
        long totalElements,
        int totalPages
    ) {}

    public record CommunityMembersResponse(
        List<CommunityMember> items,
        CommunityMembersPagination pagination
    ) {}
}
