package com.Brafurries.API.user.dto;

import java.util.List;

public final class CommunityCoreDtos {
    private CommunityCoreDtos() {}

    public record CommunityNetworkRef(
        String type,
        String externalId,
        String name,
        Boolean active
    ) {}

    public record CommunityResponse(
        Integer communityId,
        String name,
        boolean ownedByMe,
        boolean ownerAssigned,
        boolean member,
        List<CommunityNetworkRef> networks
    ) {}
}
