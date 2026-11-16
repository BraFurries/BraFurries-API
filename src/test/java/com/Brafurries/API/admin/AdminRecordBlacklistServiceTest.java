package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.user.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

class AdminRecordBlacklistServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final Authentication auth = mock(Authentication.class);
    private final AdminRecordBlacklistService service = new AdminRecordBlacklistService(users, jdbc);

    @BeforeEach void setup() {
        when(auth.getName()).thenReturn("ADMIN@Example.org");
        User actor = mock(User.class);
        when(actor.getId()).thenReturn(7);
        when(users.findByEmail("admin@example.org")).thenReturn(Optional.of(actor));
    }

    @Test void globalQueryNeverReceivesCommunityOrGuildId() {
        service.list();
        verify(jdbc).query(eq("SELECT id, game_name FROM record_global_blacklisted_games ORDER BY game_name, id"),
            any(org.springframework.jdbc.core.RowMapper.class));
        verifyNoInteractions(users);
    }

    @Test void rejectsInvalidNameBeforeWrite() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.add(auth, new AdminRecordBlacklistService.CreateRequest("   "))).getStatusCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void duplicateReturnsConflictWithoutAudit() {
        doThrow(new DuplicateKeyException("duplicate"))
            .when(jdbc).update(eq("INSERT INTO record_global_blacklisted_games (game_name) VALUES (?)"), eq("Jogo"));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> service.add(auth, new AdminRecordBlacklistService.CreateRequest("Jogo"))).getStatusCode());
        verify(jdbc, never()).update(contains("record_global_blacklist_audit"), any(Object[].class));
    }

    @Test void addAuditsWithAdministratorIdentity() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), eq("Jogo novo"))).thenReturn(12);
        service.add(auth, new AdminRecordBlacklistService.CreateRequest(" Jogo novo "));
        verify(jdbc).update("INSERT INTO record_global_blacklisted_games (game_name) VALUES (?)", "Jogo novo");
        verify(jdbc).update(contains("record_global_blacklist_audit"), eq(12), eq("Jogo novo"), eq(7));
    }

    @Test void removeAuditsDeletedNameAndId() {
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(5)))
            .thenReturn(List.of(Map.of("id", 5, "game_name", "Jogo")));
        when(jdbc.update("DELETE FROM record_global_blacklisted_games WHERE id = ?", 5)).thenReturn(1);
        service.remove(auth, 5);
        verify(jdbc).update("DELETE FROM record_global_blacklisted_games WHERE id = ?", 5);
        verify(jdbc).update(contains("record_global_blacklist_audit"), eq(5), eq("Jogo"), eq(7));
    }

    @Test void missingEntryDoesNotCreateAudit() {
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(55))).thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> service.remove(auth, 55)).getStatusCode());
        verify(jdbc, never()).update(contains("record_global_blacklist_audit"), any(Object[].class));
    }

    @Test void refusesUnknownActor() {
        when(users.findByEmail("admin@example.org")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
            () -> service.add(auth, new AdminRecordBlacklistService.CreateRequest("Jogo"))).getStatusCode());
        verifyNoInteractions(jdbc);
    }
}
