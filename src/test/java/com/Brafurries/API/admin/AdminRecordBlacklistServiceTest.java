package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.Brafurries.API.entity.platform.GlobalRecordBlacklistGame;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.platform.GlobalRecordBlacklistGameRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class AdminRecordBlacklistServiceTest {
    private final GlobalRecordBlacklistGameRepository games = mock(GlobalRecordBlacklistGameRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authentication auth = mock(Authentication.class);
    private final AdminRecordBlacklistService service = new AdminRecordBlacklistService(games, users, jdbc);

    private GlobalRecordBlacklistGame game(int id, String name) {
        var item = new GlobalRecordBlacklistGame();
        item.setId(id);
        item.setGameName(name);
        return item;
    }

    @BeforeEach void setup() {
        when(auth.getName()).thenReturn("ADMIN@Example.org");
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(7);
        when(users.findByEmail("admin@example.org")).thenReturn(Optional.of(actor));
    }

    @Test void returnsSingleListWithoutGuildOrCommunitySelection() {
        when(games.findAllByOrderByGameNameAsc()).thenReturn(List.of(game(1, "Jogo A"), game(2, "Jogo B")));
        var rows = service.list().games();
        assertEquals(2, rows.size());
        assertEquals("Jogo B", rows.get(1).name());
        verify(games).findAllByOrderByGameNameAsc();
    }

    @Test void rejectsInvalidAndDuplicateNamesWithoutMutation() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.add(auth, new AdminRecordBlacklistService.CreateRequest("   "))).getStatusCode());
        when(games.existsByGameNameIgnoreCase("Rocket League")).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> service.add(auth, new AdminRecordBlacklistService.CreateRequest("Rocket League"))).getStatusCode());
        verify(games, never()).saveAndFlush(any());
        verifyNoInteractions(jdbc);
    }

    @Test void addsGlobalEntryWithActorAuditInSameTransaction() {
        service.add(auth, new AdminRecordBlacklistService.CreateRequest(" Novo jogo "));
        verify(games).saveAndFlush(argThat(g -> "Novo jogo".equals(g.getGameName())));
        verify(jdbc).update(contains("record_global_blacklist_audit"), any(), eq("Novo jogo"), eq(7));
    }

    @Test void deletesGlobalEntryWithActorAudit() {
        var item = game(9, "Jogo");
        when(games.findById(9)).thenReturn(Optional.of(item));
        service.remove(auth, 9);
        verify(games).delete(item);
        verify(games).flush();
        verify(jdbc).update(contains("record_global_blacklist_audit"), eq(9), eq("Jogo"), eq(7));
    }

    @Test void refusesUnknownEntryWithoutAudit() {
        var response = assertThrows(ResponseStatusException.class, () -> service.remove(auth, 100));
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void deniesActorWithoutAccount() {
        when(users.findByEmail("admin@example.org")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
            () -> service.add(auth, new AdminRecordBlacklistService.CreateRequest("Jogo"))).getStatusCode());
        verifyNoInteractions(games);
    }
}
