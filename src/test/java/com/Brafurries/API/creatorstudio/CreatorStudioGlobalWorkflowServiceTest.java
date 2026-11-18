package com.Brafurries.API.creatorstudio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.sql.ResultSet;
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
class CreatorStudioGlobalWorkflowServiceTest {
    @Mock CreatorStudioAccessService access;
    @Mock PlatformAuthorizationService platform;
    @Mock CreatorStudioCatalogService artistCatalog;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    CreatorStudioGlobalWorkflowService library;

    @BeforeEach void setup() {
        library = new CreatorStudioGlobalWorkflowService(
            access,platform,artistCatalog,jdbc);
    }

    @Test void freeAndUninvitedUsersCannotReadOrImportTheGlobalLibrary() {
        when(access.requirePro(auth)).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> library.published(auth)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> library.importAsPersonal(auth,1,new CreatorStudioGlobalWorkflowService.ImportDraft(true)))
            .getStatusCode());
        verifyNoInteractions(jdbc, artistCatalog);
    }

    @Test void communityAdministratorCannotPublishTemplates() {
        when(platform.requireOwner(auth)).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN));
        var draft = new CreatorStudioGlobalWorkflowService.GlobalWorkflowDraft(
            "Processo", "", true, List.of("Sketch"));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> library.create(auth,draft)).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> library.administrative(auth)).getStatusCode());
        verifyNoInteractions(jdbc, artistCatalog);
    }

    @Test void invalidGlobalTemplateFailsBeforeAnyDatabaseMutation() {
        User owner = new User();
        owner.setId(9);
        when(platform.requireOwner(auth)).thenReturn(owner);
        var invalid = new CreatorStudioGlobalWorkflowService.GlobalWorkflowDraft(
            "Novo", "", true, List.of(" "));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> library.create(auth,invalid)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void globalTemplateImportMakesOnlyAnArtistOwnedSnapshot() throws Exception {
        when(access.requirePro(auth)).thenReturn(42);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(8L);
        when(rs.getString("name")).thenReturn("Processo básico");
        when(rs.getString("description")).thenReturn("Modelo de exemplo");
        when(rs.getBoolean("active")).thenReturn(true);
        when(jdbc.query(contains("WHERE id=? AND active=1 FOR UPDATE"),
                org.mockito.ArgumentMatchers.<RowMapper<Object>>any(),eq(8L)))
            .thenAnswer(invocation -> List.of(
                invocation.<RowMapper<Object>>getArgument(1).mapRow(rs,0)));
        when(jdbc.query(contains("FROM creator_studio_global_workflow_steps"),
                org.mockito.ArgumentMatchers.<RowMapper<CreatorStudioGlobalWorkflowService.GlobalStep>>any(),
                eq(8L))).thenReturn(List.of(
                    new CreatorStudioGlobalWorkflowService.GlobalStep(88,"Sketch",0),
                    new CreatorStudioGlobalWorkflowService.GlobalStep(89,"Entrega",1)
                ));
        var draft = new CreatorStudioGlobalWorkflowService.ImportDraft(true);
        library.importAsPersonal(auth,8L,draft);
        verify(artistCatalog).createWorkflow(eq(auth),argThat(w ->
            w.isDefault() && w.name().equals("Processo básico") &&
            w.steps().size()==2 &&
            w.steps().get(0).stageDefinitionId()==null &&
            w.steps().get(0).name().equals("Sketch") &&
            w.steps().get(1).name().equals("Entrega")
        ));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void inactiveOrUnknownGlobalTemplateCannotBeImported() {
        when(access.requirePro(auth)).thenReturn(42);
        when(jdbc.query(contains("WHERE id=? AND active=1 FOR UPDATE"),
            org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), eq(44L)))
            .thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> library.importAsPersonal(auth,44L,null)).getStatusCode());
        verifyNoInteractions(artistCatalog);
    }

    @Test void refusesBlankModelsAndTooManyStages() {
        User owner = new User();
        owner.setId(9);
        when(platform.requireOwner(auth)).thenReturn(owner);
        var tooMany = new CreatorStudioGlobalWorkflowService.GlobalWorkflowDraft(
            "Excesso", "", true, java.util.Collections.nCopies(41,"Sketch"));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> library.create(auth,tooMany)).getStatusCode());
        verifyNoInteractions(jdbc);
    }
}
