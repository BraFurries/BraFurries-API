package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentCandidateAttemptRepository.Policy;
import com.Brafurries.API.user.recruitment.RecruitmentCandidateAttemptRepository.Attempt;
import com.Brafurries.API.user.recruitment.RecruitmentExamReviewRepository.*;
import com.Brafurries.API.user.recruitment.RecruitmentStageDecisionRepository.*;
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
class RecruitmentExamReviewServiceTest {
    @Mock CommunityAuthorizationService authz;
    @Mock RecruitmentStageDecisionRepository stages;
    @Mock RecruitmentCandidateAttemptRepository attempts;
    @Mock RecruitmentExamReviewRepository reviews;
    @Mock CommunityAuditLogRepository audits;
    @Mock Authentication auth;
    RecruitmentExamReviewService service;
    final Instant now=Instant.parse("2026-10-23T15:00:00Z");
    final String reason="Um diálogo do sistema retirou o foco indevidamente";

    @BeforeEach void setup() {
        service=new RecruitmentExamReviewService(new RecruitmentFeatureGate(true,11),
            authz,stages,attempts,reviews,audits);
    }
    void manager(int userId,boolean active) {
        var community=new Community();community.setId(11);
        var user=new User();user.setId(userId);
        when(authz.requireCapability(auth,11,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES)).thenReturn(
            new CommunityAuthorizationService.CommunityAccessContext(
                community,user,false,false,active,
                Set.of(CommunityCapability.RECRUITMENT_MANAGE_PROCESSES),
                List.of(),Map.of(),Set.of()));
    }
    void stageReady() {
        manager(77,true);
        lenient().when(stages.lockApplication(11,100L))
            .thenReturn(new Application(100,80,99,"IN_PROGRESS"));
        lenient().when(stages.stagesLocked(11,100L,80L))
            .thenReturn(List.of(new Stage(501,1,"AUTO_EVALUATION",
                "AWAITING_CANDIDATE",null)));
        lenient().when(attempts.stage(11,100L,501L))
            .thenReturn(new RecruitmentCandidateAttemptRepository.Stage(
                100,80,99,"IN_PROGRESS","AWAITING_CANDIDATE",
                "AUTO_EVALUATION","{\\\"questions\\\":[]}",
                new Policy(2,1800,now.minusSeconds(100),true,3,false)));
    }
    Incident blocked() {
        return new Incident(800L,"BLOCKED",3,now.minusSeconds(10),
            null,null,null,null);
    }

    @Test void gateBlocksOtherCommunityWithoutAuthOrRepositoryReads() {
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.panel(auth,12,100L,501L)).getStatusCode());
        verifyNoInteractions(authz,stages,attempts,reviews);
    }
    @Test void inactiveManagerCannotInspectOrRestore() {
        manager(77,false);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.panel(auth,11,100L,501L)).getStatusCode());
        verifyNoInteractions(stages,attempts,reviews);
    }
    @Test void managerCannotReviewOwnApplication() {
        manager(99,true);
        when(stages.lockApplication(11,100L))
            .thenReturn(new Application(100,80,99,"IN_PROGRESS"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.panel(auth,11,100L,501L)).getStatusCode());
        verifyNoInteractions(reviews,attempts);
    }
    @Test void reviewPanelExposesBoundedEventsNotAnswersOrGrade() {
        stageReady();
        when(reviews.incidents(11,100,501)).thenReturn(List.of(blocked()));
        when(reviews.signals(11,800)).thenReturn(List.of(
            new Signal(10,"FOCUS_LOST","TAB_HIDDEN",now.minusSeconds(20))));
        when(reviews.focusSignals(11,800)).thenReturn(3);
        var panel=service.panel(auth,11,100L,501L);
        assertEquals(1,panel.incidents().size());
        assertTrue(panel.incidents().get(0).canReinstate());
        assertEquals("TAB_HIDDEN",panel.incidents().get(0).signals().get(0).source());
        assertFalse(panel.toString().contains("answer"));
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog log)->"RECRUITMENT_EXAM_BLOCK_REVIEW_VIEWED".equals(log.getAction())));
    }
    @Test void unblockRestoresSameAttemptWithFullRenewedTimeEvenAfterHardDeadline() {
        stageReady();
        when(reviews.incident(11,100,501,800)).thenReturn(blocked());
        when(reviews.focusSignals(11,800)).thenReturn(3);
        when(attempts.dbNow()).thenReturn(now);
        when(attempts.attemptsLocked(11,100,501)).thenReturn(List.of(
            new Attempt(800,100,501,1,"BLOCKED",now.minusSeconds(200),
                now.minusSeconds(10),null)));
        when(reviews.reopenBlocked(11,100,501,800,3,now.plusSeconds(1800)))
            .thenReturn(true);
        var result=service.reinstate(auth,11,100L,501L,800L,
            new RecruitmentExamReviewService.ReasonRequest(reason));
        assertTrue(result.restored());
        assertEquals(800,result.attemptId());
        assertEquals(now.plusSeconds(1800),result.expiresAt());
        verify(reviews).insertReinstatement(11,100,501,800,77,reason,3,
            now.plusSeconds(1800));
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog log)->"RECRUITMENT_EXAM_ATTEMPT_REINSTATED".equals(log.getAction())));
    }
    @Test void rejectionOnShortReasonHasNoDataEffects() {
        stageReady();
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.reinstate(auth,11,100L,501L,800L,
                new RecruitmentExamReviewService.ReasonRequest("sem provas"))).getStatusCode());
        verifyNoInteractions(reviews);
    }
    @Test void completedStageCannotBeChangedOrReopened() {
        manager(77,true);
        when(stages.lockApplication(11,100L))
            .thenReturn(new Application(100,80,99,"AWAITING_PUBLICATION"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.reinstate(auth,11,100L,501L,800L,
                new RecruitmentExamReviewService.ReasonRequest(reason))).getStatusCode());
        verifyNoInteractions(reviews,attempts);
    }
    @Test void previousRestorationIsNeverRestoredTwice() {
        stageReady();
        when(reviews.incident(11,100,501,800)).thenReturn(new Incident(
            800,"BLOCKED",3,now.minusSeconds(10),now.minusSeconds(100),
            now.minusSeconds(1),77,reason));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.reinstate(auth,11,100L,501L,800L,
                new RecruitmentExamReviewService.ReasonRequest(reason))).getStatusCode());
        verify(reviews,never()).insertReinstatement(anyInt(),anyLong(),anyLong(),
            anyLong(),anyInt(),anyString(),anyInt(),any());
    }
    @Test void ordinaryExpiredAttemptCannotBeDisguisedAsFocusBlock() {
        stageReady();
        when(reviews.incident(11,100,501,800)).thenReturn(new Incident(
            800,"EXPIRED",3,now.minusSeconds(10),null,null,null,null));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.reinstate(auth,11,100L,501L,800L,
                new RecruitmentExamReviewService.ReasonRequest(reason))).getStatusCode());
        verify(reviews,never()).insertReinstatement(anyInt(),anyLong(),anyLong(),
            anyLong(),anyInt(),anyString(),anyInt(),any());
    }
    @Test void blockRequiresPersistedFocusEvidence() {
        stageReady();
        when(reviews.incident(11,100,501,800)).thenReturn(blocked());
        when(reviews.focusSignals(11,800)).thenReturn(1);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.reinstate(auth,11,100L,501L,800L,
                new RecruitmentExamReviewService.ReasonRequest(reason))).getStatusCode());
        verify(reviews,never()).reopenBlocked(anyInt(),anyLong(),anyLong(),anyLong(),anyInt(),any());
    }
}
