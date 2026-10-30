package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.GuildManagementAccessService.AuthorizedGuild;
import com.Brafurries.API.user.dto.GuildManagementDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

class PortariaClarificationConfigurationServiceTest {
    private GuildManagementAccessService guildAccess;
    private CommunityDiscordRepository links;
    private PortariaClarificationConfigurationService service;
    private JdbcTemplate jdbc;

    private final UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("staff@local.test", "ignored");

    @BeforeEach
    void setup() {
        guildAccess = mock(GuildManagementAccessService.class);
        links = mock(CommunityDiscordRepository.class);
        jdbc = mock(JdbcTemplate.class);
        service = new PortariaClarificationConfigurationService(
            guildAccess, links, mock(CommunityAuditLogRepository.class),
            mock(UserRepository.class), jdbc, new ObjectMapper());
    }

    private void authorized(boolean canManageChannels, List<GuildCategory> categories) {
        var resources = new GuildResources("123", List.of(), List.of(), categories,
            new BotCapabilities(canManageChannels, false, true, List.of()));
        when(guildAccess.authorize(auth, "123"))
            .thenReturn(new AuthorizedGuild(321L, resources));
        var link = new CommunityDiscord();
        link.setActive(true);
        when(links.findByGuildId(123L)).thenReturn(Optional.of(link));
    }

    @Test
    void enablingRequiresCategoryEvenForAuthorizedOwner() {
        authorized(true, List.of(new GuildCategory("888", "Atendimento")));
        var ex = assertThrows(ResponseStatusException.class,
            () -> service.update(auth, "123",
                new PortariaClarificationConfigurationService.UpdateClarificationConfig(true, null)));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void enablingRejectsOtherGuildCategory() {
        authorized(true, List.of(new GuildCategory("888", "Atendimento")));
        var ex = assertThrows(ResponseStatusException.class,
            () -> service.update(auth, "123",
                new PortariaClarificationConfigurationService.UpdateClarificationConfig(true, "999")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void enablingFailsWhenBotCannotCreatePrivateChannels() {
        authorized(false, List.of(new GuildCategory("888", "Atendimento")));
        var ex = assertThrows(ResponseStatusException.class,
            () -> service.update(auth, "123",
                new PortariaClarificationConfigurationService.UpdateClarificationConfig(true, "888")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void failsClosedOnUnlinkedGuild() {
        var resources = new GuildResources("123", List.of(), List.of(), List.of(),
            new BotCapabilities(true, false, true, List.of()));
        when(guildAccess.authorize(auth, "123"))
            .thenReturn(new AuthorizedGuild(321L, resources));
        var ex = assertThrows(ResponseStatusException.class, () -> service.get(auth, "123"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void failsClosedWhenWebGuildAuthorizationDenied() {
        when(guildAccess.authorize(auth, "123"))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class, () -> service.get(auth, "123"));
        verifyNoInteractions(links, jdbc);
    }
}
