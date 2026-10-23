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
import com.Brafurries.API.user.recruitment.RecruitmentCandidateAttemptService.*;
import com.Brafurries.API.user.recruitment.RecruitmentStageDecisionRepository.Application;
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
class RecruitmentCandidateAttemptServiceTest {
    @Mock CommunityAuthorizationService authz;
    @Mock RecruitmentCandidateAttemptRepository repository;
    @Mock RecruitmentStageDecisionRepository decisions;
    @Mock RecruitmentObjectiveGradeRepository grades;
    @Mock CommunityAuditLogRepository audits;
    @Mock Authentication auth;
    RecruitmentCandidateAttemptService service;
    static final Instant NOW=Instant.parse("2026-10-23T13:00:00Z");
    static final String FORM="{\"questions\":[{"
        + "\"id\":\"question\",\"label\":\"Uma resposta\",\"type\":\"SINGLE_CHOICE\","
        + "\"required\":true,\"options\":[{\"id\":\"a\",\"label\":\"A\"},"
        + "{\"id\":\"b\",\"label\":\"B\"}],\"points\":10,\"correctAnswers\":[\"a\"]}]}";

    @BeforeEach void setup() {
        service=new RecruitmentCandidateAttemptService(
            new RecruitmentFeatureGate(true,11),authz,repository,decisions,
            grades,new RecruitmentObjectiveScorer(),new RecruitmentFormValidator(),new RecruitmentCandidateAnswerValidator(),
            audits,new ObjectMapper());
    }
    void actor(int id, boolean active, boolean manager) {
        var community=new Community();community.setId(11);
        var user=new User();user.setId(id);
        var context=new CommunityAuthorizationService.CommunityAccessContext(
            community,user,false,false,active,
            manager?Set.of(CommunityCapability.RECRUITMENT_MANAGE_PROCESSES):Set.of(),
            List.of(),Map.of(),Set.of());
        if(manager)
            when(authz.requireCapability(auth,11,
                CommunityCapability.RECRUITMENT_MANAGE_PROCESSES)).thenReturn(context);
        else when(authz.resolve(auth,11)).thenReturn(context);
    }
    Stage stage(Policy policy) {
        return new Stage(100L,80L,99,"IN_PROGRESS","AWAITING_CANDIDATE",
            "AUTO_EVALUATION",FORM,policy);
    }
    void owner(Policy policy) {
        actor(99,true,false);
        when(decisions.lockApplication(11,100L))
            .thenReturn(new Application(100,80,99,"IN_PROGRESS"));
        when(repository.stage(11,100,501)).thenReturn(stage(policy));
    }
    Policy policy(boolean hard, Instant deadline) {
        return new Policy(2,120,deadline,hard,null,false);
    }

