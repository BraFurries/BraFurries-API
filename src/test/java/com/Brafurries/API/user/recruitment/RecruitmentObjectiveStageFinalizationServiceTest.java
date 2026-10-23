package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentCandidateAttemptRepository.*;
import com.Brafurries.API.user.recruitment.RecruitmentObjectiveGradeRepository.*;
import com.Brafurries.API.user.recruitment.RecruitmentStageDecisionRepository.*;
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
class RecruitmentObjectiveStageFinalizationServiceTest {
    @Mock CommunityAuthorizationService authorization;
    @Mock RecruitmentStageDecisionRepository decisions;
    @Mock RecruitmentCandidateAttemptRepository attempts;
    @Mock RecruitmentObjectiveGradeRepository grades;
    @Mock CommunityAuditLogRepository audits;
    @Mock Authentication auth;
    RecruitmentObjectiveStageFinalizationService service;
    static final Instant NOW=Instant.parse("2026-10-23T15:00:00Z");
    static final long APPLICATION=100L, STAGE=501L;

    @BeforeEach void setup() {
        service=new RecruitmentObjectiveStageFinalizationService(
            new RecruitmentFeatureGate(true,11),authorization,decisions,attempts,grades,audits);
    }

    void manager(int userId,boolean active) {
        var community=new Community();community.setId(11);
        var user=new User();user.setId(userId);
        when(authorization.requireCapability(auth,11,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES)).thenReturn(
            new CommunityAuthorizationService.CommunityAccessContext(
                community,user,false,false,active,
                Set.of(CommunityCapability.RECRUITMENT_MANAGE_PROCESSES),
                List.of(),Map.of(),Set.of()));
    }

    void ready(int maxAttempts,Instant deadline,boolean hard,
        List<Attempt> history,List<Grade> submittedGrades,boolean withNext) {
        manager(77,true);
        when(decisions.lockApplication(11,APPLICATION))
            .thenReturn(new Application(APPLICATION,80,99,"IN_PROGRESS"));
        var stages=withNext
            ?List.of(
                new Stage(STAGE,1,"AUTO_EVALUATION","AWAITING_CANDIDATE",null),
                new Stage(502,2,"HUMAN_EVALUATION","PENDING",null))
            :List.of(new Stage(STAGE,1,"AUTO_EVALUATION","AWAITING_CANDIDATE",null));
        when(decisions.stages(11,APPLICATION,80)).thenReturn(stages);
        when(attempts.policy(11,80,STAGE))
            .thenReturn(new Policy(maxAttempts,120,deadline,hard,null,false));
        when(grades.policy(11,80,STAGE))
            .thenReturn(new GradePolicy(new BigDecimal("70.00"),"BEST"));
        when(attempts.dbNow()).thenReturn(NOW);
        when(attempts.attempts(11,APPLICATION,STAGE)).thenReturn(history);
        when(grades.grades(11,APPLICATION,STAGE)).thenReturn(submittedGrades);
        when(decisions.completeObjective(11,APPLICATION,STAGE)).thenReturn(true);
        when(decisions.setApplicationStatus(11,APPLICATION,"AWAITING_PUBLICATION"))
            .thenReturn(true);
        when(decisions.advance(11,APPLICATION,502,"AWAITING_REVIEW")).thenReturn(true);
    }
    Attempt submission(int id,int index) {
        return new Attempt(id,APPLICATION,STAGE,index,"SUBMITTED",
            NOW.minusSeconds(100),NOW.minusSeconds(1),NOW.minusSeconds(50));
    }
    Grade grade(int id,int earned,int possible,boolean passed) {
        return new Grade(id,earned,possible,1,1,
            new BigDecimal("70.00"),passed,NOW.minusSeconds(20));
    }

