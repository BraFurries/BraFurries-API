package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.Brafurries.API.admin.dto.AdminDtos.AdminBotStatus;
import com.Brafurries.API.repository.api.ApiRolePermissionRepository;
import com.Brafurries.API.repository.api.ApiUserRoleRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository.GeneralDiscordMetricsProjection;
import com.Brafurries.API.repository.misc.ConfigCommandUseRepository;
import com.Brafurries.API.repository.misc.PartnerRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTelegramRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AdminDashboardServiceTest {
    @Mock UserRepository users;
    @Mock UserDiscordRepository discordUsers;
    @Mock UserTelegramRepository telegramUsers;
    @Mock PartnerRepository partners;
    @Mock CommunityDiscordRepository guilds;
    @Mock ConfigCommandUseRepository commandUses;
    @Mock ApiUserRoleRepository userRoles;
    @Mock ApiRolePermissionRepository rolePermissions;
    @Mock AdminBotStatusService botStatus;
    @Mock GeneralDiscordMetricsProjection guildMetrics;

    @Test
    void overviewMapsPendingPartnersAndRealSevenDayGuildTransitions() {
        when(guilds.fetchGeneralDiscordMetrics()).thenReturn(guildMetrics);
        when(guildMetrics.getActiveDiscordCommunities()).thenReturn(8L);
        when(guildMetrics.getTotalMembersReachedActiveCommunities()).thenReturn(2580L);
        when(guilds.countByLastBotJoinedAtGreaterThanEqual(any(LocalDateTime.class))).thenReturn(3L);
        when(guilds.countByLastBotLeftAtGreaterThanEqual(any(LocalDateTime.class))).thenReturn(1L);
        when(users.count()).thenReturn(120L);
        when(partners.countByStatusIgnoreCase("active")).thenReturn(5L);
        when(partners.countByStatusIgnoreCase("pending")).thenReturn(4L);
        when(botStatus.getStatus()).thenReturn(new AdminBotStatus(
            "online", true, true, 100L, null, null, null, null, null,
            21.0, 8L, 2580L, null, null, null, null, null
        ));

        var service = new AdminDashboardService(
            users, discordUsers, telegramUsers, partners, guilds, commandUses,
            userRoles, rolePermissions, botStatus
        );
        LocalDateTime earliestExpected = LocalDateTime.now(ZoneOffset.UTC).minusDays(7).minusMinutes(1);

        var overview = service.getOverview();

        assertEquals(120L, overview.metrics().totalUsers());
        assertEquals(0L, overview.metrics().newUsersWeek());
        assertEquals(5L, overview.metrics().activePartners());
        assertEquals(4L, overview.metrics().pendingPartners());
        assertEquals(8L, overview.metrics().botServers());
        assertEquals(2580L, overview.metrics().botReachedUsers());
        assertEquals(3L, overview.metrics().serverGrowthWeek());
        assertEquals(1L, overview.metrics().serverLeavesWeek());
        ArgumentCaptor<LocalDateTime> joinsCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> exitsCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(guilds).countByLastBotJoinedAtGreaterThanEqual(joinsCutoff.capture());
        verify(guilds).countByLastBotLeftAtGreaterThanEqual(exitsCutoff.capture());
        assertEquals(joinsCutoff.getValue(), exitsCutoff.getValue());
        assertTrue(!joinsCutoff.getValue().isBefore(earliestExpected));
        assertTrue(!joinsCutoff.getValue().isAfter(LocalDateTime.now(ZoneOffset.UTC)));
    }
}
