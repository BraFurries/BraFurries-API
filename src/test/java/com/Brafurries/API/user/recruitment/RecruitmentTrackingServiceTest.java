package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentTrackingDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RecruitmentTrackingServiceTest {
    @Mock CommunityAuthorizationService authorization;
    @Mock RecruitmentTrackingRepository repository;
    @Mock Authentication auth;
    @Mock CommunityAuditLogRepository audits;

    RecruitmentTrackingService service;

    @BeforeEach void setup() {
        service = new RecruitmentTrackingService(
            new RecruitmentFeatureGate(true, 11), authorization, repository, new ObjectMapper(), audits
        );
    }

    private CommunityAuthorizationService.CommunityAccessContext actor(
        int userId, boolean active, Set<CommunityCapability> capabilities
    ) {
        var community = new Community(); community.setId(11);
        var user = new User(); user.setId(userId);
        return new CommunityAuthorizationService.CommunityAccessContext(
            community, user, false, false, active, capabilities,
            List.of(), Map.of(), Set.of()
        );
    }

    @Test void featureGateBlocksWrongCommunityBeforeReadingPrivateData() {
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () ->
                service.queue(auth, 12, 70L, null)).getStatusCode());
        verifyNoInteractions(authorization, repository);
    }

    @Test void inactiveApplicantCannotReadEvenTheirOwnStatus() {
        when(authorization.resolve(auth,11)).thenReturn(actor(99,false,Set.of()));
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () ->
                service.own(auth,11,null)).getStatusCode());
        verifyNoInteractions(repository);
    }

    @Test void ownListAlwaysUsesAuthenticatedUserAndPublicReceipt() {
        when(authorization.resolve(auth,11)).thenReturn(actor(99,true,Set.of()));
        when(repository.own(11,99,50L)).thenReturn(List.of(
            new OwnApplication(20L,70L,"Staff",Instant.parse("2026-10-22T10:00:00Z"),"RECEIVED")
        ));
        var result = service.own(auth,11,50L);
        assertEquals(1,result.size());
        assertEquals("RECEIVED",result.get(0).publicStatus());
        verify(repository).own(11,99,50L);
        verifyNoMoreInteractions(repository);
    }

    @Test void cannotEnumerateAnotherCandidatesReceipt() {
        when(authorization.resolve(auth,11)).thenReturn(actor(99,true,Set.of()));
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class, () ->
                service.ownDetail(auth,11,201L)).getStatusCode());
        verify(repository).ownById(11,99,201L);
    }

    @Test void managementOrPublicationAloneCannotAccessPrivateQueue() {
        when(authorization.requireCapability(auth,11,
            CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class, () ->
                service.queue(auth,11,70L,null)).getStatusCode());
        verifyNoInteractions(repository);
    }

    @Test void reviewerOnlyCanDiscoverProcessesWithOtherApplicants() {
        when(authorization.requireCapability(auth,11,
            CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS))
            .thenReturn(actor(99,true,Set.of(CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS)));
        when(repository.reviewProcesses(11,99))
            .thenReturn(List.of(new ReviewProcess(70L,"Seleção de Staff")));
        var processes = service.reviewProcesses(auth,11);
        assertEquals(70L,processes.get(0).id());
        verify(repository).reviewProcesses(11,99);
    }

    @Test void reviewersGetTenantAndSelfExcludedQueue() {
        when(authorization.requireCapability(auth,11,
            CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS))
            .thenReturn(actor(99,true,Set.of(CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS)));
        when(repository.queue(11,70L,99,50L)).thenReturn(List.of());
        assertTrue(service.queue(auth,11,70L,50L).isEmpty());
        verify(repository).queue(11,70L,99,50L);
    }

    @Test void reviewerCannotOpenOwnApplicationAnswers() {
        when(authorization.requireCapability(auth,11,
            CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS))
            .thenReturn(actor(99,true,Set.of(CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS)));
        when(repository.reviewById(11,100L)).thenReturn(
            new ReviewQueueItem(100L,70L,80L,99,"Staff","IN_PROGRESS",Instant.now())
        );
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class, () ->
                service.reviewDetail(auth,11,100L)).getStatusCode());
        verify(repository,never()).candidateAnswers(org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyLong());
        verify(repository,never()).reviewStages(org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyLong());
        verifyNoInteractions(audits);
    }

    @Test void reviewerCanReadCandidateAnswersWithoutGabaritoOrStaffForm() {
        when(authorization.requireCapability(auth,11,
            CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS))
            .thenReturn(actor(77,true,Set.of(CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS)));
        when(repository.reviewById(11,100L)).thenReturn(
            new ReviewQueueItem(100L,70L,80L,99,"Staff","IN_PROGRESS",Instant.now())
        );
        when(repository.candidateAnswers(11,100L)).thenReturn(
            new RecruitmentTrackingRepository.SubmittedAnswers(501L,
                "{\"motivation\":\"Quero ajudar\"}")
        );
        var result = service.reviewDetail(auth,11,100L);
        assertEquals("Quero ajudar",result.candidateAnswers().get("motivation").asText());
        assertEquals(501L,result.submittedStageId());
        assertFalse(result.candidateAnswers().has("correctAnswers"));
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog item) -> item.getAction().equals("RECRUITMENT_APPLICATION_VIEWED")
                && item.getTargetId().equals("100")
        ));
    }

    @Test void malformedCursorRejectedBeforeDbQuery() {
        when(authorization.resolve(auth,11)).thenReturn(actor(99,true,Set.of()));
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class, () ->
                service.own(auth,11,0L)).getStatusCode());
        verifyNoInteractions(repository);
    }

    @Test void reviewerPermissionMayBeGrantedByRoleOrDirectUser() {
        assertTrue(CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS.roleGrantAllowed());
        assertTrue(CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS.directGrantAllowed());
        assertFalse(CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS.hierarchicalTeamScope());
    }
}
