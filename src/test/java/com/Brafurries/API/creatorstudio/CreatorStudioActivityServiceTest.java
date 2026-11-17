package com.Brafurries.API.creatorstudio;

import static com.Brafurries.API.creatorstudio.CreatorStudioDetailsDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CreatorStudioActivityServiceTest {
    @Mock CreatorStudioAccessService access;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    CreatorStudioActivityService service;

    @BeforeEach void setup() {
        service = new CreatorStudioActivityService(access, jdbc);
    }

    @Test
    void deniesFreeAccountsEvenWhenViewingAnotherArtistsHistory() {
        when(access.requirePro(auth)).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.list(auth, 85L, 1, 20)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void foreignCommissionReturnsNotFoundBeforeAnyHistoryQuery() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.queryForObject(contains("creator_studio_commissions"),
            eq(Integer.class), eq(31), eq(85L))).thenReturn(0);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> service.list(auth, 85L, 1, 20)).getStatusCode());
        verify(jdbc, never()).query(contains("creator_studio_activity"),
            any(RowMapper.class), any(Object[].class));
    }

    @Test
    void historyIsOwnerScopedAndBoundedNewestFirst() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.queryForObject(contains("creator_studio_commissions"),
            eq(Integer.class), eq(31), eq(85L))).thenReturn(1);
        when(jdbc.queryForObject(contains("COUNT(*) FROM creator_studio_activity"),
            eq(Long.class), eq(31), eq(85L))).thenReturn(40L);
        ActivityPage page = service.list(auth, 85L, 2, 20);
        assertEquals(40L, page.total());
        assertTrue(page.items().isEmpty());
        verify(jdbc).query(argThat(sql -> sql.contains("owner_user_id=?")
                && sql.contains("ORDER BY occurred_on DESC,id DESC LIMIT ? OFFSET ?")),
            any(RowMapper.class), eq(31), eq(85L), eq(20), eq(20));
    }

    @Test
    void rejectsMissingDateOrDescription() {
        when(access.requirePro(auth)).thenReturn(31);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.create(auth, 85L,
                new ActivityDraft(ActivityKind.REVISION_REQUEST, "", LocalDate.now())))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.create(auth, 85L,
                new ActivityDraft(ActivityKind.NOTE, "Observação", null)))
            .getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void reportsApprovalOnlyAsAnArtistEnteredEventNeverClientVerified() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.queryForObject(contains("creator_studio_commissions"),
            eq(Integer.class), eq(31), eq(85L))).thenReturn(1);
        doAnswer(inv -> {
            KeyHolder holder = inv.getArgument(1);
            holder.getKeyList().add(Map.of("id", 901L));
            return 1;
        }).when(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
        var reported = new Activity(901L, 85L, ActivityKind.APPROVAL_REPORTED,
            "O artista informou que o cliente aprovou", LocalDate.of(2026, 11, 17), null);
        when(jdbc.query(contains("creator_studio_activity"), any(RowMapper.class),
            eq(31), eq(85L), eq(901L))).thenReturn(List.of(reported));

        Activity created = service.create(auth, 85L,
            new ActivityDraft(ActivityKind.APPROVAL_REPORTED, "O artista informou que o cliente aprovou",
                LocalDate.of(2026,11,17)));
        assertEquals(ActivityKind.APPROVAL_REPORTED, created.eventKind());
        verify(jdbc).query(argThat(sql ->
            sql.contains("owner_user_id=? AND commission_id=? AND id=?")),
            any(RowMapper.class), eq(31), eq(85L), eq(901L));
    }

    @Test
    void cannotFetchUnboundedActivityPage() {
        when(access.requirePro(auth)).thenReturn(31);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.list(auth, 85L, 1, 51)).getStatusCode());
        verifyNoInteractions(jdbc);
    }
}
