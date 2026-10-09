package com.Brafurries.API.admin.dto;

import java.util.List;
import java.util.Map;

public final class IdentityBanPropagationDtos {

    private IdentityBanPropagationDtos() {
    }

    public record IdentityCandidate(
        Integer userId,
        String discordUserId
    ) {
    }

    public record PropagationRequest(
        Integer banId,
        String reason,
        List<IdentityCandidate> identities
    ) {
    }

    public record PropagationResponse(
        Integer banId,
        Integer processed,
        Map<String, Integer> effects
    ) {
    }
}
