package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
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
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

class RoleToolServiceTest {
    private static final String GUILD = "123456789012345678";
    private final GuildManagementAccessService access = mock(GuildManagementAccessService.class);
    private final CommunityDiscordRepository links = mock(CommunityDiscordRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CoddyGuildManagementClient coddy = mock(CoddyGuildManagementClient.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authentication actor = mock(Authentication.class);
    private final RoleToolService service = new RoleToolService(
        access, links, users, coddy, jdbc,
        mock(PlatformTransactionManager.class), new ObjectMapper()
    );

    @BeforeEach void setup() {
        when(actor.getName()).thenReturn("ADMIN@Example.org");
        GuildResources resources = new GuildResources(GUILD,
            List.of(new GuildRole("900000000000000001", "Membro", 2, false, true),
                    new GuildRole("900000000000000002", "Staff", 12, false, false)),
            List.of(new GuildChannel("800000000000000001", "logs", "text", null)),
            List.of(),
            new BotCapabilities(true, true, true, List.of()));
        when(access.authorize(actor, GUILD)).thenReturn(new AuthorizedGuild(77L, resources));
        Community community = mock(Community.class);
        when(community.getId()).thenReturn(15);
        CommunityDiscord link = mock(CommunityDiscord.class);
        when(link.getActive()).thenReturn(true);
        when(link.getCommunity()).thenReturn(community);
        when(links.findByGuildId(Long.parseLong(GUILD))).thenReturn(Optional.of(link));
        User user = mock(User.class);
        when(user.getId()).thenReturn(5);
        when(users.findByEmail("admin@example.org")).thenReturn(Optional.of(user));
    }

    @Test void refusesForeignOrUnassignableRoleBeforeAnyWrite() {
        var fingerprint = "a".repeat(64);
        ResponseStatusException foreign = assertThrows(ResponseStatusException.class,
            () -> service.bulk(actor, GUILD,
                new RoleToolService.BulkRequest("900000000000000999", fingerprint), "test-foreign"));
        assertEquals(HttpStatus.BAD_REQUEST, foreign.getStatusCode());
        ResponseStatusException protectedRole = assertThrows(ResponseStatusException.class,
            () -> service.bulk(actor, GUILD,
                new RoleToolService.BulkRequest("900000000000000002", fingerprint), "test-staff"));
        assertEquals(HttpStatus.FORBIDDEN, protectedRole.getStatusCode());
        verifyNoInteractions(jdbc, coddy);
    }

    @Test void refusesCrossGuildRecoveryLogChannelAndUnsupportedBot() {
        ResponseStatusException foreign = assertThrows(ResponseStatusException.class,
            () -> service.scan(actor, GUILD,
                new RoleToolService.ScanRequest("800000000000000999", "Dyno"), "test-foreign"));
        assertEquals(HttpStatus.BAD_REQUEST, foreign.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                () -> service.scan(actor, GUILD,
                    new RoleToolService.ScanRequest("800000000000000001", "Bogus"),
                    "test-source")).getStatusCode());
        verifyNoInteractions(jdbc, coddy);
    }

    @Test void refusesUnlinkedOrInactiveCommunityEvenForAuthorizedDiscordManager() {
        when(links.findByGuildId(Long.parseLong(GUILD))).thenReturn(Optional.empty());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.list(actor, GUILD));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verifyNoInteractions(jdbc, coddy);
    }

    @Test void refusesMissingIdempotencyKeyBeforeWriting() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                () -> service.scan(actor, GUILD,
                    new RoleToolService.ScanRequest("800000000000000001", "Dyno"),
                    "")).getStatusCode());
        verifyNoInteractions(jdbc, coddy);
    }
    @Test void nonAdminGuildManagerCannotListSensitiveRecoveryHistory() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
            "Administrator required")).when(coddy)
            .requireRoleToolAdministrator(GUILD, 77L);
        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
            () -> service.list(actor, GUILD));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void nonAdminGuildManagerCannotReadRecoveryOperationById() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,
            "Administrator required")).when(coddy)
            .requireRoleToolAdministrator(GUILD, 77L);
        ResponseStatusException denied = assertThrows(ResponseStatusException.class,
            () -> service.get(actor, GUILD, 88L));
        assertEquals(HttpStatus.FORBIDDEN, denied.getStatusCode());
        verifyNoInteractions(jdbc);
    }

}
