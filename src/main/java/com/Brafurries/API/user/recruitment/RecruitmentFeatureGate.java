package com.Brafurries.API.user.recruitment;

import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fail-closed single-Community rollout. The Community identity is supplied by
 * deployment configuration, never inferred from a name or a frontend switch.
 * Authorization is checked separately by CommunityAuthorizationService.
 */
@Component
public class RecruitmentFeatureGate {
    private final boolean enabled;
    private final int allowedCommunityId;

    public RecruitmentFeatureGate(
        @Value("${app.recruitment.enabled:false}") boolean enabled,
        @Value("${app.recruitment.community-id:0}") int allowedCommunityId
    ) {
        this.enabled = enabled;
        this.allowedCommunityId = allowedCommunityId;
    }

    public void requireEnabledFor(Integer communityId) {
        if (!enabled || allowedCommunityId <= 0
            || !Objects.equals(communityId, allowedCommunityId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurso indisponível");
        }
    }
}
