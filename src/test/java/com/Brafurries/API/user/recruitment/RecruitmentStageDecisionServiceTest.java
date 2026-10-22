package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentStageDecisionRepository.*;
import com.Brafurries.API.user.recruitment.RecruitmentStageDecisionService.*;
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
class RecruitmentStageDecisionServiceTest {
    @Mock CommunityAuthorizationService authz;
    @Mock RecruitmentStageDecisionRepository repository;
    @Mock CommunityAuditLogRepository audits;
    @Mock Authentication auth;
    RecruitmentStageDecisionService service;

    @BeforeEach void setup() {
        service = new RecruitmentStageDecisionService(
            new RecruitmentFeatureGate(true,11),authz,repository,audits,
            new com.fasterxml.jackson.databind.ObjectMapper()
        );
    }

    private void manager(int userId, boolean active) {
        var community = new Community(); community.setId(11);
        var user = new User(); user.setId(userId);
        var actor = new CommunityAuthorizationService.CommunityAccessContext(
            community,user,false,false,active,
            Set.of(CommunityCapability.RECRUITMENT_MANAGE_PROCESSES),
            List.of(),Map.of(),Set.of()
        );
        when(authz.requireCapability(auth,11,CommunityCapability.RECRUITMENT_MANAGE_PROCESSES))
            .thenReturn(actor);
    }

    private void application(int candidateId) {
        when(repository.lockApplication(11,100L))
            .thenReturn(new Application(100L,80L,candidateId,"IN_PROGRESS"));
    }

    private Stage stage(long id, int position, String type, String state, String form) {
        return new Stage(id,position,type,state,form);
    }

    private Decision receipt(String outcome) {
        return new Decision(900L,100L,501L,77,outcome,
            outcome.equals("REJECT") ? "Motivo fundamentado" : null,
            Instant.parse("2026-10-22T14:00:00Z"));
    }

