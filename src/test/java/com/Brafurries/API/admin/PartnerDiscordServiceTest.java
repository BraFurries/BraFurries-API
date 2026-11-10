package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.misc.Partner;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.misc.PartnerRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PartnerDiscordServiceTest {
    @Mock PlatformAuthorizationService authorization;
    @Mock CommunityDiscordRepository guilds;
    @Mock PartnerRepository partners;
    @Mock UserDiscordRepository identities;
    @Mock JdbcTemplate jdbc;
    @Mock PartnerDiscordClient coddy;
    @Mock Authentication auth;

    PartnerDiscordService service;

    @BeforeEach
    void setUp() {
        service = new PartnerDiscordService(authorization, guilds, partners, identities, jdbc, coddy);
    }

    private void officialGuild(int linkedCommunityId) {
        Community community = new Community();
        community.setId(linkedCommunityId);
        CommunityDiscord server = new CommunityDiscord();
        server.setCommunity(community);
        server.setGuildId(123456789012345678L);
        server.setActive(true);
        when(authorization.officialCommunityId()).thenReturn(1);
        when(guilds.findByCommunityIds(List.of(1))).thenReturn(List.of(server));
    }

    private void configured() {
        when(jdbc.queryForList(
            startsWith("SELECT * FROM partner_discord_config"), eq(1)
        )).thenReturn(List.of(Map.of("guild_id", 123456789012345678L)));
    }

    @Test
    void rejectsOtherCommunityEvenIfItsGuildIsActive() {
        officialGuild(2);
        ResponseStatusException error = assertThrows(
            ResponseStatusException.class, () -> service.settings()
        );
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verifyNoInteractions(jdbc, coddy);
    }

    @Test
    void rejectsInactiveNeverPublishedPartnerWithoutCreatingAnything() {
        officialGuild(1);
        configured();
        User actor = new User();
        actor.setId(33);
        when(authorization.currentUser(auth)).thenReturn(actor);
        Partner partner = new Partner();
        partner.setId(17);
        partner.setName("Parceiro em análise");
        partner.setStatus("pending");
        when(partners.findWithDetailsById(17)).thenReturn(Optional.of(partner));
        when(jdbc.queryForList(
            startsWith("SELECT * FROM partner_discord_publications"), eq(1), eq(17)
        )).thenReturn(List.of());

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class, () -> service.sync(auth, 17)
        );
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verifyNoInteractions(coddy);
    }

    @Test
    void cannotPublishForAnotherRepresentativesAccount() {
        officialGuild(1);
        configured();
        User actor = new User();
        actor.setId(33);
        when(authorization.currentUser(auth)).thenReturn(actor);
        User representative = new User();
        representative.setId(44);
        Partner partner = new Partner();
        partner.setId(17);
        partner.setName("Ativo");
        partner.setStatus("active");
        partner.setRepresentativeUser(representative);
        when(partners.findWithDetailsById(17)).thenReturn(Optional.of(partner));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.publish(auth, 17, new PartnerDiscordService.PostRequest("Novidades"))
        );
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(coddy);
    }

    @Test
    void withdrawalRevokesDiscordBeforeDatabaseReleasesPartnerRecord() {
        officialGuild(1);
        configured();
        User actor = new User();
        actor.setId(33);
        when(authorization.currentUser(auth)).thenReturn(actor);
        Partner partner = new Partner();
        partner.setId(17);
        partner.setName("Antigo parceiro");
        partner.setStatus("suspended");
        when(partners.findWithDetailsById(17)).thenReturn(Optional.of(partner));
        when(jdbc.queryForList(
            startsWith("SELECT * FROM partner_discord_publications"), eq(1), eq(17)
        )).thenReturn(List.of(Map.of(
            "desired_revision", 3L,
            "thread_id", 700L,
            "exclusive_channel_enabled", 0,
            "text_channel_created_by_bot", 0
        )));
        when(jdbc.update(
            startsWith("DELETE FROM partner_discord_publications"), eq(17), eq(1), eq(3L)
        )).thenReturn(1);

        service.unpublish(auth, 17);

        InOrder operations = inOrder(coddy, jdbc);
        operations.verify(coddy).sync(eq("123456789012345678"), anyMap());
        operations.verify(jdbc).update(
            startsWith("DELETE FROM partner_discord_publications"), eq(17), eq(1), eq(3L)
        );
    }

    @Test
    void withdrawingUnpublishedPartnerDoesNotCallDiscord() {
        officialGuild(1);
        configured();
        User actor = new User();
        actor.setId(33);
        when(authorization.currentUser(auth)).thenReturn(actor);
        when(jdbc.queryForList(
            startsWith("SELECT * FROM partner_discord_publications"), eq(1), eq(17)
        )).thenReturn(List.of());

        service.unpublish(auth, 17);
        verifyNoInteractions(coddy, partners);
    }
}
