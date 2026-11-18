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
class CreatorStudioCatalogServiceTest {
    @Mock CreatorStudioAccessService access;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    CreatorStudioCatalogService catalog;

    @BeforeEach void setup() { catalog = new CreatorStudioCatalogService(access, jdbc); }

    @Test
    void freeAndUninvitedUsersCannotBrowseArtistCatalog() {
        when(access.requirePro(auth)).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> catalog.artTypes(auth)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> catalog.stageDefinitions(auth)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> catalog.workflows(auth)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void cannotAttachAnotherArtistsWorkflowToAnArtType() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.queryForObject(contains("creator_studio_workflows"),
            eq(Integer.class), eq(31), eq(900L))).thenReturn(0);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> catalog.createArtType(auth, new ArtTypeDraft("Ref Sheet", 900L)))
            .getStatusCode());
        verify(jdbc, never()).update(any(org.springframework.jdbc.core.PreparedStatementCreator.class),
            any(org.springframework.jdbc.support.KeyHolder.class));
    }

    @Test
    void updatingAnUnchangedArtTypeIsIdempotent() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.queryForObject(contains("creator_studio_art_types"),
            eq(Integer.class), eq(31), eq(14L))).thenReturn(1);
        // JdbcTemplate.update returns 0 changed rows by default.
        ArtType saved = catalog.updateArtType(auth, 14L,
            new ArtTypeDraft("Stickers", null));
        assertEquals("Stickers", saved.name());
        verify(jdbc).update(contains("UPDATE creator_studio_art_types"),
            eq("Stickers"), isNull(), eq(14L), eq(31));
    }

    @Test
    void cannotBorrowAnotherArtistsReusableStage() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.queryForObject(contains("creator_studio_stage_definitions"),
            eq(Integer.class), eq(31), eq(900L))).thenReturn(0);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> catalog.createWorkflow(auth, new WorkflowDraft("Ref Sheet", false,
                List.of(new StepDraft(900L, "Sketch"))))).getStatusCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void editingWorkflowDoesNotAlterAnyExistingCommissionSteps() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.query(contains("SELECT id FROM users"),
            any(RowMapper.class), eq(31))).thenReturn(List.of(31));
        when(jdbc.queryForObject(contains("creator_studio_workflows"),
            eq(Integer.class), eq(31), eq(12L))).thenReturn(1);
        catalog.updateWorkflow(auth, 12, new WorkflowDraft("Pro", false,
            List.of(new StepDraft(null, "Rascunho"))));
        verify(jdbc).update(contains("DELETE FROM creator_studio_workflow_steps"),
            eq(12L), eq(31));
        verify(jdbc).update(contains("INSERT INTO creator_studio_workflow_steps"),
            eq(31), eq(12L), isNull(), eq("Rascunho"), eq(0));
        verify(jdbc, never()).update(contains("creator_studio_commission_steps"),
            any(Object[].class));
    }

    @Test
    void refusesRewritingWorkflowOwnedBySomeoneElse() {
        when(access.requirePro(auth)).thenReturn(31);
        when(jdbc.query(contains("SELECT id FROM users"),
            any(RowMapper.class), eq(31))).thenReturn(List.of(31));
        when(jdbc.queryForObject(contains("creator_studio_workflows"),
            eq(Integer.class), eq(31), eq(999L))).thenReturn(0);
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> catalog.updateWorkflow(auth, 999, new WorkflowDraft("Ref", false,
                List.of()))).getStatusCode());
        verify(jdbc, never()).update(contains("DELETE FROM creator_studio_workflow_steps"),
            any(Object[].class));
    }

    @Test
    void capsTemplateComplexityAndRejectsBlankNames() {
        when(access.requirePro(auth)).thenReturn(31);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> catalog.createWorkflow(auth, new WorkflowDraft("Teste", false,
                java.util.Collections.nCopies(41, new StepDraft(null, "Sketch")))))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> catalog.createStageDefinition(auth, new StageDefinitionDraft("  ")))
            .getStatusCode());
        verifyNoInteractions(jdbc);
    }
}
