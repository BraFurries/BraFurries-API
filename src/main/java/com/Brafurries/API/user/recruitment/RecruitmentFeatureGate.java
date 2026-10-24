package com.Brafurries.API.user.recruitment;

import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import java.util.Objects;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fail-closed Community rollout. The eligible Community is resolved from the
 * active Discord network's canonical guild ID, not from a mutable name or
 * an assumed Community primary key. Authorization is always separate.
 *
 * RECRUITMENT_ENABLED=false remains an emergency kill switch.
 */
@Component
public class RecruitmentFeatureGate {
    static final long BRAFURRIES_GUILD_ID = 753321055682035712L;

    private final boolean enabled;
    private final int allowedCommunityId;
    private final Predicate<Integer> isOfficialBraFurriesCommunity;

    @Autowired
    public RecruitmentFeatureGate(
        CommunityDiscordRepository discordCommunities,
        @Value("${app.recruitment.enabled:false}") boolean enabled,
        @Value("${app.recruitment.community-id:0}") int allowedCommunityId
    ) {
        this(enabled, allowedCommunityId, id ->
            discordCommunities.existsByGuildIdAndCommunityIdAndActiveTrue(BRAFURRIES_GUILD_ID, id));
    }

    /**
     * Legacy service-test constructor; production always uses the
     * repository-backed constructor to verify the active Discord guild.
     */
    RecruitmentFeatureGate(boolean enabled, int allowedCommunityId) {
        this(enabled, allowedCommunityId, id -> Objects.equals(id, allowedCommunityId));
    }

    RecruitmentFeatureGate(
        boolean enabled,
        int allowedCommunityId,
        Predicate<Integer> isOfficialBraFurriesCommunity
    ) {
        this.enabled = enabled;
        this.allowedCommunityId = allowedCommunityId;
        this.isOfficialBraFurriesCommunity = Objects.requireNonNull(isOfficialBraFurriesCommunity);
    }

    public void requireEnabledFor(Integer communityId) {
        if (!enabled || communityId == null || communityId <= 0
            || (allowedCommunityId > 0 && !Objects.equals(communityId, allowedCommunityId))
            || !isOfficialBraFurriesCommunity.test(communityId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurso indisponível");
        }
    }
}
