package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.GuildManagementDtos.*;
import static com.Brafurries.API.user.dto.ModerationAccessDtos.*;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class GuildManagementControllerModerationCompatibilityTest {

    @Test
    void legacyStaffWriteDelegatesToAuditedModerationControlPlane() {
        GuildManagementService legacyService = mock(GuildManagementService.class);
        LogConfigurationService logs = mock(LogConfigurationService.class);
        ModerationAccessService moderationAccess = mock(ModerationAccessService.class);
        Authentication authentication = mock(Authentication.class);

        GuildManagementController controller = new GuildManagementController(
            legacyService,
            logs,
            moderationAccess
        );

        StaffRolesRequest request = new StaffRolesRequest(List.of("10"));
        StaffRolesResponse staff = new StaffRolesResponse(
            List.of("10"),
            List.of()
        );
        ModerationAccessResponse aggregate = new ModerationAccessResponse(
            "123",
            staff,
            new CollaborativeModerationResponse(
                false,
                null,
                3,
                ModerationAccessService.PARTICIPANT_MODE,
                List.of("10")
            )
        );
        when(moderationAccess.updateStaff(authentication, "123", request))
            .thenReturn(aggregate);

        StaffRolesResponse response = controller.updateStaffRoles(
            authentication,
            "123",
            request
        );

        assertSame(staff, response);
        verify(moderationAccess).updateStaff(authentication, "123", request);
        verify(legacyService, never()).updateStaffRoles(any(), anyString(), any());
    }
}
