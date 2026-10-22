package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentPublicationDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.user.CommunityAuthorizationService;
import com.Brafurries.API.user.CommunityCapability;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class RecruitmentPublicationServiceTest {
    @Mock RecruitmentPublicationRepository drafts;
    @Mock CommunityAuthorizationService authorization;
    @Mock CommunityAuditLogRepository audits;
    @Mock Authentication auth;

    RecruitmentPublicationService service;
    private final int tenant = 11;
    private final long processId = 50L;

    @BeforeEach
    void setup() {
        service = new RecruitmentPublicationService(
            new RecruitmentFeatureGate(true, tenant),
            authorization, drafts, new RecruitmentFormValidator(),
            audits, new ObjectMapper()
        );
    }

    private CommunityAuthorizationService.CommunityAccessContext actor(
        CommunityCapability capability
    ) {
        var community = new Community();
        community.setId(tenant);
        var user = new User();
        user.setId(99);
        return new CommunityAuthorizationService.CommunityAccessContext(
            community, user, false, false, true,
            Set.of(capability), List.of(), Map.of(), Set.of()
        );
    }

    private void publisher() {
        when(authorization.requireCapability(
            auth, tenant, CommunityCapability.RECRUITMENT_PUBLISH_PROCESSES
        )).thenReturn(actor(CommunityCapability.RECRUITMENT_PUBLISH_PROCESSES));
    }

    private void manager() {
        when(authorization.requireCapability(
            auth, tenant, CommunityCapability.RECRUITMENT_MANAGE_PROCESSES
        )).thenReturn(actor(CommunityCapability.RECRUITMENT_MANAGE_PROCESSES));
    }

    private void validDraft() {
        when(drafts.drafts(tenant, processId)).thenReturn(List.of(
            new RecruitmentPublicationRepository.DraftVersion(
                70L, "DRAFT",
                "{\"minMembershipDays\":30,\"requireVip\":false,\"requirePartner\":false}",
                "{\"text\":\"Eu li e aceito os termos de inscrição na seleção BraFurries.\"}"
            )
        ));
        when(drafts.stages(tenant, processId, 70L)).thenReturn(List.of(
            new RecruitmentPublicationRepository.DraftStage(
                80L, 1, "APPLICATION",
                "{\"questions\":[{\"id\":\"why\",\"label\":\"Motivação\",\"type\":\"SHORT_TEXT\",\"required\":true}]}",
                null
            ),
            new RecruitmentPublicationRepository.DraftStage(
                81L, 2, "DECISION", null, null
            )
        ));
    }

    @Test
    void wrongCommunityRejectedBeforeAnyRepositoryAccess() {
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class,
                () -> service.publish(auth, 12, processId, new PublishRequest(0L)))
                .getStatusCode());
        verifyNoInteractions(authorization, drafts, audits);
    }

    @Test
    void publishingRequiresSeparateCapabilityNotJustDraftManagement() {
        when(authorization.requireCapability(
            auth, tenant, CommunityCapability.RECRUITMENT_PUBLISH_PROCESSES
        )).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                () -> service.publish(auth, tenant, processId, new PublishRequest(0L)))
                .getStatusCode());
        verifyNoInteractions(drafts, audits);
    }

    @Test
    void preflightShowsMissingEligibilityAndStagesWithoutPublishing() {
        when(authorization.resolve(auth, tenant)).thenReturn(
            actor(CommunityCapability.RECRUITMENT_MANAGE_PROCESSES)
        );
        when(drafts.findProcess(tenant, processId)).thenReturn(
            new RecruitmentPublicationRepository.DraftProcess(processId, 0L, "DRAFT")
        );
        when(drafts.drafts(tenant, processId)).thenReturn(List.of(
            new RecruitmentPublicationRepository.DraftVersion(70L, "DRAFT", null)
        ));
        var check = service.preview(auth, tenant, processId);
        assertFalse(check.ready());
        assertEquals(3, check.blockers().size());
        assertEquals(0, check.stageCount());
        verify(drafts, never()).publish(
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @Test
    void incompleteDraftCannotBePublished() {
        publisher();
        when(drafts.lockProcess(tenant, processId)).thenReturn(
            new RecruitmentPublicationRepository.DraftProcess(processId, 0L, "DRAFT")
        );
        when(drafts.drafts(tenant, processId)).thenReturn(List.of(
            new RecruitmentPublicationRepository.DraftVersion(70L, "DRAFT", null)
        ));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                () -> service.publish(auth, tenant, processId, new PublishRequest(0L)))
                .getStatusCode());
        verify(drafts, never()).publish(
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()
        );
        verifyNoInteractions(audits);
    }

    @Test
    void malformedAutomaticExamBlocksPublication() {
        publisher();
        when(drafts.lockProcess(tenant, processId)).thenReturn(
            new RecruitmentPublicationRepository.DraftProcess(processId, 0L, "DRAFT")
        );
        when(drafts.drafts(tenant, processId)).thenReturn(List.of(
            new RecruitmentPublicationRepository.DraftVersion(
                70L, "DRAFT",
                "{\"minMembershipDays\":0,\"requireVip\":false,\"requirePartner\":false}"
            )
        ));
        when(drafts.stages(tenant, processId, 70L)).thenReturn(List.of(
            new RecruitmentPublicationRepository.DraftStage(
                80L, 1, "AUTO_EVALUATION", null, null
            )
        ));
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                () -> service.publish(auth, tenant, processId, new PublishRequest(0L)))
                .getStatusCode());
        verifyNoInteractions(audits);
    }

    @Test
    void publisherFreezesVersionButNeverOpensIntake() {
        publisher();
        when(drafts.lockProcess(tenant, processId)).thenReturn(
            new RecruitmentPublicationRepository.DraftProcess(processId, 3L, "DRAFT")
        );
        validDraft();
        when(drafts.publish(tenant, processId, 70L, 3L)).thenReturn(true);
        var result = service.publish(auth, tenant, processId, new PublishRequest(3L));
        assertEquals("PUBLISHED", result.versionStatus());
        assertEquals("CLOSED", result.enrollmentStatus());
        assertEquals("PRIVATE", result.visibility());
        assertEquals(4L, result.draftRevision());
        verify(audits).save(org.mockito.ArgumentMatchers.argThat(
            (CommunityAuditLog event) -> event.getAction().equals("RECRUITMENT_PROCESS_PUBLISHED")
                && event.getTargetId().equals("50")
        ));
    }

    @Test
    void publishedDraftMustHaveAnApplicationFormInFirstStage() {
        publisher();
        when(drafts.lockProcess(tenant, processId)).thenReturn(
            new RecruitmentPublicationRepository.DraftProcess(processId, 0L, "DRAFT")
        );
        when(drafts.drafts(tenant, processId)).thenReturn(List.of(
            new RecruitmentPublicationRepository.DraftVersion(
                70L, "DRAFT",
                "{\"minMembershipDays\":0,\"requireVip\":false,\"requirePartner\":false}",
                "{\"text\":\"Aceito e conheço as condições da seleção BraFurries.\"}"
            )
        ));
        when(drafts.stages(tenant, processId, 70L)).thenReturn(List.of(
            new RecruitmentPublicationRepository.DraftStage(80L, 1, "ANALYSIS", null, null)
        ));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> service.publish(auth, tenant, processId, new PublishRequest(0L))).getStatusCode());
        verifyNoInteractions(audits);
    }

    @Test
    void stalePublisherNeverPersistsPublication() {
        publisher();
        when(drafts.lockProcess(tenant, processId)).thenReturn(
            new RecruitmentPublicationRepository.DraftProcess(processId, 9L, "DRAFT")
        );
        assertEquals(HttpStatus.CONFLICT,
            assertThrows(ResponseStatusException.class,
                () -> service.publish(auth, tenant, processId, new PublishRequest(8L)))
                .getStatusCode());
        verify(drafts, never()).publish(
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong(),
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()
        );
        verifyNoInteractions(audits);
    }

    @Test
    void eligibilityIsConfigurableOnlyByManagerAndBumpsRevision() {
        manager();
        when(drafts.lockProcess(tenant, processId)).thenReturn(
            new RecruitmentPublicationRepository.DraftProcess(processId, 0L, "DRAFT")
        );
        when(drafts.drafts(tenant, processId)).thenReturn(List.of(
            new RecruitmentPublicationRepository.DraftVersion(70L, "DRAFT", null)
        ));
        when(drafts.updateEligibility(
            org.mockito.ArgumentMatchers.eq(tenant), org.mockito.ArgumentMatchers.eq(processId),
            org.mockito.ArgumentMatchers.eq(70L), org.mockito.ArgumentMatchers.eq(0L),
            org.mockito.ArgumentMatchers.contains("\"minMembershipDays\":90")
        )).thenReturn(true);
        when(drafts.findProcess(tenant, processId)).thenReturn(
            new RecruitmentPublicationRepository.DraftProcess(processId, 1L, "DRAFT")
        );
        var check = service.configureEligibility(auth, tenant, processId,
            new UpdateEligibilityRequest(0L, new EligibilityRules(90, false, false)));
        assertEquals(1L, check.draftRevision());
        verify(audits).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsInvalidEligibilityRangeBeforeDatabaseMutation() {
        manager();
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                () -> service.configureEligibility(auth, tenant, processId,
                    new UpdateEligibilityRequest(0L, new EligibilityRules(5000, false, false))))
                .getStatusCode());
        verifyNoInteractions(drafts, audits);
    }
}
