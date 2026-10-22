package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentDecisionDeskRepository.*;
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
class RecruitmentDecisionDeskServiceTest {
    @Mock CommunityAuthorizationService authorization;
    @Mock RecruitmentDecisionDeskRepository repository;
    @Mock CommunityAuditLogRepository audits;
    @Mock Authentication auth;
    RecruitmentDecisionDeskService service;

    private final QueueItem candidate=new QueueItem(100L,70L,80L,99,
        "Colaboradores","IN_PROGRESS",Instant.parse("2026-10-22T10:00:00Z"));
    private final StageRecord active=new StageRecord(501L,1,"Inscrição","APPLICATION",
        "AWAITING_REVIEW",null,2,null,null,null,null);

    @BeforeEach void setup() {
        service=new RecruitmentDecisionDeskService(new RecruitmentFeatureGate(true,11),
            authorization,repository,audits,new ObjectMapper());
    }

    private void manager(int userId,boolean activeMember) {
        var community=new Community();community.setId(11);
        var user=new User();user.setId(userId);
        when(authorization.requireCapability(auth,11,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES)).thenReturn(
            new CommunityAuthorizationService.CommunityAccessContext(
                community,user,false,false,activeMember,
                Set.of(CommunityCapability.RECRUITMENT_MANAGE_PROCESSES),
                List.of(),Map.of(),Set.of()));
    }

    @Test void tenantGateBlocksBeforeAuthorization() {
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.queue(auth,12,70L,null)).getStatusCode());
        verifyNoInteractions(authorization,repository,audits);
    }

    @Test void aReviewerWithoutManagerCapabilityCannotReadEvidence() {
        when(authorization.requireCapability(auth,11,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.evidence(auth,11,70L,100L,501L)).getStatusCode());
        verifyNoInteractions(repository,audits);
    }

    @Test void inactiveManagerCannotReadSensitiveContent() {
        manager(77,false);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.detail(auth,11,70L,100L)).getStatusCode());
        verifyNoInteractions(repository,audits);
    }

    @Test void managerQueueExcludesOwnApplicantAndAuditsLookup() {
        manager(77,true);
        when(repository.queue(11,70L,77,null)).thenReturn(List.of(candidate));
        assertEquals(100L,service.queue(auth,11,70L,null).get(0).id());
        verify(repository).queue(11,70L,77,null);
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog log)->"RECRUITMENT_MANAGER_QUEUE_VIEWED".equals(log.getAction())));
    }

    @Test void noOwnCandidateAccessEvenWithManagerCapability() {
        manager(99,true);
        when(repository.application(11,70L,100L)).thenReturn(candidate);
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.detail(auth,11,70L,100L)).getStatusCode());
        verify(repository,never()).applicationAnswers(anyInt(),anyLong(),anyLong());
        verifyNoInteractions(audits);
    }

    @Test void processMismatchFailsWithoutSensitiveReads() {
        manager(77,true);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.evidence(auth,11,71L,100L,501L)).getStatusCode());
        verify(repository,never()).evidence(anyInt(),anyLong(),anyLong(),anyLong());
        verifyNoInteractions(audits);
    }

    @Test void managerSeesApplicationAndOnlySubmittedAnswers() {
        manager(77,true);
        when(repository.application(11,70L,100L)).thenReturn(candidate);
        when(repository.stages(11,100L,80L)).thenReturn(List.of(active));
        when(repository.applicationAnswers(11,100L,80L))
            .thenReturn("{\"why\":\"Quero ajudar\"}");
        var result=service.detail(auth,11,70L,100L);
        assertEquals("Quero ajudar",result.candidateAnswers().get("why").asText());
        assertEquals(2,result.stages().get(0).assessmentCount());
        assertEquals(1,result.stages().get(0).policy().minimumAssessments());
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog log)->"RECRUITMENT_MANAGER_APPLICATION_VIEWED".equals(log.getAction())));
    }

    @Test void futureStageEvidenceIsNeverReadable() {
        manager(77,true);
        when(repository.application(11,70L,100L)).thenReturn(candidate);
        when(repository.stages(11,100L,80L)).thenReturn(List.of(
            new StageRecord(502L,2,"Entrevista","INTERVIEW",
                "PENDING",null,0,null,null,null,null)));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.evidence(auth,11,70L,100L,502L)).getStatusCode());
        verify(repository,never()).evidence(anyInt(),anyLong(),anyLong(),anyLong());
        verifyNoInteractions(audits);
    }

    @Test void managerEvidenceContainsIndependentAssessmentsAndIsAudited() {
        manager(77,true);
        when(repository.application(11,70L,100L)).thenReturn(candidate);
        when(repository.stages(11,100L,80L)).thenReturn(List.of(active));
        when(repository.evidence(11,100L,80L,501L)).thenReturn(List.of(
            new AssessmentRow(800L,82,new BigDecimal("90.00"),
                "Boa comunicação","{\"clarity\":\"ok\"}",Instant.parse("2026-10-22T10:30:00Z"))));
        var result=service.evidence(auth,11,70L,100L,501L);
        assertEquals("Boa comunicação",result.assessments().get(0).opinion());
        assertEquals("ok",result.assessments().get(0).staffAnswers().get("clarity").asText());
        assertTrue(result.truncated());
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog log)->"RECRUITMENT_MANAGER_EVIDENCE_VIEWED".equals(log.getAction())));
    }
}
