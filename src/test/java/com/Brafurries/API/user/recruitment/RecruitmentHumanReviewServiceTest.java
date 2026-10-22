package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.Brafurries.API.user.recruitment.RecruitmentHumanReviewRepository.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
class RecruitmentHumanReviewServiceTest {
    @Mock CommunityAuthorizationService authorization;
    @Mock RecruitmentHumanReviewRepository repository;
    @Mock RecruitmentCandidateAnswerValidator validator;
    @Mock CommunityAuditLogRepository audits;
    @Mock Authentication auth;
    RecruitmentHumanReviewService service;

    @BeforeEach void setup() {
        service = new RecruitmentHumanReviewService(
            new RecruitmentFeatureGate(true, 11), authorization, repository,
            validator, audits, new ObjectMapper()
        );
    }

    private CommunityAuthorizationService.CommunityAccessContext actor(int userId, boolean active) {
        var community = new Community(); community.setId(11);
        var user = new User(); user.setId(userId);
        return new CommunityAuthorizationService.CommunityAccessContext(
            community, user, false, false, active,
            Set.of(CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS),
            List.of(), Map.of(), Set.of()
        );
    }
    private void reviewer(int userId) {
        when(authorization.requireCapability(auth,11,
            CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS)).thenReturn(actor(userId,true));
    }
    private void manager(int userId) {
        when(authorization.requireCapability(auth,11,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES)).thenReturn(actor(userId,true));
    }
    private AssessmentTarget target(int applicantId, String status) {
        return new AssessmentTarget(501L,100L,80L,applicantId,status,"HUMAN_EVALUATION",null);
    }

    @Test void onlyProcessManagersCanDiscoverVersionedStages() {
        manager(77);
        var stage = new ReviewableStage(501L,70L,80L,1,"PUBLISHED",2,"Entrevista","INTERVIEW");
        when(repository.stagesForProcess(11,70L)).thenReturn(List.of(stage));
        assertEquals(List.of(stage), service.stagesForProcess(auth,11,70L));
        verify(repository).stagesForProcess(11,70L);
    }

    @Test void wrongCommunityCannotDiscoverReviewableStages() {
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> service.stagesForProcess(auth,12,70L)).getStatusCode());
        verifyNoInteractions(authorization, repository);
    }

    @Test void crossTenantFeatureGateRejectsBeforeAnyQuery() {
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> service.assign(auth,12,501L,new RecruitmentHumanReviewService.AssignmentRequest(99,null))
        ).getStatusCode());
        verifyNoInteractions(repository, authorization);
    }

    @Test void aManagerCanAssignPersonButNotCrossTenantMember() {
        manager(77);
        when(repository.stage(11,501L)).thenReturn(
            new StageIdentity(501L,70L,80L,"HUMAN_EVALUATION"));
        when(repository.eligiblePerson(11,99)).thenReturn(true);
        when(repository.assign(11,new StageIdentity(501L,70L,80L,"HUMAN_EVALUATION"),
            99,null,77)).thenReturn(6L);
        var result = service.assign(auth,11,501L,
            new RecruitmentHumanReviewService.AssignmentRequest(99,null));
        assertEquals(6L,result.id());
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog log) -> "RECRUITMENT_REVIEWER_ASSIGNED".equals(log.getAction())
        ));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.assign(auth,11,501L,
                new RecruitmentHumanReviewService.AssignmentRequest(999,null))
        ).getStatusCode());
        verify(repository,never()).assign(
            eq(11), any(), eq(999), isNull(), eq(77));
    }

    @Test void roleAssignmentRequiresActiveTenantRole() {
        manager(77);
        when(repository.stage(11,501L)).thenReturn(
            new StageIdentity(501L,70L,80L,"HUMAN_EVALUATION"));
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.assign(auth,11,501L,
                new RecruitmentHumanReviewService.AssignmentRequest(null,200))
        ).getStatusCode());
        verify(repository,never()).assign(anyInt(),any(),any(),any(),anyInt());
    }

    @Test void assessmentCannotBeMadeByCandidateThemself() {
        reviewer(99);
        when(repository.lockAssessmentTarget(11,100L,501L)).thenReturn(target(99,"AWAITING_REVIEW"));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.assess(auth,11,100L,501L,
                new RecruitmentHumanReviewService.AssessmentRequest(null,"Great",Map.of()))
        ).getStatusCode());
        verify(repository,never()).submit(anyInt(),anyLong(),anyLong(),
            anyInt(),any(),any(),any());
    }

    @Test void broadCapabilityWithoutStageDesignationCannotAssess() {
        reviewer(77);
        when(repository.lockAssessmentTarget(11,100L,501L)).thenReturn(target(99,"AWAITING_REVIEW"));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> service.assess(auth,11,100L,501L,
                new RecruitmentHumanReviewService.AssessmentRequest(null,"Great",Map.of()))
        ).getStatusCode());
        verifyNoInteractions(audits);
        verify(repository,never()).submit(anyInt(),anyLong(),anyLong(),
            anyInt(),any(),any(),any());
    }

    @Test void cannotAssessUnavailableStageOrAutoEvaluation() {
        reviewer(77);
        when(repository.lockAssessmentTarget(11,100L,501L)).thenReturn(target(99,"PENDING"));
        when(repository.assignedToStage(11,501L,80L,77)).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> service.assess(auth,11,100L,501L,
                new RecruitmentHumanReviewService.AssessmentRequest(null,"Great",Map.of()))
        ).getStatusCode());
    }

    @Test void validAssessmentDoesNotChangeStageOrPublishDecision() {
        reviewer(77);
        when(repository.lockAssessmentTarget(11,100L,501L)).thenReturn(target(99,"AWAITING_REVIEW"));
        when(repository.assignedToStage(11,501L,80L,77)).thenReturn(true);
        var response = new Assessment(5L,100L,501L,77,new BigDecimal("82.50"),
            "Parecer",null,Instant.parse("2026-10-22T12:00:00Z"));
        when(repository.ownAssessment(11,100L,501L,77)).thenReturn(response);
        var result = service.assess(auth,11,100L,501L,
            new RecruitmentHumanReviewService.AssessmentRequest(
                new BigDecimal("82.50"),"Parecer",Map.of()));
        assertEquals("Parecer",result.opinion());
        verify(repository).submit(11,100L,501L,77,new BigDecimal("82.50"),"Parecer",null);
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog log) ->
                "RECRUITMENT_ASSESSMENT_SUBMITTED".equals(log.getAction())
        ));
    }

    @Test void scoreOverOneHundredRejected() {
        reviewer(77);
        when(repository.lockAssessmentTarget(11,100L,501L)).thenReturn(target(99,"AWAITING_REVIEW"));
        when(repository.assignedToStage(11,501L,80L,77)).thenReturn(true);
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> service.assess(auth,11,100L,501L,
                new RecruitmentHumanReviewService.AssessmentRequest(
                    new BigDecimal("101"),"No",Map.of()))
        ).getStatusCode());
        verify(repository,never()).submit(anyInt(),anyLong(),anyLong(),
            anyInt(),any(),any(),any());
    }

    @Test void onlyOwnAssessmentIsReturnedWithCurrentDesignation() {
        reviewer(77);
        when(repository.stage(11,501L)).thenReturn(new StageIdentity(501L,70L,80L,"HUMAN_EVALUATION"));
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> service.ownAssessment(auth,11,100L,501L)
        ).getStatusCode());
        verify(repository,never()).ownAssessment(anyInt(),anyLong(),anyLong(),anyInt());
    }
}
