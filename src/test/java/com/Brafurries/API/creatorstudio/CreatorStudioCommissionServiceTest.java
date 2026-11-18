package com.Brafurries.API.creatorstudio;

import static com.Brafurries.API.creatorstudio.CreatorStudioCommissionDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CreatorStudioCommissionServiceTest {
    @Mock CreatorStudioAccessService access;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication authentication;
    CreatorStudioCommissionService commissions;

    @BeforeEach
    void init() {
        commissions = new CreatorStudioCommissionService(access, jdbc);
    }

    private static SaveCommission request() {
        return new SaveCommission("Sketch", "Cliente", "Detalhes",
            "Notas privadas", LocalDate.of(2026, 11, 12), null, null);
    }

    @Test
    void blocksFreeUsersBeforeReadingAnyCommissionData() {
        when(access.requirePro(authentication)).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN, "Plano Pro necessário"));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> commissions.find(authentication, 100)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> commissions.create(authentication, request())).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> commissions.list(authentication, null, 1, 25)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void missingOrForeignCommissionLooksLikeNotFound() {
        when(access.requirePro(authentication)).thenReturn(55);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> commissions.find(authentication, 800)).getStatusCode());
        verify(jdbc).query(argThat(sql -> sql.contains("owner_user_id=? AND id=?")),
            any(org.springframework.jdbc.core.RowMapper.class), eq(55), eq(800L));
    }

    @Test
    void rejectsInvalidInputWithoutMutation() {
        when(access.requirePro(authentication)).thenReturn(55);
        SaveCommission invalid = new SaveCommission("  ", "Cliente",
            null, null, LocalDate.now(), null, null);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> commissions.create(authentication, invalid)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void rejectsArtTypesOwnedByAnotherArtist() {
        when(access.requirePro(authentication)).thenReturn(55);
        SaveCommission invalidType = new SaveCommission("Sketch", "Cliente",
            null, null, LocalDate.now(), null, 999L);
        when(jdbc.queryForObject(contains("creator_studio_art_types"),
            eq(Integer.class), eq(55), eq(999L))).thenReturn(0);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> commissions.create(authentication, invalidType)).getStatusCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void editingOnlyChangesOwnedCommissionAndDoesNotReplaceItsStatus() {
        when(access.requirePro(authentication)).thenReturn(55);
        when(jdbc.update(contains("UPDATE creator_studio_commissions"),
            eq("Sketch"), eq("Cliente"), eq("Detalhes"), eq("Notas privadas"),
            eq(LocalDate.of(2026,11,12)), isNull(), isNull(),
            eq(800L), eq(55))).thenReturn(0);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> commissions.update(authentication, 800, request())).getStatusCode());
        verify(jdbc).update(argThat(sql ->
            sql.contains("WHERE id=? AND owner_user_id=?")
            && !sql.contains("SET status=")),
            any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void statusUpdatesAlwaysRequireOwnershipAndPreserveSeparateStages() {
        when(access.requirePro(authentication)).thenReturn(55);
        when(jdbc.update(contains("UPDATE creator_studio_commissions"),
            eq("PAUSED"), eq("PAUSED"), eq(800L), eq(55))).thenReturn(0);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> commissions.changeStatus(authentication, 800,
                new ChangeStatus(Status.PAUSED))).getStatusCode());
        verify(jdbc).update(argThat(sql ->
            sql.contains("WHERE id=? AND owner_user_id=?")
            && sql.contains("completed_at=CASE")
            && !sql.contains("creator_studio_commission_steps")),
            eq("PAUSED"), eq("PAUSED"), eq(800L), eq(55));
    }

    @Test
    void defaultQueueExcludesCompletedAndOrdersByOriginalRequestDate() {
        when(access.requirePro(authentication)).thenReturn(55);
        when(jdbc.queryForObject(contains("COUNT(*)"), eq(Long.class),
            eq(55))).thenReturn(0L);
        CommissionPage page = commissions.list(authentication, null, 1, 25);
        assertTrue(page.items().isEmpty());
        assertEquals(0L, page.total());
        assertEquals(1, page.page());
        verify(jdbc).query(argThat(sql ->
            sql.contains("WHERE owner_user_id=? AND status <> 'COMPLETED'")
            && sql.contains("ORDER BY requested_on ASC,id ASC")
            && sql.contains("LIMIT ? OFFSET ?")),
            any(org.springframework.jdbc.core.RowMapper.class),
            eq(55), eq(25), eq(0));
    }

    @Test
    void completedHistoryMustBeExplicitlyRequested() {
        when(access.requirePro(authentication)).thenReturn(55);
        when(jdbc.queryForObject(contains("COUNT(*)"), eq(Long.class),
            eq(55), eq("COMPLETED"))).thenReturn(0L);
        commissions.list(authentication, Status.COMPLETED, 2, 10);
        verify(jdbc).query(argThat(sql ->
            sql.contains("WHERE owner_user_id=? AND status=?")
            && sql.contains("ORDER BY requested_on ASC,id ASC")),
            any(org.springframework.jdbc.core.RowMapper.class),
            eq(55), eq("COMPLETED"), eq(10), eq(10));
    }

    @Test
    void processFilterUsesOnlyAuthenticatedArtistsSourceWorkflow() {
        when(access.requirePro(authentication)).thenReturn(55);
        commissions.filteredList(authentication, null, 1, 12, 99L, null, false);
        verify(jdbc).query(argThat(sql ->
            sql.contains("source_workflow_id=?")
                && sql.contains("WHERE owner_user_id=?")
                && sql.contains("status <> 'COMPLETED'")),
            any(org.springframework.jdbc.core.RowMapper.class),
            eq(55), eq(99L), eq(12), eq(0));
    }

    @Test
    void reusableStageFilterSelectsActualCurrentStepNotAnyPendingFutureStep() {
        when(access.requirePro(authentication)).thenReturn(55);
        commissions.filteredList(authentication, null, 2, 12, null, 7L, false);
        verify(jdbc).query(argThat(sql ->
            sql.contains("AND s.status<>'DONE'")
                && sql.contains("ORDER BY CASE s.status WHEN 'IN_PROGRESS'")
                && sql.contains("AND s.commission_id=creator_studio_commissions.id")),
            any(org.springframework.jdbc.core.RowMapper.class),
            eq(55), eq(7L), eq(12), eq(12));
    }

    @Test
    void customOrUnlinkedStagesCanBeRequestedSeparatelyAndInvalidFiltersAreRejected() {
        when(access.requirePro(authentication)).thenReturn(55);
        commissions.filteredList(authentication, null, 1, 25, null, null, true);
        verify(jdbc).query(argThat(sql -> sql.contains("stage_definition_id")
            && sql.contains("IS NULL")), any(org.springframework.jdbc.core.RowMapper.class),
            eq(55), eq(25), eq(0));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> commissions.filteredList(authentication, null, 1, 25,
                null, 7L, true)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> commissions.filteredList(authentication, null, 1, 25,
                -1L, null, false)).getStatusCode());
    }

    @Test
    void rejectsUnboundedPagination() {
        when(access.requirePro(authentication)).thenReturn(55);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> commissions.list(authentication, null, 1, 101)).getStatusCode());
        verifyNoInteractions(jdbc);
    }
}
