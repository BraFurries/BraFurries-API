package com.Brafurries.API.creatorstudio;

import static com.Brafurries.API.creatorstudio.CreatorStudioCommissionDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
class CreatorStudioCommissionWorkflowTest {
    @Mock CreatorStudioAccessService access;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    CreatorStudioCommissionService service;

    @BeforeEach void setup() { service = new CreatorStudioCommissionService(access, jdbc); }

    private static SaveCommission commission(Long artType, Long workflow) {
        return new SaveCommission("Ref Sheet", "Cliente", "Pedido", null,
            LocalDate.of(2026, 11, 11), null, artType, workflow);
    }

    private void simulateGeneratedKey() {
        doAnswer(invocation -> {
            KeyHolder holder = invocation.getArgument(1);
            holder.getKeyList().add(Map.of("id", 444L));
            return 1;
        }).when(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
    }

    private void stubCreatedCard() {
        Commission card = new Commission(444L, "Ref Sheet", "Cliente", "Pedido",
            null, LocalDate.of(2026,11,11), null, null, Status.QUEUED,
            LocalDateTime.of(2026,11,17,15,0), LocalDateTime.of(2026,11,17,15,0),
            null, "Sketch", 10L);
        when(jdbc.query(contains("FROM creator_studio_commissions"),
            any(RowMapper.class), eq(33), eq(444L))).thenReturn(List.of(card));
    }

    @Test
    void copiesExplicitlySelectedWorkflowStepsInTheCreationTransaction() {
        when(access.requirePro(auth)).thenReturn(33);
        when(jdbc.queryForObject(contains("creator_studio_workflows"),
            eq(Integer.class), eq(33), eq(98L))).thenReturn(1);
        simulateGeneratedKey();
        stubCreatedCard();

        Commission created = service.create(auth, commission(null, 98L));

        assertEquals(444L, created.id());
        verify(jdbc).update(argThat(sql ->
            sql.contains("INSERT INTO creator_studio_commission_steps")
                && sql.contains("SELECT ?,?,stage_definition_id,name,position,'PENDING'")
                && sql.contains("WHERE owner_user_id=? AND workflow_id=?")),
            eq(33), eq(444L), eq(33), eq(98L));
    }

    @Test
    void defaultWorkflowIsAppliedWithoutForcingAnySpecificArtType() {
        when(access.requirePro(auth)).thenReturn(33);
        when(jdbc.query(contains("is_default=1"), any(RowMapper.class),
            eq(33))).thenReturn(List.of(98L));
        when(jdbc.queryForObject(contains("creator_studio_workflows"),
            eq(Integer.class), eq(33), eq(98L))).thenReturn(1);
        simulateGeneratedKey();
        stubCreatedCard();
        service.create(auth, commission(null, null));
        verify(jdbc).update(contains("INSERT INTO creator_studio_commission_steps"),
            eq(33), eq(444L), eq(33), eq(98L));
    }

    @Test
    void rejectsExplicitWorkflowOfAnotherArtistBeforeCardInsertion() {
        when(access.requirePro(auth)).thenReturn(33);
        when(jdbc.queryForObject(contains("creator_studio_workflows"),
            eq(Integer.class), eq(33), eq(98L))).thenReturn(0);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.create(auth, commission(null, 98L))).getStatusCode());
        verify(jdbc, never()).update(any(PreparedStatementCreator.class),
            any(KeyHolder.class));
    }
}
