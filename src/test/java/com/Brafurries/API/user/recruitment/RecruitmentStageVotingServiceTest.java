package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentStageVoteRepository.*;
import com.Brafurries.API.user.recruitment.RecruitmentStageDecisionService.*;
import com.Brafurries.API.user.recruitment.RecruitmentStageVotingService.*;
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
class RecruitmentStageVotingServiceTest {
    @Mock CommunityAuthorizationService authz;
    @Mock RecruitmentHumanReviewRepository assessments;
    @Mock RecruitmentStageVoteRepository votes;
    @Mock RecruitmentStageDecisionRepository decisions;
    @Mock RecruitmentStageDecisionService finalizer;
    @Mock CommunityAuditLogRepository audits;
    @Mock Authentication auth;
    RecruitmentStageVotingService service;

    private static final long APP=100L,STAGE=501L,VERSION=80L;
    private static final String POLICY =
        "{\"reviewMode\":\"COLLECTIVE\",\"decisionMode\":\"AUTOMATIC_VOTE\","
        + "\"minimumAssessments\":2,\"minimumVotes\":3,\"tiePolicy\":\"MANAGER_OVERRIDE\"}";
    private static final RecruitmentHumanReviewRepository.AssessmentTarget TARGET =
        new RecruitmentHumanReviewRepository.AssessmentTarget(
            STAGE,APP,VERSION,99,"AWAITING_REVIEW","HUMAN_EVALUATION",null
        );

    @BeforeEach void setup() {
        service=new RecruitmentStageVotingService(new RecruitmentFeatureGate(true,11),
            authz,assessments,votes,decisions,finalizer,audits,new ObjectMapper());
    }

    private void actor(int userId,CommunityCapability cap) {
        var community=new Community(); community.setId(11);
        var user=new User(); user.setId(userId);
        when(authz.requireCapability(auth,11,cap)).thenReturn(
            new CommunityAuthorizationService.CommunityAccessContext(
                community,user,false,false,true,Set.of(cap),List.of(),Map.of(),Set.of()
            ));
    }

    private void manager(int userId,String policy) {
        actor(userId,CommunityCapability.RECRUITMENT_MANAGE_PROCESSES);
        when(assessments.assessmentTarget(11,APP,STAGE,false)).thenReturn(TARGET);
        when(decisions.policyJson(11,STAGE,VERSION)).thenReturn(policy);
    }

    private void finalizePrerequisites(String policy,int count,int approves,int rejects) {
        manager(77,policy);
        when(decisions.lockApplication(11,APP)).thenReturn(
            new RecruitmentStageDecisionRepository.Application(APP,VERSION,99,"IN_PROGRESS"));
        when(decisions.assessmentCount(11,APP,STAGE)).thenReturn(3);
        when(votes.lockedTally(11,APP,STAGE)).thenReturn(new Tally(approves,rejects));
    }

