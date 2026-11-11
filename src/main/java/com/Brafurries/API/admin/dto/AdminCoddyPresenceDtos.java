package com.Brafurries.API.admin.dto;

import com.Brafurries.API.admin.dto.AdminDtos.AdminPagination;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** Read-only, global ADMIN view of the currently known Coddy Discord guilds. */
public final class AdminCoddyPresenceDtos {
    private AdminCoddyPresenceDtos() {}

    public record Summary(
        long knownServers,
        long activeServers,
        long reconciledServers,
        long inactiveServers,
        long estimatedReach,
        long entries7d,
        long exits7d,
        long entries30d,
        long exits30d
    ) {}

    public record Server(
        Integer communityId,
        String communityName,
        String guildId,
        String name,
        boolean active,
        int observedMembers,
        String membershipSyncState,
        Instant lastBotJoinedAt,
        Instant lastBotLeftAt,
        // Legacy reconciliation/event timestamps have no declared timezone;
        // leave them as local database values rather than invent a UTC instant.
        LocalDateTime lastFullReconciliationAt,
        LocalDateTime lastMembershipEventAt
    ) {}

    public record Response(
        Summary summary,
        List<Server> items,
        AdminPagination pagination
    ) {}
}
