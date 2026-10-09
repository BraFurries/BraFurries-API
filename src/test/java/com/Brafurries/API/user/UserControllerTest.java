package com.Brafurries.API.user;

import com.Brafurries.API.user.dto.MemberProfileUpdateDtos.UpdateMemberProfileRequest;
import com.Brafurries.API.user.dto.MemberProfileUpdateDtos.UpdateMemberProfileResponse;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserControllerTest {

    @Test
    void updateMyProfileDelegatesOnlyTheAuthenticatedPrincipalAndDisplayName() {
        MemberProfileUpdateService updateService = mock(MemberProfileUpdateService.class);
        UserController controller = new UserController(
            mock(UserProfileService.class),
            mock(UserPermissionService.class),
            mock(UserGeneralInfoService.class),
            mock(UserProfileImageService.class),
            mock(UserDashboardService.class),
            mock(ManagedServersService.class),
            mock(MemberProfileService.class),
            mock(UserCommunityContextService.class),
            mock(UserModerationHistoryService.class),
            updateService
        );
        Authentication authentication = mock(Authentication.class);
        UpdateMemberProfileRequest request = new UpdateMemberProfileRequest("Novo Nome");
        UpdateMemberProfileResponse expected = new UpdateMemberProfileResponse(42, "Novo Nome");
        when(authentication.getName()).thenReturn("member@example.com");
        when(updateService.updateDisplayName("member@example.com", request)).thenReturn(expected);

        UpdateMemberProfileResponse response = controller.updateMyProfile(authentication, request);

        assertSame(expected, response);
        verify(updateService).updateDisplayName("member@example.com", request);
    }
}