    @Test void featureGateRejectsOtherCommunitiesBeforeAuthorization() {
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            () -> service.decide(auth,12,100L,501L,
                new DecisionRequest("APPROVE",null))).getStatusCode());
        verifyNoInteractions(repository,authz,audits);
    }

    @Test void inactiveManagerCannotDecide() {
        manager(77,false);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            () -> service.decide(auth,11,100L,501L,
                new DecisionRequest("APPROVE",null))).getStatusCode());
        verifyNoInteractions(repository);
    }

    @Test void invalidOrAbsentOutcomeIsBadRequest() {
        manager(77,true);
        for (String outcome : new String[] {null,"APPROVED",""}) {
            assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
                () -> service.decide(auth,11,100L,501L,
                    new DecisionRequest(outcome,null))).getStatusCode());
        }
        verifyNoInteractions(repository);
    }

    @Test void ownApplicationCannotBeDecidedByManager() {
        manager(77,true); application(77);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            () -> service.decide(auth,11,100L,501L,
                new DecisionRequest("APPROVE",null))).getStatusCode());
        verify(repository,never()).insertDecision(anyInt(),anyLong(),anyLong(),
            anyInt(),any(),any());
    }

    @Test void atLeastOneAssessmentIsRequiredWithoutInventingAQuorum() {
        manager(77,true); application(99);
        when(repository.stages(11,100L,80L)).thenReturn(List.of(
            stage(501L,1,"APPLICATION","AWAITING_REVIEW",null)));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            () -> service.decide(auth,11,100L,501L,
                new DecisionRequest("APPROVE",null))).getStatusCode());
        verify(repository,never()).insertDecision(anyInt(),anyLong(),anyLong(),
            anyInt(),any(),any());
    }

    @Test void cannotSkipFutureOrNotYetCompletedStages() {
        manager(77,true); application(99);
        when(repository.stages(11,100L,80L)).thenReturn(List.of(
            stage(500L,1,"APPLICATION","PENDING",null),
            stage(501L,2,"HUMAN_EVALUATION","AWAITING_REVIEW",null)));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            () -> service.decide(auth,11,100L,501L,
                new DecisionRequest("APPROVE",null))).getStatusCode());
    }

    @Test void noManualOverrideForAutomaticStage() {
        manager(77,true); application(99);
        when(repository.stages(11,100L,80L)).thenReturn(List.of(
            stage(501L,1,"AUTO_EVALUATION","AWAITING_REVIEW",null)));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            () -> service.decide(auth,11,100L,501L,
                new DecisionRequest("APPROVE",null))).getStatusCode());
    }

    @Test void approveAdvancesOnlyOneReviewStageAndAudits() {
        manager(77,true); application(99);
        when(repository.stages(11,100L,80L)).thenReturn(List.of(
            stage(501L,1,"APPLICATION","AWAITING_REVIEW",null),
            stage(502L,2,"INTERVIEW","PENDING",null),
            stage(503L,3,"DECISION","PENDING",null)));
        when(repository.assessmentCount(11,100L,501L)).thenReturn(2);
        when(repository.complete(11,100L,501L)).thenReturn(true);
        when(repository.advance(11,100L,502L,"AWAITING_REVIEW")).thenReturn(true);
        when(repository.decision(11,100L,501L)).thenReturn(null,receipt("APPROVE"));
        var result = service.decide(auth,11,100L,501L,
            new DecisionRequest("APPROVE",null));
        assertEquals("IN_PROGRESS",result.applicationStatus());
        assertEquals(502L,result.nextStageId());
        assertEquals("AWAITING_REVIEW",result.nextStageStatus());
        verify(repository,never()).setApplicationStatus(anyInt(),anyLong(),any());
        verify(repository,never()).advance(eq(11),eq(100L),eq(503L),any());
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog log) -> "RECRUITMENT_STAGE_DECIDED".equals(log.getAction())));
    }

    @Test void candidateFormNeverSilentlySkippedOnApproval() {
        manager(77,true); application(99);
        when(repository.stages(11,100L,80L)).thenReturn(List.of(
            stage(501L,1,"APPLICATION","AWAITING_REVIEW",null),
            stage(502L,2,"AUTO_EVALUATION","PENDING", "{\"questions\":[]}")));
        when(repository.assessmentCount(11,100L,501L)).thenReturn(1);
        when(repository.complete(11,100L,501L)).thenReturn(true);
        when(repository.advance(11,100L,502L,"AWAITING_CANDIDATE")).thenReturn(true);
        when(repository.decision(11,100L,501L)).thenReturn(null,receipt("APPROVE"));
        assertEquals("AWAITING_CANDIDATE",
            service.decide(auth,11,100L,501L,
                new DecisionRequest("APPROVE",null)).nextStageStatus());
    }

    @Test void finalApprovalIsPrivateUntilPublication() {
        manager(77,true); application(99);
        when(repository.stages(11,100L,80L)).thenReturn(List.of(
            stage(501L,1,"APPLICATION","AWAITING_REVIEW",null)));
        when(repository.assessmentCount(11,100L,501L)).thenReturn(1);
        when(repository.complete(11,100L,501L)).thenReturn(true);
        when(repository.setApplicationStatus(11,100L,"AWAITING_PUBLICATION")).thenReturn(true);
        when(repository.decision(11,100L,501L)).thenReturn(null,receipt("APPROVE"));
        var result = service.decide(auth,11,100L,501L,new DecisionRequest("APPROVE",null));
        assertEquals("AWAITING_PUBLICATION",result.applicationStatus());
        assertNull(result.nextStageId());
    }

    @Test void rejectionBlocksFutureStagesAndRequiresExplanation() {
        manager(77,true);
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            () -> service.decide(auth,11,100L,501L,new DecisionRequest("REJECT","bad")))
            .getStatusCode());
        application(99);
        when(repository.stages(11,100L,80L)).thenReturn(List.of(
            stage(501L,1,"APPLICATION","AWAITING_REVIEW",null),
            stage(502L,2,"INTERVIEW","PENDING",null)));
        when(repository.assessmentCount(11,100L,501L)).thenReturn(1);
        when(repository.complete(11,100L,501L)).thenReturn(true);
        when(repository.setApplicationStatus(11,100L,"AWAITING_PUBLICATION")).thenReturn(true);
        when(repository.decision(11,100L,501L)).thenReturn(null,receipt("REJECT"));
        var result = service.decide(auth,11,100L,501L,
            new DecisionRequest("REJECT","Motivo fundamentado"));
        assertEquals("AWAITING_PUBLICATION",result.applicationStatus());
        verify(repository).blockRemaining(11,100L);
        verify(repository,never()).advance(anyInt(),anyLong(),anyLong(),any());
    }

    @Test void collectivePolicyRequiresMultipleAssessments() {
        manager(77,true); application(99);
        when(repository.stages(11,100L,80L)).thenReturn(List.of(
            stage(501L,1,"APPLICATION","AWAITING_REVIEW",null)));
        when(repository.policyJson(11,501L,80L)).thenReturn(
            "{\\"reviewMode\\":\\"COLLECTIVE\\",\\"decisionMode\\":\\"MANUAL\\",\\"minimumAssessments\\":3}"
        );
        when(repository.assessmentCount(11,100L,501L)).thenReturn(2);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            () -> service.decide(auth,11,100L,501L,
                new DecisionRequest("APPROVE",null))).getStatusCode());
        verify(repository,never()).insertDecision(anyInt(),anyLong(),anyLong(),
            anyInt(),any(),any());
    }

    @Test void automaticVotingCannotBeBypassedWithManualDecision() {
        manager(77,true); application(99);
        when(repository.stages(11,100L,80L)).thenReturn(List.of(
            stage(501L,1,"APPLICATION","AWAITING_REVIEW",null)));
        when(repository.policyJson(11,501L,80L)).thenReturn(
            "{\\"reviewMode\\":\\"COLLECTIVE\\",\\"decisionMode\\":\\"AUTOMATIC_VOTE\\",\\"minimumAssessments\\":2}"
        );
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            () -> service.decide(auth,11,100L,501L,
                new DecisionRequest("APPROVE",null))).getStatusCode());
        verify(repository,never()).insertDecision(anyInt(),anyLong(),anyLong(),
            anyInt(),any(),any());
    }

    @Test void duplicateDecisionCannotMoveStageAgain() {
        manager(77,true); application(99);
        when(repository.stages(11,100L,80L)).thenReturn(List.of(
            stage(501L,1,"APPLICATION","AWAITING_REVIEW",null)));
        when(repository.assessmentCount(11,100L,501L)).thenReturn(1);
        when(repository.decision(11,100L,501L)).thenReturn(receipt("APPROVE"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            () -> service.decide(auth,11,100L,501L,
                new DecisionRequest("APPROVE",null))).getStatusCode());
        verify(repository,never()).complete(anyInt(),anyLong(),anyLong());
    }
}
