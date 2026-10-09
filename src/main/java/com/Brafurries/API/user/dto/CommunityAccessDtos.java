package com.Brafurries.API.user.dto;

import java.util.List;
import java.util.Set;

public final class CommunityAccessDtos {
    private CommunityAccessDtos() {}

    public record CommunityAccessResponse(
        Integer communityId,
        boolean owner,
        boolean communityAdmin,
        boolean activeMember,
        boolean canManageCommunityAdmins,
        List<Integer> activeRoleIds,
        Set<String> capabilities,
        List<TeamCapabilityScope> teamScopes
    ) {}

    public record TeamCapabilityScope(
        String capability,
        boolean unrestricted,
        List<Integer> sourceRoleIds,
        List<Integer> targetRoleIds
    ) {}
}
