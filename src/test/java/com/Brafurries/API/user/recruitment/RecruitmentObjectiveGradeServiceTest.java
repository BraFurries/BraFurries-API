package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentCandidateAttemptRepository.DraftStage;
import com.Brafurries.API.user.recruitment.RecruitmentObjectiveGradeRepository.*;
import com.Brafurries.API.user.recruitment.RecruitmentObjectiveGradeService.ConfigureRequest;
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
class RecruitmentObjectiveGradeServiceTest {
    @Mock CommunityAuthorizationService authz;
    @Mock RecruitmentCandidateAttemptRepository attempts;
    @Mock RecruitmentObjectiveGradeRepository grades;
    @Mock CommunityAuditLogRepository audits;
    @Mock Authentication auth;
    RecruitmentObjectiveGradeService service;
    final Instant now=Instant.parse("2026-10-23T14:00:00Z");

    @BeforeEach void setup() {
        service=new RecruitmentObjectiveGradeService(new RecruitmentFeatureGate(true,11),
            authz,attempts,grades,audits);
    }
    void manager(int id,boolean active) {
        var user=new User();user.setId(id);
        var community=new Community();community.setId(11);
        when(authz.requireCapability(auth,11,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES)).thenReturn(
            new CommunityAuthorizationService.CommunityAccessContext(
                community,user,false,false,active,
                Set.of(CommunityCapability.RECRUITMENT_MANAGE_PROCESSES),
                List.of(),Map.of(),Set.of()));
    }
    Grade grade(long id,int points,int total) {
        return new Grade(id,points,total,1,2,
            new BigDecimal("70.00"),points*100>=total*70,now);
    }

    @Test void gateAndMembershipNeverAllowCrossTenantOrInactiveReads() {
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.managerSummary(auth,12,100L,501L)).getStatusCode());
        verifyNoInteractions(authz,grades,attempts);
        manager(77,false);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.managerSummary(auth,11,100L,501L)).getStatusCode());
        verifyNoInteractions(grades,attempts);
    }

    @Test void cannotInspectOwnGradesEvenWithManagerCapability() {
        manager(99,true);
        when(grades.stage(11,100L,501L)).thenReturn(
            new StageRef(80,99,"AUTO_EVALUATION"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.managerSummary(auth,11,100L,501L)).getStatusCode());
        verify(grades,never()).grades(anyInt(),anyLong(),anyLong());
    }

    @Test void invalidCutoffOrSelectionCannotBeSaved() {
        manager(77,true);
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.configure(auth,11,70L,501L,
                new ConfigureRequest(4L,new BigDecimal("0"),"BEST")))
            .getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.configure(auth,11,70L,501L,
                new ConfigureRequest(4L,new BigDecimal("70.001"),"LAST")))
            .getStatusCode());
        verifyNoInteractions(attempts,grades);
    }

    @Test void configOnlyDraftAndOptimisticRevision() {
        manager(77,true);
        when(attempts.lockDraft(11,70L,501L)).thenReturn(
            new DraftStage(80,"DRAFT","DRAFT","AUTO_EVALUATION",4));
        when(grades.configure(eq(11),eq(70L),eq(80L),eq(501L),
            eq(4L),any(GradePolicy.class))).thenReturn(true);
        var saved=service.configure(auth,11,70L,501L,
            new ConfigureRequest(4L,new BigDecimal("70.00"),"BEST"));
        assertEquals(5,saved.revision());
        assertEquals("BEST",saved.policy().resultStrategy());
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog e)->"RECRUITMENT_GRADE_POLICY_UPDATED".equals(e.getAction())));
    }

    @Test void publishedVersionCannotMutatePolicy() {
        manager(77,true);
        when(attempts.lockDraft(11,70L,501L)).thenReturn(
            new DraftStage(80,"ACTIVE","PUBLISHED","AUTO_EVALUATION",4));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.configure(auth,11,70L,501L,
                new ConfigureRequest(4L,new BigDecimal("70.00"),"BEST"))).getStatusCode());
        verifyNoInteractions(grades);
    }

    @Test void bestStrategySelectsHighestRatioRatherThanRawScore() {
        manager(77,true);
        when(grades.stage(11,100L,501L)).thenReturn(
            new StageRef(80,99,"AUTO_EVALUATION"));
        when(grades.policy(11,80L,501L)).thenReturn(
            new GradePolicy(new BigDecimal("70.00"),"BEST"));
        when(grades.grades(11,100L,501L)).thenReturn(List.of(
            grade(800,75,100),grade(801,8,10),grade(802,7,10)));
        var result=service.managerSummary(auth,11,100L,501L);
        assertEquals(801,result.selected().attemptId());
        assertTrue(result.provisional());
        assertEquals(3,result.attempts().size());
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog e)->"RECRUITMENT_GRADE_SUMMARY_VIEWED".equals(e.getAction())));
    }

    @Test void previouslyProvisionalSummaryBecomesFinalAfterObjectiveDecision() {
        manager(77,true);
        when(grades.stage(11,100L,501L)).thenReturn(
            new StageRef(80,99,"AUTO_EVALUATION"));
        when(grades.policy(11,80L,501L)).thenReturn(
            new GradePolicy(new BigDecimal("70.00"),"BEST"));
        when(grades.grades(11,100L,501L)).thenReturn(List.of(grade(800,80,100)));
        when(grades.finalized(11,100L,501L)).thenReturn(true);
        assertFalse(service.managerSummary(auth,11,100L,501L).provisional());
    }

    @Test void lastStrategyReturnsNewestSubmittedAttemptEvenIfLower() {
        manager(77,true);
        when(grades.stage(11,100L,501L)).thenReturn(
            new StageRef(80,99,"AUTO_EVALUATION"));
        when(grades.policy(11,80L,501L)).thenReturn(
            new GradePolicy(new BigDecimal("70.00"),"LAST"));
        when(grades.grades(11,100L,501L)).thenReturn(List.of(
            grade(800,90,100),grade(801,50,100)));
        assertEquals(801,service.managerSummary(auth,11,100L,501L).selected().attemptId());
    }
}