    @Test void gateDeniesOtherCommunityWithoutLookingUpCandidate() {
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.start(auth,12,100L,501L)).getStatusCode());
        verifyNoInteractions(authz,repository,decisions);
    }
    @Test void foreignCandidateCannotStartOthersAttempt() {
        actor(101,true,false);
        when(decisions.lockApplication(11,100L))
            .thenReturn(new Application(100,80,99,"IN_PROGRESS"));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.start(auth,11,100L,501L)).getStatusCode());
        verifyNoInteractions(repository,audits);
    }
    @Test void inactiveCandidateCannotTakeExam() {
        actor(99,false,false);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.start(auth,11,100L,501L)).getStatusCode());
        verifyNoInteractions(repository,decisions);
    }

    @Test void hardDeadlineClampsAttemptAndRemovesAnswerKey() {
        owner(policy(true,NOW.plusSeconds(45)));
        when(repository.dbNow()).thenReturn(NOW);
        when(repository.create(11,100,80,501,1,NOW.plusSeconds(45)))
            .thenReturn(new Attempt(800,100,501,1,"STARTED",NOW,
                NOW.plusSeconds(45),null));
        var session=service.start(auth,11,100L,501L);
        assertEquals(NOW.plusSeconds(45),session.expiresAt());
        assertEquals(NOW,session.serverNow());
        assertEquals(1,session.form().questions().size());
        var question=session.form().questions().get(0);
        assertEquals("question",question.id());
        assertEquals("SINGLE_CHOICE",question.type());
        assertEquals("a",question.options().get(0).id());
        assertFalse(question.toString().contains("correctAnswers"));
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog event)->"RECRUITMENT_CANDIDATE_ATTEMPT_STARTED".equals(event.getAction())));
    }

    @Test void flexibleDeadlineLetsStartedAttemptFinishWithinOwnDuration() {
        owner(policy(false,NOW.plusSeconds(45)));
        when(repository.dbNow()).thenReturn(NOW);
        when(repository.create(11,100,80,501,1,NOW.plusSeconds(120)))
            .thenReturn(new Attempt(800,100,501,1,"STARTED",NOW,
                NOW.plusSeconds(120),null));
        assertEquals(NOW.plusSeconds(120),
            service.start(auth,11,100L,501L).expiresAt());
    }

    @Test void repeatedStartReturnsSameAttemptWithoutConsumingNewAttempt() {
        owner(policy(true,null));
        when(repository.dbNow()).thenReturn(NOW);
        when(repository.attempts(11,100,501)).thenReturn(List.of(
            new Attempt(800,100,501,1,"STARTED",NOW,NOW.plusSeconds(60),null)));
        assertEquals(800,service.start(auth,11,100L,501L).attemptId());
        verify(repository,never()).create(anyInt(),anyLong(),anyLong(),
            anyLong(),anyInt(),any());
        verifyNoInteractions(audits);
    }

    @Test void expiredPriorAttemptConsumesOneOfAllowedAttempts() {
        owner(policy(true,null));
        when(repository.dbNow()).thenReturn(NOW);
        when(repository.attempts(11,100,501)).thenReturn(List.of(
            new Attempt(800,100,501,1,"STARTED",NOW.minusSeconds(180),
                NOW.minusSeconds(60),null)));
        when(repository.create(11,100,80,501,2,NOW.plusSeconds(120)))
            .thenReturn(new Attempt(801,100,501,2,"STARTED",NOW,
                NOW.plusSeconds(120),null));
        assertEquals(2,service.start(auth,11,100L,501L).attemptNumber());
        verify(repository).expire(11,800);
    }

    @Test void insufficientAttemptsAndExpiredDeadlineBlockCreation() {
        owner(policy(true,NOW.minusSeconds(1)));
        when(repository.dbNow()).thenReturn(NOW);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.start(auth,11,100L,501L)).getStatusCode());
        verify(repository,never()).create(anyInt(),anyLong(),anyLong(),
            anyLong(),anyInt(),any());
    }

    @Test void submissionRejectsWrongAttemptAndNoContentIsSaved() {
        owner(policy(true,null));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.submit(auth,11,100L,501L,999L,new SubmitRequest(Map.of())))
            .getStatusCode());
        verify(repository,never()).submit(anyInt(),anyLong(),anyString());
    }

    @Test void successfulSubmissionIsValidatedAndDoesNotGradeOrAdvance() throws Exception {
        owner(policy(true,null));
        when(repository.attempts(11,100,501)).thenReturn(List.of(
            new Attempt(800,100,501,1,"STARTED",NOW,NOW.plusSeconds(60),null)));
        when(repository.dbNow()).thenReturn(NOW,NOW);
        when(repository.submit(eq(11),eq(800L),anyString())).thenReturn(true);
        when(grades.policy(11,80L,501L)).thenReturn(
            new RecruitmentObjectiveGradeRepository.GradePolicy(
                new java.math.BigDecimal("70.00"),"BEST"));
        var answer=new ObjectMapper().readTree("\"b\"");
        var result=service.submit(auth,11,100L,501L,800L,
            new SubmitRequest(Map.of("question",answer)));
        assertEquals("SUBMITTED",result.status());
        verify(repository).submit(eq(11),eq(800L),
            org.mockito.ArgumentMatchers.argThat(json->json.contains("\"b\"")));
        verify(grades).insertGrade(eq(11),eq(800L),
            org.mockito.ArgumentMatchers.argThat((RecruitmentObjectiveScorer.Score grade)->
                grade.earnedPoints()==0 && grade.possiblePoints()==10 && !grade.passed()),
            eq(new java.math.BigDecimal("70.00")));
        verify(decisions,never()).advance(anyInt(),anyLong(),anyLong(),anyString());
        verify(decisions,never()).setApplicationStatus(anyInt(),anyLong(),anyString());
    }

    @Test void submittingAfterTimeLimitIsRejected() throws Exception {
        owner(policy(true,null));
        when(repository.attempts(11,100,501)).thenReturn(List.of(
            new Attempt(800,100,501,1,"STARTED",NOW.minusSeconds(180),
                NOW.minusSeconds(60),null)));
        when(repository.dbNow()).thenReturn(NOW);
        var answer=new ObjectMapper().readTree("\"a\"");
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.submit(auth,11,100L,501L,800L,
                new SubmitRequest(Map.of("question",answer)))).getStatusCode());
        verify(repository,never()).submit(anyInt(),anyLong(),anyString());
    }

    @Test void authorizedReinstatementCanSubmitDespiteOriginalHardDeadline() throws Exception {
        owner(policy(true,NOW.minusSeconds(20)));
        when(repository.attempts(11,100,501)).thenReturn(List.of(
            new Attempt(800,100,501,1,"STARTED",NOW.minusSeconds(120),
                NOW.plusSeconds(1800),null)));
        when(repository.dbNow()).thenReturn(NOW,NOW);
        when(repository.hasReinstatement(11,100,501,800)).thenReturn(true);
        when(repository.submit(eq(11),eq(800L),anyString())).thenReturn(true);
        when(grades.policy(11,80,501)).thenReturn(
            new RecruitmentObjectiveGradeRepository.GradePolicy(
                new java.math.BigDecimal("70.00"),"BEST"));
        var receipt=service.submit(auth,11,100L,501L,800L,
            new SubmitRequest(Map.of("question",
                new ObjectMapper().readTree("\"a\""))));
        assertEquals("SUBMITTED",receipt.status());
        verify(repository).hasReinstatement(11,100,501,800);
    }

    @Test void normalAttemptIsDeniedAfterHardDeadlineWithoutReinstatement() {
        owner(policy(true,NOW.minusSeconds(20)));
        when(repository.attempts(11,100,501)).thenReturn(List.of(
            new Attempt(800,100,501,1,"STARTED",NOW.minusSeconds(120),
                NOW.plusSeconds(60),null)));
        when(repository.dbNow()).thenReturn(NOW);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.submit(auth,11,100L,501L,800L,
                new SubmitRequest(Map.of()))).getStatusCode());
        verify(repository,never()).submit(anyInt(),anyLong(),anyString());
    }

    @Test void immediateGradeShowsOnlyNumericPointsNotPassingOrKey() throws Exception {
        owner(policy(true,null));
        when(repository.attempts(11,100,501)).thenReturn(List.of(
            new Attempt(800,100,501,1,"STARTED",NOW,NOW.plusSeconds(60),null)));
        when(repository.dbNow()).thenReturn(NOW,NOW);
        when(repository.submit(eq(11),eq(800L),anyString())).thenReturn(true);
        when(grades.policy(11,80,501)).thenReturn(
            new RecruitmentObjectiveGradeRepository.GradePolicy(
                new java.math.BigDecimal("70.00"),"BEST","AFTER_SUBMISSION"));
        var answer=new ObjectMapper().readTree("\"a\"");
        var receipt=service.submit(auth,11,100L,501L,800L,
            new SubmitRequest(Map.of("question",answer)));
        assertNotNull(receipt.score());
        assertEquals(10,receipt.score().earnedPoints());
        assertEquals(new java.math.BigDecimal("100.00"),receipt.score().percentage());
        assertFalse(receipt.toString().contains("correctAnswers"));
        assertFalse(receipt.toString().contains("passed"));
    }

    @Test void privatePolicyDoesNotReturnExamScoreAfterSubmission() throws Exception {
        owner(policy(true,null));
        when(repository.attempts(11,100,501)).thenReturn(List.of(
            new Attempt(800,100,501,1,"STARTED",NOW,NOW.plusSeconds(60),null)));
        when(repository.dbNow()).thenReturn(NOW,NOW);
        when(repository.submit(eq(11),eq(800L),anyString())).thenReturn(true);
        when(grades.policy(11,80,501)).thenReturn(
            new RecruitmentObjectiveGradeRepository.GradePolicy(
                new java.math.BigDecimal("70.00"),"BEST","PRIVATE"));
        var answer=new ObjectMapper().readTree("\"a\"");
        assertNull(service.submit(auth,11,100L,501L,800L,
            new SubmitRequest(Map.of("question",answer))).score());
    }

    @Test void foreignUserCannotDiscoverCurrentExamOrNumericScores() {
        actor(101,true,false);
        when(decisions.candidateId(11,100L)).thenReturn(99);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.availableExams(auth,11,100L)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.ownScores(auth,11,100L,501L)).getStatusCode());
        verifyNoInteractions(repository,grades);
    }

    @Test void privateScoreHistoryIsDeniedEvenToItsCandidate() {
        actor(99,true,false);
        when(decisions.candidateId(11,100L)).thenReturn(99);
        when(grades.stage(11,100L,501L)).thenReturn(
            new RecruitmentObjectiveGradeRepository.StageRef(80,99,"AUTO_EVALUATION"));
        when(grades.policy(11,80,501)).thenReturn(
            new RecruitmentObjectiveGradeRepository.GradePolicy(
                new java.math.BigDecimal("70.00"),"BEST","PRIVATE"));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.ownScores(auth,11,100L,501L)).getStatusCode());
        verify(grades,never()).grades(anyInt(),anyLong(),anyLong());
    }

    @Test void authorizedCandidateReadsOnlyTheirNumericScoresWhenEnabled() {
        actor(99,true,false);
        when(decisions.candidateId(11,100L)).thenReturn(99);
        when(grades.stage(11,100L,501L)).thenReturn(
            new RecruitmentObjectiveGradeRepository.StageRef(80,99,"AUTO_EVALUATION"));
        when(grades.policy(11,80,501)).thenReturn(
            new RecruitmentObjectiveGradeRepository.GradePolicy(
                new java.math.BigDecimal("70.00"),"BEST","AFTER_SUBMISSION"));
        when(grades.grades(11,100L,501L)).thenReturn(List.of(
            new RecruitmentObjectiveGradeRepository.Grade(
                800,7,10,1,2,new java.math.BigDecimal("70.00"),true,NOW)));
        var result=service.ownScores(auth,11,100L,501L);
        assertEquals(1,result.size());
        assertEquals(800L,result.get(0).attemptId());
        assertEquals(7,result.get(0).earnedPoints());
        assertEquals(new java.math.BigDecimal("70.00"),result.get(0).percentage());
        assertFalse(result.toString().contains("passed"));
        assertFalse(result.toString().contains("correctAnswers"));
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog log)->"RECRUITMENT_CANDIDATE_GRADE_VIEWED".equals(log.getAction())));
    }

    private static final String EVENT="00000000-0000-4000-8000-000000000001";

    @Test void focusEventCountsOnceAndReportsRemainingLimit() {
        owner(new Policy(2,120,null,true,3,false));
        when(repository.attemptsLocked(11,100,501)).thenReturn(List.of(
            new Attempt(800,100,501,1,"STARTED",NOW,NOW.plusSeconds(120),null)));
        when(repository.dbNow()).thenReturn(NOW);
        when(repository.incrementFocusLoss(11,800)).thenReturn(true);
        var result=service.recordIntegrityEvent(auth,11,100L,501L,800L,
            new IntegrityEventRequest(EVENT,"FOCUS_LOST","TAB_HIDDEN"));
        assertEquals(1,result.focusLosses());
        assertFalse(result.blocked());
        verify(repository).insertIntegrityEvent(11,800,EVENT,"FOCUS_LOST","TAB_HIDDEN");
        verify(repository,never()).blockAttempt(anyInt(),anyLong(),anyInt());
    }

    @Test void sameUuidCannotIncrementFocusTwice() {
        owner(new Policy(2,120,null,true,3,false));
        when(repository.attemptsLocked(11,100,501)).thenReturn(List.of(
            new Attempt(800,100,501,1,"STARTED",NOW,NOW.plusSeconds(120),null)));
        when(repository.eventExists(11,800,EVENT)).thenReturn(true);
        when(repository.focusLosses(11,800)).thenReturn(1);
        var result=service.recordIntegrityEvent(auth,11,100L,501L,800L,
            new IntegrityEventRequest(EVENT,"FOCUS_LOST","WINDOW_BLUR"));
        assertEquals(1,result.focusLosses());
        verify(repository,never()).incrementFocusLoss(anyInt(),anyLong());
        verify(repository,never()).insertIntegrityEvent(anyInt(),anyLong(),anyString(),anyString(),anyString());
        verifyNoInteractions(audits);
    }

    @Test void thirdFocusLossBlocksAttemptAndAuditsIt() {
        owner(new Policy(2,120,null,true,3,false));
        when(repository.attemptsLocked(11,100,501)).thenReturn(List.of(
            new Attempt(800,100,501,1,"STARTED",NOW,NOW.plusSeconds(120),null)));
        when(repository.dbNow()).thenReturn(NOW);
        when(repository.focusLosses(11,800)).thenReturn(2);
        when(repository.incrementFocusLoss(11,800)).thenReturn(true);
        when(repository.blockAttempt(11,800,3)).thenReturn(true);
        var result=service.recordIntegrityEvent(auth,11,100L,501L,800L,
            new IntegrityEventRequest(EVENT,"FOCUS_LOST","TAB_HIDDEN"));
        assertTrue(result.blocked());
        assertEquals("BLOCKED",result.status());
        assertEquals(3,result.focusLosses());
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog log)->"RECRUITMENT_EXAM_ATTEMPT_BLOCKED".equals(log.getAction())));
    }

    @Test void blockedStageCannotStartAnotherAttempt() {
        owner(new Policy(5,120,null,true,3,false));
        when(repository.attempts(11,100,501)).thenReturn(List.of(
            new Attempt(800,100,501,1,"BLOCKED",NOW,NOW.plusSeconds(120),null)));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.start(auth,11,100L,501L)).getStatusCode());
        verify(repository,never()).create(anyInt(),anyLong(),anyLong(),anyLong(),anyInt(),any());
    }

    @Test void copySignalIsInformativeAndDoesNotCountFocusLosses() {
        owner(new Policy(2,120,null,true,3,true));
        when(repository.attemptsLocked(11,100,501)).thenReturn(List.of(
            new Attempt(800,100,501,1,"STARTED",NOW,NOW.plusSeconds(120),null)));
        when(repository.dbNow()).thenReturn(NOW);
        var result=service.recordIntegrityEvent(auth,11,100L,501L,800L,
            new IntegrityEventRequest(EVENT,"COPY_PREVENTED","COPY"));
        assertFalse(result.blocked());
        assertEquals(0,result.focusLosses());
        verify(repository,never()).incrementFocusLoss(anyInt(),anyLong());
        verify(repository).insertIntegrityEvent(11,800,EVENT,"COPY_PREVENTED","COPY");
    }

    @Test void cannotInjectEventsForDisabledControlsOrInvalidUuid() {
        owner(new Policy(2,120,null,true,null,false));
        when(repository.attemptsLocked(11,100,501)).thenReturn(List.of(
            new Attempt(800,100,501,1,"STARTED",NOW,NOW.plusSeconds(120),null)));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.recordIntegrityEvent(auth,11,100L,501L,800L,
                new IntegrityEventRequest(EVENT,"FOCUS_LOST","TAB_HIDDEN"))).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.recordIntegrityEvent(auth,11,100L,501L,800L,
                new IntegrityEventRequest("not-a-uuid","FOCUS_LOST","TAB_HIDDEN"))).getStatusCode());
        verify(repository,never()).insertIntegrityEvent(anyInt(),anyLong(),anyString(),anyString(),anyString());
    }

    @Test void unauthorizedCandidateCannotRegisterAnotherPersonsFocusLoss() {
        actor(101,true,false);
        when(decisions.lockApplication(11,100L)).thenReturn(new Application(100,80,99,"IN_PROGRESS"));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.recordIntegrityEvent(auth,11,100L,501L,800L,
                new IntegrityEventRequest(EVENT,"FOCUS_LOST","TAB_HIDDEN"))).getStatusCode());
        verifyNoInteractions(repository,audits);
    }

    @Test void policyRejectsFocusLimitOutsideSupportedRange() {
        actor(77,true,true);
        var config=new ConfigureRequest(4L,2,120,null,false,11,true);
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.configure(auth,11,70L,501L,config)).getStatusCode());
        verifyNoInteractions(repository);
    }

    @Test void configuredFocusAndCopyControlsArePersistedOnDraftOnly() {
        actor(77,true,true);
        when(repository.lockDraft(11,70,501)).thenReturn(
            new DraftStage(80,"DRAFT","DRAFT","AUTO_EVALUATION",4));
        when(repository.savePolicy(eq(11),eq(70L),eq(501L),eq(80L),
            eq(4L),any(Policy.class))).thenReturn(true);
        var saved=service.configure(auth,11,70L,501L,
            new ConfigureRequest(4L,2,120,null,false,3,true));
        assertEquals(3,saved.policy().maxFocusLosses());
        assertTrue(saved.policy().blockCopy());
        verify(repository).savePolicy(eq(11),eq(70L),eq(501L),eq(80L),
            eq(4L),org.mockito.ArgumentMatchers.argThat(
                (Policy p)->p.maxFocusLosses()==3 && p.blockCopy()));
    }

    @Test void policyIsStoredOnlyOnCorrectRevisionOfDraftStage() {
        actor(77,true,true);
        when(repository.lockDraft(11,70,501)).thenReturn(
            new DraftStage(80,"DRAFT","DRAFT","AUTO_EVALUATION",4));
        var config=new ConfigureRequest(4L,2,120,null,false,null,false);
        when(repository.savePolicy(eq(11),eq(70L),eq(501L),eq(80L),
            eq(4L),any(Policy.class))).thenReturn(true);
        var saved=service.configure(auth,11,70L,501L,config);
        assertEquals(5,saved.draftRevision());
        verify(repository).savePolicy(eq(11),eq(70L),eq(501L),eq(80L),
            eq(4L),any(Policy.class));
        verify(audits).save(any(CommunityAuditLog.class));
    }

    @Test void policyCannotChangeAfterPublication() {
        actor(77,true,true);
        when(repository.lockDraft(11,70,501)).thenReturn(
            new DraftStage(80,"ACTIVE","PUBLISHED","AUTO_EVALUATION",4));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.configure(auth,11,70L,501L,
                new ConfigureRequest(4L,2,120,null,true,null,false))).getStatusCode());
        verify(repository,never()).savePolicy(anyInt(),anyLong(),anyLong(),
            anyLong(),anyLong(),any(Policy.class));
    }
}
