package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentDraftDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RecruitmentDraftServiceTest {
    private static final int BRAFURRIES_COMMUNITY_ID = 11;
    private static final int OTHER_COMMUNITY_ID = 12;

    @Mock CommunityAuthorizationService authorization;
    @Mock RecruitmentDraftRepository drafts;
    @Mock CommunityAuditLogRepository audits;
    @Mock Authentication auth;

    private RecruitmentDraftService service;
    private Community community;
    private User actorUser;

    @BeforeEach
    void setUp() {
        community = new Community();
        community.setId(BRAFURRIES_COMMUNITY_ID);
        community.setName("BraFurries");
        actorUser = new User();
        actorUser.setId(99);
        service = serviceWithGate(true, BRAFURRIES_COMMUNITY_ID);
    }

    private RecruitmentDraftService serviceWithGate(boolean enabled, int allowedId) {
        return new RecruitmentDraftService(
            new RecruitmentFeatureGate(enabled, allowedId),
            authorization, drafts, audits
        );
    }

    private void authorized() {
        var actor = new CommunityAuthorizationService.CommunityAccessContext(
            community, actorUser, false, false, true,
            Set.of(CommunityCapability.RECRUITMENT_MANAGE_PROCESSES),
            List.of(), Map.of(), Set.of()
        );
        lenient().when(authorization.requireCapability(
            auth, BRAFURRIES_COMMUNITY_ID,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES
        )).thenReturn(actor);
    }

    private ProcessResponse process(long id, String title) {
        return new ProcessResponse(id, BRAFURRIES_COMMUNITY_ID, title, null,
            "DRAFT", "PRIVATE", "CLOSED", 1);
    }

    @Test
    void disabledFeatureDoesNotQueryAuthorizationOrDatabase() {
        service = serviceWithGate(false, BRAFURRIES_COMMUNITY_ID);
        var exception = assertThrows(ResponseStatusException.class,
            () -> service.list(auth, BRAFURRIES_COMMUNITY_ID));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(authorization, drafts, audits);
    }

    @Test
    void missingExplicitCommunityConfigurationFailsClosed() {
        service = serviceWithGate(true, 0);
        var exception = assertThrows(ResponseStatusException.class,
            () -> service.list(auth, BRAFURRIES_COMMUNITY_ID));
        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(authorization, drafts, audits);
    }

    @Test
    void foreignCommunityNeverReachesAuthorizationOrDatabase() {
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class,
                () -> service.create(auth, OTHER_COMMUNITY_ID,
                    new CreateProcessRequest("Processo", null))).getStatusCode());
        verifyNoInteractions(authorization, drafts, audits);
    }

    @Test
    void authorizedListIsScopedToCommunity() {
        authorized();
        when(drafts.list(BRAFURRIES_COMMUNITY_ID)).thenReturn(List.of(process(7, "Staff")));
        assertEquals(1, service.list(auth, BRAFURRIES_COMMUNITY_ID).size());
        verify(drafts).list(BRAFURRIES_COMMUNITY_ID);
        verifyNoInteractions(audits);
    }

    @Test
    void missingRecruitmentCapabilityDeniesBeforeDatabaseRead() {
        when(authorization.requireCapability(auth, BRAFURRIES_COMMUNITY_ID,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                () -> service.list(auth, BRAFURRIES_COMMUNITY_ID)).getStatusCode());
        verifyNoInteractions(drafts, audits);
    }

    @Test
    void createsDraftAndAuditsWithinActorCommunity() {
        authorized();
        when(drafts.create(BRAFURRIES_COMMUNITY_ID, 99, "Staff", "Sobre a seleção"))
            .thenReturn(7L);
        when(drafts.find(BRAFURRIES_COMMUNITY_ID, 7L))
            .thenReturn(process(7L, "Staff"));
        var result = service.create(auth, BRAFURRIES_COMMUNITY_ID,
            new CreateProcessRequest(" Staff ", " Sobre a seleção "));
        assertEquals(7L, result.id());
        verify(drafts).create(BRAFURRIES_COMMUNITY_ID, 99, "Staff", "Sobre a seleção");
        ArgumentCaptor<CommunityAuditLog> records = ArgumentCaptor.forClass(CommunityAuditLog.class);
        verify(audits).save(records.capture());
        assertSame(community, records.getValue().getCommunity());
        assertSame(actorUser, records.getValue().getActorUser());
        assertEquals("RECRUITMENT_PROCESS_CREATED", records.getValue().getAction());
        assertEquals("7", records.getValue().getTargetId());
    }

    @Test
    void canAppendSequentialStageToDraftAndAudit() {
        authorized();
        when(drafts.appendDraftStage(BRAFURRIES_COMMUNITY_ID, 7L,
            "Análise interna", "Em análise", StageType.ANALYSIS, 0L))
            .thenReturn(new StageResponse(50L, 7L, 30L, 2,
                "Análise interna", "Em análise", StageType.ANALYSIS));

        var response = service.appendStage(auth, BRAFURRIES_COMMUNITY_ID, 7L,
            new CreateStageRequest(" Análise interna ", " Em análise ", StageType.ANALYSIS));
        assertEquals(2, response.position());
        verify(audits).save(argThat(event ->
            event.getAction().equals("RECRUITMENT_STAGE_ADDED")
                && event.getTargetId().equals("50")
        ));
    }

    @Test
    void processWithoutDraftVersionRejectsStageMutation() {
        authorized();
        when(drafts.find(BRAFURRIES_COMMUNITY_ID, 7L))
            .thenReturn(new ProcessResponse(7L, BRAFURRIES_COMMUNITY_ID, "Publicado",
                null, "ACTIVE", "PUBLIC", "CLOSED", 0));
        var error = assertThrows(ResponseStatusException.class, () ->
            service.appendStage(auth, BRAFURRIES_COMMUNITY_ID, 7L,
                new CreateStageRequest("Nova", "Nova", StageType.ANALYSIS)));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verifyNoInteractions(audits);
    }

    @Test
    void publishedProcessCannotBeEditedAsDraft() {
        authorized();
        when(drafts.find(BRAFURRIES_COMMUNITY_ID, 7L))
            .thenReturn(new ProcessResponse(7L, BRAFURRIES_COMMUNITY_ID, "Publicado",
                null, "ACTIVE", "PUBLIC", "CLOSED", 0));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class, () ->
                service.update(auth, BRAFURRIES_COMMUNITY_ID, 7L,
                    new UpdateProcessRequest("Novo nome", null))).getStatusCode());
        verifyNoInteractions(audits);
    }

    @Test
    void missingProcessIsNotFoundAndCannotBeEnumeratedAcrossTenants() {
        authorized();
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () ->
                service.detail(auth, BRAFURRIES_COMMUNITY_ID, 4242L)).getStatusCode());
        verify(drafts).find(BRAFURRIES_COMMUNITY_ID, 4242L);
    }

    @Test
    void invalidTitleIsRejectedBeforeWriting() {
        authorized();
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class, () ->
                service.create(auth, BRAFURRIES_COMMUNITY_ID,
                    new CreateProcessRequest("  ", null))).getStatusCode());
        verifyNoInteractions(drafts, audits);
    }

    @Test
    void recruitmentManagementCapabilityIsDelegableByRoleOrUser() {
        assertTrue(CommunityCapability.RECRUITMENT_MANAGE_PROCESSES.roleGrantAllowed());
        assertTrue(CommunityCapability.RECRUITMENT_MANAGE_PROCESSES.directGrantAllowed());
        assertFalse(CommunityCapability.RECRUITMENT_MANAGE_PROCESSES.hierarchicalTeamScope());
    }
}
