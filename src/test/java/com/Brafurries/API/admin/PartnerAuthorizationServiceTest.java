package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Verifies the server policy, not the sidebar's cosmetic visibility. */
class PartnerAuthorizationServiceTest {
    private final PlatformAuthorizationService authorization = mock(PlatformAuthorizationService.class);
    private final PartnerAuthorizationService service = new PartnerAuthorizationService(authorization);

    @Test
    void anonymousAndMissingAuthenticationAreDenied() {
        assertFalse(service.canManage(null));
        Authentication anonymous = mock(Authentication.class);
        when(anonymous.isAuthenticated()).thenReturn(false);
        assertFalse(service.canManage(anonymous));
        verifyNoInteractions(authorization);
    }

    @Test
    void legacyGlobalAdminRetainsAccessWithoutCommerceGrant() {
        Authentication admin = auth("ROLE_ADMIN");
        assertTrue(service.canManage(admin));
        verifyNoInteractions(authorization);
    }

    @Test
    void delegatedPartnerManagerNeedsEffectiveServerSideGrant() {
        Authentication user = auth("ROLE_USER");
        when(authorization.resolve(user)).thenReturn(actor(false, PlatformPermission.PARTNERS_MANAGE));
        assertTrue(service.canManage(user));
        verify(authorization).resolve(user);
    }

    @Test
    void financePermissionDoesNotGrantPartnerManagement() {
        Authentication user = auth("ROLE_USER");
        when(authorization.resolve(user)).thenReturn(actor(false, PlatformPermission.FINANCE_VIEW));
        assertFalse(service.canManage(user));
    }

    @Test
    void ordinaryCommunityPrivilegeDoesNotGrantPartnerManagement() {
        Authentication user = auth("ROLE_COMMUNITY_ADMIN");
        when(authorization.resolve(user)).thenReturn(actor(false));
        assertFalse(service.canManage(user));
    }

    @Test
    void configuredOwnerCanManageWithoutSeparateGrant() {
        Authentication owner = auth("ROLE_USER");
        when(authorization.resolve(owner)).thenReturn(actor(true));
        assertTrue(service.canManage(owner));
    }

    private Authentication auth(String authority) {
        Authentication auth = mock(Authentication.class);
        when(auth.isAuthenticated()).thenReturn(true);
        when(auth.getAuthorities()).thenAnswer(invocation ->
            List.of(new SimpleGrantedAuthority(authority)));
        return auth;
    }

    private PlatformAuthorizationService.Actor actor(boolean owner, PlatformPermission... permissions) {
        return new PlatformAuthorizationService.Actor(null,
            new PlatformAuthorizationService.Access(owner, true, Set.of(permissions)));
    }
}
