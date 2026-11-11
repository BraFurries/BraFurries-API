package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityNetworkMemberStatusRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminCoddyReachServiceTest {
    @Mock CommunityDiscordRepository guilds;
    @Mock CommunityNetworkMemberStatusRepository memberships;

    @Test
    void returnsDistinctPersistedUsersAlongsideReconciliationCoverage() {
        when(memberships.countDistinctPresentUsersInHealthyActiveDiscordGuilds()).thenReturn(61L);
        when(guilds.countByActiveTrueAndMembershipSyncStateAndLastFullReconciliationAtIsNotNull("HEALTHY"))
            .thenReturn(3L);

        var reach = new AdminCoddyReachService(memberships, guilds).read();

        assertEquals(61L, reach.uniqueMembers());
        assertEquals(3L, reach.reconciledServers());
    }

    @Test
    void reportsNoCoverageWithoutFallingBackToRawGuildSum() {
        var reach = new AdminCoddyReachService(memberships, guilds).read();
        assertEquals(0L, reach.uniqueMembers());
        assertEquals(0L, reach.reconciledServers());
    }
}
