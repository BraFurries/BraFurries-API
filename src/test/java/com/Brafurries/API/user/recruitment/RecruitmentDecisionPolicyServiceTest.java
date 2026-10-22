package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.Brafurries.API.user.recruitment.RecruitmentDecisionPolicy.*;
import com.Brafurries.API.user.recruitment.RecruitmentDecisionPolicyRepository.StoredPolicy;
import com.Brafurries.API.user.recruitment.RecruitmentDecisionPolicyService.SavePolicyRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RecruitmentDecisionPolicyServiceTest {
    @Mock CommunityAuthorizationService authz;
    @Mock RecruitmentDecisionPolicyRepository repository;
    @Mock CommunityAuditLogRepository audits;
    @Mock Authentication auth;
    RecruitmentDecisionPolicyService service;

    @BeforeEach void init() {
        service=new RecruitmentDecisionPolicyService(new RecruitmentFeatureGate(true,11),
            authz,repository,audits,new ObjectMapper());
    }

    private void manager() {
        var community=new Community();community.setId(11);
        var user=new User();user.setId(77);
        when(authz.requireCapability(auth,11,CommunityCapability.RECRUITMENT_MANAGE_PROCESSES))
            .thenReturn(new CommunityAuthorizationService.CommunityAccessContext(
                community,user,false,false,true,
                Set.of(CommunityCapability.RECRUITMENT_MANAGE_PROCESSES),
                List.of(),Map.of(),Set.of()));
    }
    private RecruitmentDecisionPolicy policy() {
        return new RecruitmentDecisionPolicy(ReviewMode.COLLECTIVE,DecisionMode.MANUAL,3);
    }

    @Test void crossTenantGateDeniesWithoutQuery() {
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.detail(auth,12,70L,501L)).getStatusCode());
        verifyNoInteractions(authz,repository,audits);
    }

    @Test void readPolicyIsRestrictedToManagerAndTenant() {
        manager();
        when(repository.find(11,70L,501L)).thenReturn(
            new StoredPolicy(501L,70L,"HUMAN_EVALUATION","DRAFT","DRAFT",null,0));
        var result=service.detail(auth,11,70L,501L);
        assertEquals(1,result.policy().minimumAssessments());
        verify(repository).find(11,70L,501L);
    }

    @Test void successfulWriteIsVersionBoundAndAudited() {
        manager();
        when(repository.update(eq(11),eq(70L),eq(501L),eq(4L),anyString()))
            .thenReturn(new StoredPolicy(501L,70L,"ANALYSIS","DRAFT","DRAFT",
                "{\"reviewMode\":\"COLLECTIVE\",\"decisionMode\":\"MANUAL\",\"minimumAssessments\":3}",5));
        var saved=service.update(auth,11,70L,501L,new SavePolicyRequest(policy(),4L));
        assertEquals(5,saved.draftRevision());
        assertEquals(3,saved.policy().minimumAssessments());
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog log)->"RECRUITMENT_DECISION_POLICY_UPDATED".equals(log.getAction())));
    }

    @Test void rejectedInvalidConfigurationNeverPersists() {
        manager();
        var invalid = new RecruitmentDecisionPolicy(
            ReviewMode.INDIVIDUAL,DecisionMode.AUTOMATIC_VOTE,1);
        assertEquals(HttpStatus.BAD_REQUEST,assertThrows(ResponseStatusException.class,
            ()->service.update(auth,11,70L,501L,
                new SavePolicyRequest(invalid,0L))).getStatusCode());
        verifyNoInteractions(repository,audits);
    }

    @Test void concurrentRevisionConflictDoesNotAudit() {
        manager();
        when(repository.update(eq(11),eq(70L),eq(501L),eq(1L),anyString()))
            .thenThrow(new OptimisticLockingFailureException("stale"));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.update(auth,11,70L,501L,
                new SavePolicyRequest(policy(),1L))).getStatusCode());
        verifyNoInteractions(audits);
    }
}
