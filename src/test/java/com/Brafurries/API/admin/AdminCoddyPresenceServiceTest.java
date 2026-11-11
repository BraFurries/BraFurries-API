package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository.GeneralDiscordMetricsProjection;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AdminCoddyPresenceServiceTest {
    @Mock CommunityDiscordRepository guilds;
    @Mock GeneralDiscordMetricsProjection metrics;

    @Test
    void returnsGlobalSummaryAndPagedGuildDetailsWithoutTruncatingDiscordId() {
        Community community = new Community();
        community.setId(11);
        community.setName("Comunidade Demo");
        CommunityDiscord guild = new CommunityDiscord();
        guild.setCommunity(community);
        guild.setGuildId(123456789012345678L);
        guild.setName("Servidor Demo");
        guild.setActive(true);
        guild.setUsersQuantity(700);
        guild.setMembershipSyncState("HEALTHY");
        guild.setLastBotJoinedAt(LocalDateTime.of(2026, 11, 10, 14, 30));
        guild.setLastBotLeftAt(null);

        when(guilds.findAll(any(Specification.class), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(guild)));
        when(guilds.fetchGeneralDiscordMetrics()).thenReturn(metrics);
        when(metrics.getTotalDiscordCommunities()).thenReturn(4L);
        when(metrics.getActiveDiscordCommunities()).thenReturn(3L);
        when(metrics.getTotalMembersReachedActiveCommunities()).thenReturn(1900L);
        when(guilds.countByLastBotJoinedAtGreaterThanEqual(any(LocalDateTime.class)))
            .thenReturn(2L, 3L);
        when(guilds.countByLastBotLeftAtGreaterThanEqual(any(LocalDateTime.class)))
            .thenReturn(1L, 2L);

        var response = new AdminCoddyPresenceService(guilds)
            .list(1, 20, "123456789012345678", "active", 11);

        assertEquals(4L, response.summary().knownServers());
        assertEquals(3L, response.summary().activeServers());
        assertEquals(1L, response.summary().inactiveServers());
        assertEquals(1900L, response.summary().estimatedReach());
        assertEquals(2L, response.summary().entries7d());
        assertEquals(1L, response.summary().exits7d());
        assertEquals(3L, response.summary().entries30d());
        assertEquals(2L, response.summary().exits30d());
        assertEquals(1L, response.pagination().totalItems());
        assertEquals("123456789012345678", response.items().getFirst().guildId());
        assertEquals(11, response.items().getFirst().communityId());
        assertEquals("Comunidade Demo", response.items().getFirst().communityName());
        assertEquals("2026-11-10T14:30:00Z", response.items().getFirst().lastBotJoinedAt().toString());
        assertNull(response.items().getFirst().lastBotLeftAt());

        ArgumentCaptor<Pageable> pagination = ArgumentCaptor.forClass(Pageable.class);
        verify(guilds).findAll(any(Specification.class), pagination.capture());
        assertEquals(0, pagination.getValue().getPageNumber());
        assertEquals(20, pagination.getValue().getPageSize());
        assertEquals("active: DESC,name: ASC,id: ASC", pagination.getValue().getSort().toString());
    }

    @Test
    void rejectsInvalidFiltersWithoutReadingGuilds() {
        var service = new AdminCoddyPresenceService(guilds);
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> service.list(1, 20, "", "suspended", null)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> service.list(0, 20, "", "active", null)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> service.list(1, 101, "", "active", null)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> service.list(1, 20, "", "active", 0)).getStatusCode().value());
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> service.list(1, 20, "x".repeat(101), "all", null)).getStatusCode().value());
        verify(guilds, never()).findAll(any(Specification.class), any(Pageable.class));
    }
}
