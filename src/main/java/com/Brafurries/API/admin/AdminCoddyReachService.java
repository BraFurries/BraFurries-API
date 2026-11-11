package com.Brafurries.API.admin;

import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityNetworkMemberStatusRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single source of truth for administrative reach. One platform user is
 * counted once across active Discord guilds with completed healthy snapshots.
 *
 * This measures persisted identities (bots may be included), not unique humans
 * or a real-time Discord audience. Unreconciled guilds are excluded and their
 * coverage is made explicit to consumers.
 */
@Service
public class AdminCoddyReachService {
    private static final String HEALTHY = "HEALTHY";

    private final CommunityNetworkMemberStatusRepository memberships;
    private final CommunityDiscordRepository guilds;

    public AdminCoddyReachService(
        CommunityNetworkMemberStatusRepository memberships,
        CommunityDiscordRepository guilds
    ) {
        this.memberships = memberships;
        this.guilds = guilds;
    }

    @Transactional(readOnly = true)
    public ReachSnapshot read() {
        return new ReachSnapshot(
            memberships.countDistinctPresentUsersInHealthyActiveDiscordGuilds(),
            guilds.countByActiveTrueAndMembershipSyncStateAndLastFullReconciliationAtIsNotNull(HEALTHY)
        );
    }

    public record ReachSnapshot(long uniqueMembers, long reconciledServers) {}
}
