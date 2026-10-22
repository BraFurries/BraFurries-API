package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentCandidateDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.user.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class RecruitmentCandidateServiceTest {
    @Mock CommunityAuthorizationService authorization;
    @Mock RecruitmentCandidateRepository candidateRepo;
    @Mock RecruitmentPublicationRepository publicationRepo;
    @Mock UserCommunityStatusRepository memberships;
    @Mock CommunityNetworkAvailabilityService network;
    @Mock CommunityAuditLogRepository audits;
    @Mock Authentication auth;

    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    RecruitmentCandidateService service;
    Community community;
    User user;
    UserCommunityStatus membership;

    @BeforeEach
    void init() {
        service = new RecruitmentCandidateService(
            new RecruitmentFeatureGate(true,11),authorization,candidateRepo,publicationRepo,
            new RecruitmentFormValidator(),new RecruitmentCandidateAnswerValidator(),
            memberships,new CommunityMembershipStatusResolver(),network,audits,json
        );
        community = new Community();community.setId(11);
        user = new User();user.setId(99);
        membership = new UserCommunityStatus();
        membership.setUser(user);membership.setCommunity(community);
        membership.setIsPresent(true);membership.setBanned(false);
        membership.setMemberSince(LocalDateTime.now(ZoneOffset.UTC).minusDays(100));
        membership.setIsVip(false);membership.setIsPartner(false);
    }

    private void activeMember() {
        when(authorization.resolve(auth,11)).thenReturn(context(true));
    }

    private CommunityAuthorizationService.CommunityAccessContext context(boolean activeMember) {
        return new CommunityAuthorizationService.CommunityAccessContext(
            community,user,false,false,activeMember,Set.of(),List.of(),Map.of(),Set.of()
        );
    }

    private RecruitmentCandidateRepository.CandidateSelection process(boolean requiresVip) {
        return new RecruitmentCandidateRepository.CandidateSelection(
            70L,"Staff","Recrutamento",java.time.Instant.now().plusSeconds(86400),
            71L,"{\"minMembershipDays\":30,\"requireVip\":"+requiresVip+
            ",\"requirePartner\":false}",
            "{\"text\":\"Confirmo ciência das regras do processo seletivo BraFurries.\"}",
            72L,"{\"questions\":[{\"id\":\"why\",\"label\":\"Por quê?\","
                +"\"type\":\"SHORT_TEXT\",\"required\":true}]}"
        );
    }

    private ApplyRequest request(String termsHash) {
        JsonNode value = json.getNodeFactory().textNode("Quero ajudar");
        return new ApplyRequest(termsHash,Map.of("why",value));
    }

    @Test void tenantGateBlocksBeforeAuthenticationOrRepository() {
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.list(auth,12)).getStatusCode());
        verifyNoInteractions(authorization,candidateRepo);
    }

    @Test void nonMemberOwnerCannotApply() {
        when(authorization.resolve(auth,11)).thenReturn(context(false));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.form(auth,11,70L)).getStatusCode());
        verifyNoInteractions(candidateRepo);
    }

    @Test void unpublishedOrClosedProcessIsUnavailable() {
        activeMember();
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.form(auth,11,70L)).getStatusCode());
    }

    @Test void publicFormDoesNotReturnAnswersKeysAndReportsVipRequirement() {
        activeMember();
        when(candidateRepo.publishedSelection(11,70L,true,false)).thenReturn(process(true));
        when(memberships.findByUserAndCommunity(user,community)).thenReturn(Optional.of(membership));
        var form = service.form(auth,11,70L);
        assertEquals(1,form.form().questions().size());
        assertFalse(form.eligible());
        assertTrue(form.eligibilityBlockers().contains("Membro VIP obrigatório"));
        String encoded;
        try {encoded=json.writeValueAsString(form);}catch(Exception error){throw new IllegalStateException(error);}
        assertFalse(encoded.contains("correctAnswers"));
        assertFalse(encoded.contains("staffForm"));
        assertFalse(encoded.contains("points"));
    }

    @Test void staleTermsCannotBeAcceptedOrPersisted() {
        activeMember();
        when(memberships.findAllForAssignmentByUserIdAndCommunityId(99,11))
            .thenReturn(List.of(membership));
        when(candidateRepo.publishedSelection(11,70L,true,true)).thenReturn(process(false));
        assertEquals(HttpStatus.CONFLICT,assertThrows(ResponseStatusException.class,
            ()->service.apply(auth,11,70L,request("a".repeat(64)))).getStatusCode());
        verify(candidateRepo,never()).submit(anyInt(),anyLong(),anyLong(),anyLong(),
            anyLong(),anyList(),anyString(),anyString());
    }

    @Test void bannedCandidateRejectedOnSubmissionRecheckEvenIfContextWasActive() {
        activeMember();
        membership.setBanned(true);
        when(memberships.findAllForAssignmentByUserIdAndCommunityId(99,11))
            .thenReturn(List.of(membership));
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.apply(auth,11,70L,request("a".repeat(64)))).getStatusCode());
        verify(candidateRepo,never()).publishedSelection(anyInt(),anyLong(),anyBoolean(),anyBoolean());
    }

    @Test void publisherCapabilityIsRequiredBeforeOpeningEnrollment() {
        when(authorization.requireCapability(auth,11,
            CommunityCapability.RECRUITMENT_PUBLISH_PROCESSES))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->service.open(auth,11,70L,0L,java.time.Instant.now().plusSeconds(7200)))
            .getStatusCode());
        verifyNoInteractions(candidateRepo,publicationRepo);
    }
}
