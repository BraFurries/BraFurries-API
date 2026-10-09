package com.Brafurries.API.user.dto;

import java.util.List;
import java.util.Set;

public final class CommunityCapabilityManagementDtos {
    private CommunityCapabilityManagementDtos() {}

    public record CapabilityDefinition(
        String code,
        boolean directGrantAllowed,
        boolean roleGrantAllowed,
        boolean hierarchicalTeamScope
    ) {}

    public record CapabilityCatalogResponse(
        List<CapabilityDefinition> capabilities
    ) {}

    public record DirectCapabilityGrantsResponse(
        Integer userId,
        Set<String> capabilities
    ) {}

    public record RoleCapabilityGrantsResponse(
        Integer roleId,
        Set<String> capabilities
    ) {}
}
