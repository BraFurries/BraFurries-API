package com.Brafurries.API.creatorstudio;

import static com.Brafurries.API.creatorstudio.CreatorStudioProductionDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CreatorStudioStepServiceTest {
    @Mock CreatorStudioAccessService access;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    CreatorStudioStepService steps;

    @BeforeEach void setup() { steps = new CreatorStudioStepService(access, jdbc); }

    @Test
    void deniesAllStepReadsWithoutPro() {
        when(access.requirePro(auth)).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> steps.list(auth, 5L)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> steps.append(auth, 5L, new StepDraft(null, "Sketch"))).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void masksForeignCommissionWith404BeforeListingItsSteps() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.queryForObject(contains("creator_studio_commissions"),
            eq(Integer.class), eq(31), eq(600L))).thenReturn(0);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> steps.list(auth, 600L)).getStatusCode());
        verify(jdbc, never()).query(contains("creator_studio_commission_steps"),
            any(RowMapper.class), any(Object[].class));
    }

    @Test
    void importNeverOverwritesAnExistingCommissionProgress() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class),
            eq(31), eq(12L))).thenReturn(List.of(12L));
        when(jdbc.queryForObject(contains("creator_studio_commission_steps"),
            eq(Integer.class), eq(31), eq(12L))).thenReturn(1);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> steps.importWorkflow(auth, 12L, 77L)).getStatusCode());
        verify(jdbc, never()).update(contains("INSERT INTO creator_studio_commission_steps"),
            any(Object[].class));
    }

    @Test
    void applyingWorkflowToAnExistingEmptyCommissionRecordsProvenanceAtomically() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class),
            eq(31), eq(12L))).thenReturn(List.of(12L));
        when(jdbc.queryForObject(contains("creator_studio_commission_steps"),
            eq(Integer.class), eq(31), eq(12L))).thenReturn(0);
        when(jdbc.queryForObject(contains("creator_studio_workflows"),
            eq(Integer.class), eq(31), eq(77L))).thenReturn(1);

        steps.importWorkflow(auth, 12L, 77L);

        verify(jdbc).update(contains("INSERT INTO creator_studio_commission_steps"),
            eq(31), eq(12L), eq(31), eq(77L));
        verify(jdbc).update(argThat(sql ->
            sql.contains("source_workflow_id=?")
            && sql.contains("WHERE owner_user_id=? AND id=?")),
            eq(77L), eq(31), eq(12L));
    }

    @Test
    void cannotImportForeignWorkflowIntoOwnedCommission() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class),
            eq(31), eq(12L))).thenReturn(List.of(12L));
        when(jdbc.queryForObject(contains("creator_studio_commission_steps"),
            eq(Integer.class), eq(31), eq(12L))).thenReturn(0);
        when(jdbc.queryForObject(contains("creator_studio_workflows"),
            eq(Integer.class), eq(31), eq(77L))).thenReturn(0);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> steps.importWorkflow(auth, 12L, 77L)).getStatusCode());
        verify(jdbc, never()).update(contains("INSERT INTO creator_studio_commission_steps"),
            any(Object[].class));
    }

    @Test
    void cannotMutateAStageOnAnotherArtistsCommission() {
        when(access.requirePro(auth)).thenReturn(31);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> steps.delete(auth, 66L, 77L)).getStatusCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void unauthorizedAndInvalidProgressMovesNeverTouchCommissionData() {
        when(access.requirePro(auth)).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> steps.moveToStep(auth, 66L, 77L)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void canMoveOnlyWithinAnOwnedCommissionsRealStepsAndPreservesPausedStatus() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class),
            eq(31), eq(66L))).thenReturn(List.of(66L));
        when(jdbc.queryForObject(contains("SELECT status FROM creator_studio_commissions"),
            eq(String.class), eq(31), eq(66L))).thenReturn("PAUSED");
        when(jdbc.query(contains("FROM creator_studio_commission_steps"), any(RowMapper.class),
            eq(31), eq(66L), eq(77L))).thenReturn(List.of(
                new CommissionStep(77L, 66L, "Pintura", 5L, 2,
                    StepStatus.PENDING, "Anotação", null)));
        steps.moveToStep(auth, 66L, 77L);
        verify(jdbc).update(argThat(sql ->
                sql.contains("UPDATE creator_studio_commission_steps")
                && sql.contains("position < ?") && sql.contains("owner_user_id=?")),
            eq(2), eq(2), eq(2), eq(31), eq(66L));
        verify(jdbc).update(contains("UPDATE creator_studio_commissions"),
            eq(31), eq(66L));
        verify(jdbc, never()).update(contains("SET status=?"),
            any(Object[].class));
    }

    @Test
    void cannotMoveACompletedOrForeignCommission() {
        when(access.requirePro(auth)).thenReturn(31);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> steps.moveToStep(auth, 999L, 77L)).getStatusCode());
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class),
            eq(31), eq(66L))).thenReturn(List.of(66L));
        when(jdbc.queryForObject(contains("SELECT status FROM creator_studio_commissions"),
            eq(String.class), eq(31), eq(66L))).thenReturn("COMPLETED");
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> steps.moveToStep(auth, 66L, 77L)).getStatusCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void cannotMoveToAStepThatBelongsToAnotherCommission() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class),
            eq(31), eq(66L))).thenReturn(List.of(66L));
        when(jdbc.queryForObject(contains("SELECT status FROM creator_studio_commissions"),
            eq(String.class), eq(31), eq(66L))).thenReturn("IN_PROGRESS");
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> steps.moveToStep(auth, 66L, 1234L)).getStatusCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void validatesCustomStageTextAndPreventsCrossArtistSharedStageReferences() {
        when(access.requirePro(auth)).thenReturn(31);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> steps.append(auth, 66L, new StepDraft(null, " "))).getStatusCode());
        when(jdbc.queryForObject(contains("creator_studio_stage_definitions"),
            eq(Integer.class), eq(31), eq(99L))).thenReturn(0);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> steps.append(auth, 66L, new StepDraft(99L, "Sketch"))).getStatusCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void activeStepTransitionDoesNotOverwriteCommissionPausedStatus() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class),
            eq(31), eq(66L))).thenReturn(List.of(66L));
        // An unknown step fails before mutating any other step or commission state.
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> steps.update(auth, 66L, 77L,
                new UpdateStep(null, "Sketch", StepStatus.IN_PROGRESS, null))).getStatusCode());
        verify(jdbc, never()).update(contains("UPDATE creator_studio_commissions"),
            any(Object[].class));
        verify(jdbc, never()).update(contains("SET status='PENDING'"),
            any(Object[].class));
    }
}
