package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.Brafurries.API.entity.community.*;
import com.Brafurries.API.repository.community.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class AdminRecordBlacklistServiceTest {
    private static final long GUILD_ID = 123456789012345678L;
    private final CommunityDiscordRepository guilds = mock(CommunityDiscordRepository.class);
    private final BlacklistedGameRepository games = mock(BlacklistedGameRepository.class);
    private final Authentication auth = mock(Authentication.class);
    private final AdminRecordBlacklistService service = new AdminRecordBlacklistService(guilds, games);

    private CommunityDiscord ownedGuild() {
        Community tenant = mock(Community.class);
        when(tenant.getId()).thenReturn(7);
        when(tenant.getName()).thenReturn("Comunidade");
        CommunityDiscord guild = mock(CommunityDiscord.class);
        when(guild.getCommunity()).thenReturn(tenant);
        when(guild.getGuildId()).thenReturn(GUILD_ID);
        when(guild.getName()).thenReturn("Servidor");
        return guild;
    }

    @BeforeEach void setup() {
        when(auth.getName()).thenReturn("admin@example.org");
    }

    @Test void returnsOnlyGuildsFromActiveCommunityDirectory() {
        var guild = ownedGuild();
        when(guilds.findAllByActiveTrueOrderByGuildIdAsc()).thenReturn(List.of(guild));
        var rows = service.guildOptions();
        assertEquals(1, rows.size());
        assertEquals(7, rows.getFirst().communityId());
        assertEquals(Long.toString(GUILD_ID), rows.getFirst().guildId());
    }

    @Test void rejectsCrossTenantWriteBeforeAnyDatabaseMutation() {
        var error = assertThrows(ResponseStatusException.class,
            () -> service.add(auth, 22, Long.toString(GUILD_ID), new AdminRecordBlacklistService.CreateRequest("Jogo")));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(games);
    }

    @Test void rejectsInvalidNameAndDuplicateWithoutMutation() {
        var bad = assertThrows(ResponseStatusException.class,
            () -> service.add(auth, 7, Long.toString(GUILD_ID), new AdminRecordBlacklistService.CreateRequest("   ")));
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatusCode());
        var guild = ownedGuild();
        when(guilds.lockActiveGuildInCommunity(GUILD_ID, 7)).thenReturn(List.of(guild));
        var old = mock(BlacklistedGame.class);
        when(old.getGameName()).thenReturn("Rocket League");
        when(games.findAllByCommunityDiscordGuildIdOrderByGameNameAsc(GUILD_ID)).thenReturn(List.of(old));
        var duplicate = assertThrows(ResponseStatusException.class,
            () -> service.add(auth, 7, Long.toString(GUILD_ID), new AdminRecordBlacklistService.CreateRequest("rocket league")));
        assertEquals(HttpStatus.CONFLICT, duplicate.getStatusCode());
        verify(games, never()).saveAndFlush(any());
    }

    @Test void rejectsDeletionOfOtherTenantGame() {
        var guild = ownedGuild();
        when(guilds.lockActiveGuildInCommunity(GUILD_ID, 7)).thenReturn(List.of(guild));
        var other = mock(BlacklistedGame.class);
        var otherGuild = mock(CommunityDiscord.class);
        when(otherGuild.getGuildId()).thenReturn(987654321012345678L);
        when(other.getCommunityDiscord()).thenReturn(otherGuild);
        when(games.findById(4)).thenReturn(Optional.of(other));
        var error = assertThrows(ResponseStatusException.class,
            () -> service.remove(auth, 7, Long.toString(GUILD_ID), 4));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(games, never()).delete(any());
    }

    @Test void addsAndDeletesOnlyWithinExactCommunityAndGuild() {
        var guild = ownedGuild();
        when(guilds.lockActiveGuildInCommunity(GUILD_ID, 7)).thenReturn(List.of(guild));
        when(games.findAllByCommunityDiscordGuildIdOrderByGameNameAsc(GUILD_ID)).thenReturn(List.of());
        service.add(auth, 7, Long.toString(GUILD_ID), new AdminRecordBlacklistService.CreateRequest(" Jogo novo "));
        verify(games).saveAndFlush(argThat(g -> g.getCommunityDiscord() == guild
            && "Jogo novo".equals(g.getGameName())));
        var entry = new BlacklistedGame();
        entry.setId(4);
        entry.setCommunityDiscord(guild);
        entry.setGameName("Jogo novo");
        when(games.findById(4)).thenReturn(Optional.of(entry));
        service.remove(auth, 7, Long.toString(GUILD_ID), 4);
        verify(games).delete(entry);
    }

    @Test void blocksAmbiguousGuildCardinality() {
        var guild = ownedGuild();
        when(guilds.lockActiveGuildInCommunity(GUILD_ID, 7)).thenReturn(List.of(guild, guild));
        var err = assertThrows(ResponseStatusException.class,
            () -> service.add(auth, 7, Long.toString(GUILD_ID), new AdminRecordBlacklistService.CreateRequest("jogo")));
        assertEquals(HttpStatus.CONFLICT, err.getStatusCode());
        verifyNoInteractions(games);
    }
}
