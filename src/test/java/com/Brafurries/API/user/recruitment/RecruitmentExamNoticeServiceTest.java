package com.Brafurries.API.user.recruitment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
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
class RecruitmentExamNoticeServiceTest {
    @Mock CommunityAuthorizationService authz;
    @Mock RecruitmentStageDecisionRepository decisions;
    @Mock RecruitmentExamNoticeRepository repository;
    @Mock Authentication authentication;
    RecruitmentExamNoticeService service;

    @BeforeEach void setup() {
        service=new RecruitmentExamNoticeService(
            new RecruitmentFeatureGate(true,11),authz,decisions,repository);
    }

    private CommunityAuthorizationService.CommunityAccessContext access(int id,
        boolean active,boolean manager) {
        var community=new Community();community.setId(11);
        var user=new User();user.setId(id);
        return new CommunityAuthorizationService.CommunityAccessContext(
            community,user,false,false,active,
            manager?Set.of(CommunityCapability.RECRUITMENT_MANAGE_PROCESSES):Set.of(),
            List.of(),Map.of(),Set.of());
    }

    @Test void otherCommunityFailsBeforeCheckingMembershipOrRepository() {
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.candidate(authentication,12,100L)).getStatusCode());
        verifyNoInteractions(authz,decisions,repository);
    }

    @Test void candidateCannotViewOrMarkAnotherPersonsNotices() {
        when(authz.resolve(authentication,11)).thenReturn(access(42,true,false));
        when(decisions.candidateId(11,100L)).thenReturn(99);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.candidate(authentication,11,100L)).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.candidateRead(authentication,11,100L,200L)).getStatusCode());
        verifyNoInteractions(repository);
    }

    @Test void candidateCanReadOnlyScopedNotice() {
        when(authz.resolve(authentication,11)).thenReturn(access(99,true,false));
        when(decisions.candidateId(11,100L)).thenReturn(99);
        when(repository.candidateNoticeExists(11,100,200,99)).thenReturn(true);
        service.candidateRead(authentication,11,100L,200L);
        verify(repository).markRead(11,200,99);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.candidateRead(authentication,11,100L,201L)).getStatusCode());
        verify(repository,never()).markRead(11,201,99);
    }

    @Test void staffRequiresActiveManagementCapability() {
        when(authz.requireCapability(authentication,11,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES))
            .thenReturn(access(77,false,true));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.staff(authentication,11,300L)).getStatusCode());
        verifyNoInteractions(repository);
    }

    @Test void staffReadIsLimitedToProcessAndOwnAcknowledgement() {
        when(authz.requireCapability(authentication,11,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES))
            .thenReturn(access(77,true,true));
        when(repository.staffNoticeExists(11,300,501,77)).thenReturn(true);
        service.staffRead(authentication,11,300L,501L);
        verify(repository).markRead(11,501,77);
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.staffRead(authentication,11,301L,501L)).getStatusCode());
        verify(repository,never()).markRead(11,501,78);
    }
}
