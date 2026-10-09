package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminDtos.AdminUserItem;
import com.Brafurries.API.admin.dto.AdminDtos.UpdateUserRoleRequest;
import com.Brafurries.API.admin.dto.AdminUserProfileDtos.AdminUserProfileResponse;
import com.Brafurries.API.admin.dto.AdminUserReliabilityDtos.UserReliabilityResponse;
import com.Brafurries.API.admin.dto.UserIdentityDtos.ModerationHistoryResponse;
import com.Brafurries.API.admin.dto.UserIdentityDtos.UserIdentityResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AdminUserIdentityAuthorizationTest.Config.class)
class AdminUserIdentityAuthorizationTest {

    @jakarta.annotation.Resource AdminUserController controller;
    @jakarta.annotation.Resource UserIdentityLinkService identityLinks;
    @jakarta.annotation.Resource AdminUserProfileService profile;
    @jakarta.annotation.Resource UserIdentityModerationService moderation;
    @jakarta.annotation.Resource AdminUserReliabilityService reliability;
    @jakarta.annotation.Resource AdminUsersService users;
    @jakarta.annotation.Resource AdminUserCommunityContextService context;
    @jakarta.annotation.Resource AdminDiscordMemberService discordMember;
    @jakarta.annotation.Resource AdminDiscordIdentityController discordIdentityController;
    @jakarta.annotation.Resource AdminDiscordIdentityService discordIdentity;

    @Test
    @WithMockUser(roles = "USER")
    void commonUserCannotReadAdministrativeIdentity() {
        assertThrows(AccessDeniedException.class, () -> controller.getIdentity(10));
        assertThrows(AccessDeniedException.class, () -> controller.getModerationHistory(10, 20));
        assertThrows(AccessDeniedException.class, () -> controller.getUserProfile(10, 1, 20));
        assertThrows(AccessDeniedException.class, () -> controller.getUserReliabilityById(10));
        assertThrows(AccessDeniedException.class, () -> controller.getUserCommunityContext(10, 20));
        assertThrows(AccessDeniedException.class, () -> controller.getDiscordMember(10, 20));
        assertThrows(AccessDeniedException.class, () -> controller.updateUserRole(10, new UpdateUserRoleRequest("moderator")));
        assertThrows(
            AccessDeniedException.class,
            () -> discordIdentityController.preview(10, "123456789012345678")
        );
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void platformAdminCanReadAdministrativeIdentity() {
        UserIdentityResponse expected = mock(UserIdentityResponse.class);
        when(identityLinks.getIdentity(10)).thenReturn(expected);
        assertSame(expected, controller.getIdentity(10));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void platformAdminCanReadTheDossierAndUpdateRole() {
        AdminUserProfileResponse expectedProfile = mock(AdminUserProfileResponse.class);
        ModerationHistoryResponse expectedHistory = mock(ModerationHistoryResponse.class);
        UserReliabilityResponse expectedReliability = mock(UserReliabilityResponse.class);
        AdminUserItem expectedRole = mock(AdminUserItem.class);
        UpdateUserRoleRequest request = new UpdateUserRoleRequest("moderator");
        when(profile.getProfile(10, 1, 20)).thenReturn(expectedProfile);
        when(moderation.getHistory(10, 20)).thenReturn(expectedHistory);
        when(reliability.getUserReliability(10, null)).thenReturn(expectedReliability);
        when(users.updateRole(10, request)).thenReturn(expectedRole);

        assertSame(expectedProfile, controller.getUserProfile(10, 1, 20));
        assertSame(expectedHistory, controller.getModerationHistory(10, 20));
        assertSame(expectedReliability, controller.getUserReliabilityById(10));
        assertSame(expectedRole, controller.updateUserRole(10, request));
    }

    @Configuration
    @EnableMethodSecurity
    static class Config {
        @Bean AdminUserReliabilityService reliability() { return mock(AdminUserReliabilityService.class); }
        @Bean AdminUsersService users() { return mock(AdminUsersService.class); }
        @Bean AdminUserProfileService profile() { return mock(AdminUserProfileService.class); }
        @Bean UserIdentityLinkService identityLinks() { return mock(UserIdentityLinkService.class); }
        @Bean UserIdentityModerationService moderation() { return mock(UserIdentityModerationService.class); }
        @Bean AdminUserCommunityContextService context() { return mock(AdminUserCommunityContextService.class); }
        @Bean AdminDiscordMemberService discordMember() { return mock(AdminDiscordMemberService.class); }
        @Bean AdminDiscordIdentityService discordIdentity() { return mock(AdminDiscordIdentityService.class); }
        @Bean AdminDiscordIdentityController discordIdentityController(AdminDiscordIdentityService service) {
            return new AdminDiscordIdentityController(service);
        }
        @Bean AdminUserController controller(
            AdminUserReliabilityService reliability,
            AdminUsersService users,
            AdminUserProfileService profile,
            UserIdentityLinkService identityLinks,
            UserIdentityModerationService moderation,
            AdminUserCommunityContextService context,
            AdminDiscordMemberService discordMember
        ) {
            return new AdminUserController(reliability, users, profile, identityLinks, moderation, context, discordMember);
        }
    }
}
