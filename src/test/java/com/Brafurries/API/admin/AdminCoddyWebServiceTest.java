package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.Brafurries.API.admin.dto.AdminCoddyWebDtos.*;
import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class AdminCoddyWebServiceTest {
    private static final String KEY = "00000000-0000-4000-8000-000000000001";
    private static final long GUILD = 123456789012345678L;
    private static final long CHANNEL = 777777777777777777L;
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CommunityDiscordRepository guilds = mock(CommunityDiscordRepository.class);
    private final AdminCoddyWebClient coddy = mock(AdminCoddyWebClient.class);
    private final Authentication auth = mock(Authentication.class);
    private AdminCoddyWebService service;

    @BeforeEach
    void setup() {
        service = new AdminCoddyWebService(jdbc, users, guilds, coddy);
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(7);
        when(auth.getName()).thenReturn("admin@brafurries.test");
        when(users.findByEmail("admin@brafurries.test")).thenReturn(Optional.of(actor));
        when(jdbc.queryForList(anyString(), eq(KEY))).thenReturn(List.of());
    }

    private SendRequest request(int communityId, String message) {
        return new SendRequest(communityId, Long.toString(GUILD),
            Long.toString(CHANNEL), message, KEY);
    }

    private CommunityDiscord guild(int communityId, boolean active) {
        Community community = mock(Community.class);
        when(community.getId()).thenReturn(communityId);
        when(community.getName()).thenReturn("Comunidade " + communityId);
        CommunityDiscord link = mock(CommunityDiscord.class);
        when(link.getGuildId()).thenReturn(GUILD);
        when(link.getCommunity()).thenReturn(community);
        when(link.getActive()).thenReturn(active);
        return link;
    }

    @Test
    void rejectsCrossTenantGuildBeforeAuditingOrSending() {
        when(guilds.findByGuildId(GUILD)).thenReturn(Optional.of(guild(21, true)));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.send(auth, request(22, "Olá")));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(coddy, never()).send(any(long.class), any(long.class), anyString());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsInactiveGuildBeforeAnyExternalEffect() {
        when(guilds.findByGuildId(GUILD)).thenReturn(Optional.of(guild(12, false)));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.send(auth, request(12, "Olá")));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(coddy);
    }

    @Test
    void filtersAvailableTargetsToActiveCommunityAssociations() {
        when(guilds.findAllByActiveTrueOrderByGuildIdAsc()).thenReturn(List.of(guild(12, true)));
        when(coddy.messageTargets()).thenReturn(new AdminCoddyWebClient.RuntimeTargets(List.of(
            new AdminCoddyWebClient.RuntimeGuild(Long.toString(GUILD), "Servidor",
                List.of(new ChannelTarget(Long.toString(CHANNEL), "avisos", "text"))),
            new AdminCoddyWebClient.RuntimeGuild("999999999999999999", "Outro",
                List.of(new ChannelTarget("777", "outro", "text")))
        )));
        Targets available = service.targets();
        assertEquals(1, available.guilds().size());
        assertEquals(12, available.guilds().getFirst().communityId());
        assertEquals(Long.toString(CHANNEL), available.guilds().getFirst().channels().getFirst().channelId());
    }

    @Test
    void doesNotReplayAnUncertainRequestKey() {
        when(jdbc.queryForList(anyString(), eq(KEY))).thenReturn(List.of(Map.of(
            "actor_user_id", 7, "community_id", 12, "guild_id", GUILD,
            "channel_id", CHANNEL, "payload_sha256", "unused",
            "outcome", "UNKNOWN"
        )));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.send(auth, request(12, "Olá")));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verifyNoInteractions(coddy);
    }

    @Test
    void successfulSendIsOneShotAndRecordsReturnedDiscordMessage() {
        when(guilds.findByGuildId(GUILD)).thenReturn(Optional.of(guild(12, true)));
        when(guilds.findAllByActiveTrueOrderByGuildIdAsc()).thenReturn(List.of(guild(12, true)));
        when(coddy.messageTargets()).thenReturn(new AdminCoddyWebClient.RuntimeTargets(List.of(
            new AdminCoddyWebClient.RuntimeGuild(Long.toString(GUILD), "Servidor",
                List.of(new ChannelTarget(Long.toString(CHANNEL), "avisos", "text")))
        )));
        when(coddy.send(GUILD, CHANNEL, "Olá")).thenReturn(
            new AdminCoddyWebClient.Delivery(Long.toString(GUILD), Long.toString(CHANNEL), "900000000000000001")
        );

        SendResult result = service.send(auth, request(12, "Olá"));

        assertEquals("sent", result.outcome());
        assertEquals("900000000000000001", result.messageId());
        verify(coddy, times(1)).send(GUILD, CHANNEL, "Olá");
        verify(jdbc, times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void presenceUpdatePersistsDesiredStateAndReturnsRuntimeAppliedState() {
        Presence desired = new Presence("WATCHING", "Eventos", "online");
        when(coddy.updatePresence(desired)).thenReturn(desired);

        PresenceView result = service.updatePresence(auth,
            new PresenceUpdateRequest("WATCHING", "Eventos", "online"));

        assertEquals(desired, result.desired());
        assertEquals(desired, result.current());
        verify(coddy).updatePresence(desired);
        verify(jdbc, times(3)).update(anyString(), any(Object[].class));
    }
}
