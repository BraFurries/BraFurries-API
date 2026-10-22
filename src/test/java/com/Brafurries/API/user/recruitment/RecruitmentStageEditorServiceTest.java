package com.Brafurries.API.user.recruitment;

import static com.Brafurries.API.user.recruitment.RecruitmentDraftDtos.*;
import static com.Brafurries.API.user.recruitment.RecruitmentStageEditorDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
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
class RecruitmentStageEditorServiceTest {
    @Mock CommunityAuthorizationService authorization;
    @Mock RecruitmentDraftRepository processes;
    @Mock RecruitmentStageEditorRepository stages;
    @Mock CommunityAuditLogRepository audits;
    @Mock Authentication auth;

    RecruitmentStageEditorService service;

    @BeforeEach
    void setUp() {
        service = new RecruitmentStageEditorService(
            new RecruitmentFeatureGate(true, 11), authorization, processes, stages,
            new RecruitmentFormValidator(), audits
        );
    }

    private void granted() {
        Community community = new Community();
        community.setId(11);
        User user = new User();
        user.setId(99);
        var actor = new CommunityAuthorizationService.CommunityAccessContext(
            community, user, false, false, true,
            Set.of(CommunityCapability.RECRUITMENT_MANAGE_PROCESSES),
            List.of(), Map.of(), Set.of()
        );
        when(authorization.requireCapability(
            auth, 11, CommunityCapability.RECRUITMENT_MANAGE_PROCESSES
        )).thenReturn(actor);
    }

    @Test void rejectsWrongCommunityBeforeReadingHiddenGabarito() {
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class,
                () -> service.detail(auth, 12, 100L, 200L)).getStatusCode());
        verifyNoInteractions(authorization, stages, processes, audits);
    }

    @Test void refusesMissingCapabilityBeforeDatabaseAccess() {
        when(authorization.requireCapability(auth, 11,
            CommunityCapability.RECRUITMENT_MANAGE_PROCESSES))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,
            assertThrows(ResponseStatusException.class,
                () -> service.detail(auth, 11, 100L, 200L)).getStatusCode());
        verifyNoInteractions(stages, processes, audits);
    }

    @Test void updateStageValidatesTwoIndependentFormsAndAudits() {
        granted();
        var request = new SaveStageRequest(" Interna ", " Pública ", StageType.ANALYSIS,
            null, null);
        var result = new StageDefinitionResponse(
            200L, 100L, 300L, 1, "Interna", "Pública", StageType.ANALYSIS,
            null, null
        );
        when(stages.save(eq(11), eq(100L), eq(200L), any())).thenReturn(result);
        assertEquals("Pública", service.update(auth, 11, 100L, 200L, request).publicName());
        verify(stages).save(eq(11), eq(100L), eq(200L), argThat(v ->
            v.internalName().equals("Interna") && v.publicName().equals("Pública")
        ));
        verify(audits).save(argThat(log ->
            log.getAction().equals("RECRUITMENT_STAGE_UPDATED")
                && log.getTargetId().equals("200")
        ));
    }

    @Test void invalidAutoFormCannotBeStored() {
        granted();
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                () -> service.update(auth, 11, 100L, 200L,
                    new SaveStageRequest("Exame", "Exame", StageType.AUTO_EVALUATION,
                        null, null))).getStatusCode());
        verifyNoInteractions(stages, audits);
    }

    @Test void cannotPublishUnapprovedPermutation() {
        granted();
        doThrow(new IllegalArgumentException("Duplicated IDs"))
            .when(stages).reorder(11, 100L, List.of(1L, 1L));
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                () -> service.reorder(auth, 11, 100L,
                    new ReorderStagesRequest(List.of(1L, 1L)))).getStatusCode());
        verifyNoInteractions(audits);
    }

    @Test void successfulReorderIsAudited() {
        granted();
        when(stages.reorder(11, 100L, List.of(2L, 1L))).thenReturn(true);
        when(processes.stages(11, 100L)).thenReturn(List.of());
        assertEquals(List.of(), service.reorder(auth, 11, 100L,
            new ReorderStagesRequest(List.of(2L, 1L))));
        verify(audits).save(argThat(log ->
            log.getAction().equals("RECRUITMENT_STAGES_REORDERED")
        ));
    }
}