    @Test void gateBlocksOtherCommunityBeforeAuthAndReads() {
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.finalizeStage(auth,12,APPLICATION,STAGE)).getStatusCode());
        verifyNoInteractions(authorization,attempts,grades,decisions);
    }

    @Test void inactiveManagerCannotFinalize() {
        manager(77,false);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.finalizeStage(auth,11,APPLICATION,STAGE)).getStatusCode());
        verifyNoInteractions(decisions,attempts,grades);
    }

    @Test void managerMustNotDecideOwnCandidateResult() {
        manager(99,true);
        when(decisions.lockApplication(11,APPLICATION))
            .thenReturn(new Application(APPLICATION,80,99,"IN_PROGRESS"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.finalizeStage(auth,11,APPLICATION,STAGE)).getStatusCode());
        verify(decisions,never()).insertObjectiveDecision(
            anyInt(),anyLong(),anyLong(),anyInt(),anyString(),anyString(),any());
    }

    @Test void missingTenantApplicationIsInvisible() {
        manager(77,true);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.finalizeStage(auth,11,APPLICATION,STAGE)).getStatusCode());
        verifyNoInteractions(attempts,grades);
    }

    @Test void unusedAttemptsWithoutDeadlinePreventEarlyClosure() {
        ready(2,null,true,List.of(submission(800,1)),
            List.of(grade(800,90,100,true)),false);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.finalizeStage(auth,11,APPLICATION,STAGE)).getStatusCode());
        verify(decisions,never()).insertObjectiveDecision(
            anyInt(),anyLong(),anyLong(),anyInt(),anyString(),anyString(),any());
    }

    @Test void activeAttemptIsNeverStolenEvenWhenFlexibleEnrollmentDeadlinePassed() {
        ready(2,NOW.minusSeconds(10),false,List.of(
            new Attempt(800,APPLICATION,STAGE,1,"STARTED",
                NOW.minusSeconds(30),NOW.plusSeconds(60),null)),List.of(),false);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.finalizeStage(auth,11,APPLICATION,STAGE)).getStatusCode());
        verify(attempts,never()).expire(anyInt(),anyLong());
        verify(decisions,never()).insertObjectiveDecision(
            anyInt(),anyLong(),anyLong(),anyInt(),anyString(),anyString(),any());
    }

    @Test void maxAttemptsWithPassingBestAdvancesOnlyNextStageWithoutPublishing() {
        ready(2,null,true,List.of(submission(800,1),submission(801,2)),
            List.of(grade(800,80,100,true),grade(801,50,100,false)),true);
        var result=service.finalizeStage(auth,11,APPLICATION,STAGE);
        assertTrue(result.passed());
        assertEquals(800L,result.selectedAttemptId());
        assertEquals("IN_PROGRESS",result.applicationStatus());
        assertEquals(502L,result.nextStageId());
        assertEquals("AWAITING_REVIEW",result.nextStageStatus());
        verify(decisions).insertObjectiveDecision(eq(11),eq(APPLICATION),eq(STAGE),
            eq(77),eq("APPROVE"),anyString(),eq(800L));
        verify(decisions).advance(11,APPLICATION,502,"AWAITING_REVIEW");
        verify(decisions,never()).blockRemaining(anyInt(),anyLong());
        verify(decisions,never()).setApplicationStatus(anyInt(),anyLong(),anyString());
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog log)->"RECRUITMENT_OBJECTIVE_STAGE_FINALIZED".equals(log.getAction())));
    }

    @Test void lastStrategyOverridesEarlierPassAndBlocksRemaining() {
        ready(2,null,true,List.of(submission(800,1),submission(801,2)),
            List.of(grade(800,90,100,true),grade(801,50,100,false)),true);
        when(grades.policy(11,80,STAGE))
            .thenReturn(new GradePolicy(new BigDecimal("70.00"),"LAST"));
        var result=service.finalizeStage(auth,11,APPLICATION,STAGE);
        assertFalse(result.passed());
        assertEquals(801L,result.selectedAttemptId());
        assertEquals("AWAITING_PUBLICATION",result.applicationStatus());
        verify(decisions).blockRemaining(11,APPLICATION);
        verify(decisions).setApplicationStatus(11,APPLICATION,"AWAITING_PUBLICATION");
        verify(decisions,never()).advance(anyInt(),anyLong(),anyLong(),anyString());
    }

    @Test void afterDeadlineNoValidSubmittedAttemptRejectsWithoutFictitiousGrade() {
        ready(2,NOW.minusSeconds(20),true,List.of(),List.of(),true);
        var result=service.finalizeStage(auth,11,APPLICATION,STAGE);
        assertFalse(result.passed());
        assertNull(result.selectedAttemptId());
        verify(decisions).insertObjectiveDecision(eq(11),eq(APPLICATION),eq(STAGE),
            eq(77),eq("REJECT"),anyString(),isNull());
    }

    @Test void ungradedSubmissionFailsClosed() {
        ready(1,null,true,List.of(submission(800,1)),List.of(),false);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.finalizeStage(auth,11,APPLICATION,STAGE)).getStatusCode());
        verify(decisions,never()).insertObjectiveDecision(
            anyInt(),anyLong(),anyLong(),anyInt(),anyString(),anyString(),any());
    }

    @Test void expiredAttemptCountsTowardsExhaustion() {
        var expired=new Attempt(800,APPLICATION,STAGE,1,"STARTED",
            NOW.minusSeconds(300),NOW.minusSeconds(60),null);
        ready(1,null,true,List.of(expired),List.of(),false);
        when(attempts.expire(11,800L)).thenReturn(true);
        var result=service.finalizeStage(auth,11,APPLICATION,STAGE);
        assertFalse(result.passed());
        verify(attempts).expire(11,800L);
        verify(decisions).completeObjective(11,APPLICATION,STAGE);
    }

    @Test void futureStageCannotBypassSequentialOrder() {
        manager(77,true);
        when(decisions.lockApplication(11,APPLICATION))
            .thenReturn(new Application(APPLICATION,80,99,"IN_PROGRESS"));
        when(decisions.stages(11,APPLICATION,80)).thenReturn(List.of(
            new Stage(500,1,"HUMAN_EVALUATION","AWAITING_REVIEW",null),
            new Stage(STAGE,2,"AUTO_EVALUATION","AWAITING_CANDIDATE",null)));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.finalizeStage(auth,11,APPLICATION,STAGE)).getStatusCode());
        verifyNoInteractions(attempts,grades);
    }

    @Test void secondFinalizationCannotOverwriteExistingDecision() {
        manager(77,true);
        when(decisions.lockApplication(11,APPLICATION))
            .thenReturn(new Application(APPLICATION,80,99,"IN_PROGRESS"));
        when(decisions.stages(11,APPLICATION,80)).thenReturn(
            List.of(new Stage(STAGE,1,"AUTO_EVALUATION","AWAITING_CANDIDATE",null)));
        when(decisions.decision(11,APPLICATION,STAGE)).thenReturn(
            new Decision(700,APPLICATION,STAGE,77,"REJECT",
                "Decisão anterior consolidada",NOW));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.finalizeStage(auth,11,APPLICATION,STAGE)).getStatusCode());
        verifyNoInteractions(attempts,grades);
        verify(decisions,never()).insertObjectiveDecision(
            anyInt(),anyLong(),anyLong(),anyInt(),anyString(),anyString(),any());
    }
}