    @Test void featureGateBlocksAnotherCommunityBeforeAnyRead() {
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.tally(auth,12,APP,STAGE)).getStatusCode());
        verifyNoInteractions(authz,assessments,votes,decisions);
    }

    @Test void reviewerCannotReadManagerTallies() {
        when(authz.requireCapability(auth,11,CommunityCapability.RECRUITMENT_MANAGE_PROCESSES))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.tally(auth,11,APP,STAGE)).getStatusCode());
        verifyNoInteractions(votes,assessments,decisions);
    }

    @Test void reviewerMustBeAssignedAndHaveSubmittedAssessmentToVote() {
        actor(88,CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS);
        when(decisions.lockApplication(11,APP)).thenReturn(
            new RecruitmentStageDecisionRepository.Application(APP,VERSION,99,"IN_PROGRESS"));
        when(assessments.assessmentTarget(11,APP,STAGE,false)).thenReturn(TARGET);
        when(decisions.policyJson(11,STAGE,VERSION)).thenReturn(POLICY);
        when(assessments.assignedToStage(11,STAGE,VERSION,88)).thenReturn(true);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.cast(auth,11,APP,STAGE,new CastRequest("APPROVE")))
            .getStatusCode());
        verifyNoInteractions(votes);
    }

    @Test void reviewerCastIsPrivateAndAuditedAndNoAutoAdvanceOccurs() {
        actor(88,CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS);
        when(decisions.lockApplication(11,APP)).thenReturn(
            new RecruitmentStageDecisionRepository.Application(APP,VERSION,99,"IN_PROGRESS"));
        when(assessments.assessmentTarget(11,APP,STAGE,false)).thenReturn(TARGET);
        when(decisions.policyJson(11,STAGE,VERSION)).thenReturn(POLICY);
        when(assessments.assignedToStage(11,STAGE,VERSION,88)).thenReturn(true);
        when(assessments.ownAssessment(11,APP,STAGE,88)).thenReturn(
            new RecruitmentHumanReviewRepository.Assessment(
                9,APP,STAGE,88,null,"Parecer",null,Instant.parse("2026-10-23T12:00:00Z"))
        );
        when(votes.own(11,APP,STAGE,88)).thenReturn(
            new Ballot(20,APP,STAGE,88,"APPROVE",Instant.parse("2026-10-23T12:01:00Z")));
        var receipt=service.cast(auth,11,APP,STAGE,new CastRequest("APPROVE"));
        assertEquals("APPROVE",receipt.choice());
        verify(votes).cast(11,APP,STAGE,88,"APPROVE");
        verifyNoInteractions(finalizer);
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog log)->"RECRUITMENT_BALLOT_CAST".equals(log.getAction())));
    }

    @Test void managerCannotVoteOnOwnCandidacy() {
        actor(99,CommunityCapability.RECRUITMENT_REVIEW_APPLICATIONS);
        when(decisions.lockApplication(11,APP)).thenReturn(
            new RecruitmentStageDecisionRepository.Application(APP,VERSION,99,"IN_PROGRESS"));
        when(assessments.assessmentTarget(11,APP,STAGE,false)).thenReturn(TARGET);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.cast(auth,11,APP,STAGE,new CastRequest("APPROVE")))
            .getStatusCode());
        verifyNoInteractions(votes);
    }

    @Test void quorumIsMandatoryAndNoPartialDecisionIsWritten() {
        finalizePrerequisites(POLICY,3,1,1);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.finalizeVote(auth,11,APP,STAGE,null)).getStatusCode());
        verifyNoInteractions(finalizer);
    }

    @Test void managerFinalizesOnlyComputedMajority() {
        finalizePrerequisites(POLICY,3,3,1);
        when(finalizer.decideFromVotes(eq(auth),eq(11),eq(APP),eq(STAGE),
            any(DecisionRequest.class),eq("VOTE_MAJORITY")))
            .thenReturn(new DecisionReceipt(null,"AWAITING_PUBLICATION",null,null));
        var result=service.finalizeVote(auth,11,APP,STAGE,null);
        assertEquals("AWAITING_PUBLICATION",result.receipt().applicationStatus());
        assertTrue(result.tally().quorumReached());
        verify(finalizer).decideFromVotes(eq(auth),eq(11),eq(APP),eq(STAGE),
            org.mockito.ArgumentMatchers.argThat((DecisionRequest r)->
                "APPROVE".equals(r.outcome())),eq("VOTE_MAJORITY"));
    }

    @Test void tieRequiresExplicitManagerOverrideWithReason() {
        finalizePrerequisites(POLICY,4,2,2);
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.finalizeVote(auth,11,APP,STAGE,null)).getStatusCode());
        verifyNoInteractions(finalizer);
        when(finalizer.decideFromVotes(eq(auth),eq(11),eq(APP),eq(STAGE),
            any(DecisionRequest.class),eq("VOTE_TIE_OVERRIDE")))
            .thenReturn(new DecisionReceipt(null,"AWAITING_PUBLICATION",null,null));
        service.finalizeVote(auth,11,APP,STAGE,
            new TieOverride("APPROVE","Critérios adicionais documentados"));
        verify(finalizer).decideFromVotes(eq(auth),eq(11),eq(APP),eq(STAGE),
            org.mockito.ArgumentMatchers.argThat((DecisionRequest r)->
                "APPROVE".equals(r.outcome()) && r.reason().contains("documentados")),
            eq("VOTE_TIE_OVERRIDE"));
    }

    @Test void configuredRejectOnTieHasDeterministicOutcome() {
        String tiePolicy=POLICY.replace("MANAGER_OVERRIDE","REJECT_ON_TIE");
        finalizePrerequisites(tiePolicy,4,2,2);
        when(finalizer.decideFromVotes(eq(auth),eq(11),eq(APP),eq(STAGE),
            any(DecisionRequest.class),eq("VOTE_TIE_REJECT")))
            .thenReturn(new DecisionReceipt(null,"AWAITING_PUBLICATION",null,null));
        service.finalizeVote(auth,11,APP,STAGE,null);
        verify(finalizer).decideFromVotes(eq(auth),eq(11),eq(APP),eq(STAGE),
            org.mockito.ArgumentMatchers.argThat((DecisionRequest r)->
                "REJECT".equals(r.outcome()) && r.reason().length()>=10),
            eq("VOTE_TIE_REJECT"));
    }
}
