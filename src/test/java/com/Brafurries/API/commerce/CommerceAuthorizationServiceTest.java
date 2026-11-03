package com.Brafurries.API.commerce;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.user.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommerceAuthorizationServiceTest {
    @Mock UserRepository users;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;

    private User user;

    @BeforeEach
    void setup() {
        user = new User();
        user.setId(35);
        user.setEmail("member@example.com");
        lenient().when(auth.isAuthenticated()).thenReturn(true);
        lenient().when(auth.getName()).thenReturn("MEMBER@EXAMPLE.COM");
        lenient().when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
    }

    @Test
    void configuredOwnerHasFullCommerceAccessWithoutTeamOrAdminGrant() {
        user.setId(3);
        var service = new CommerceAuthorizationService(users, jdbc, 3, 99);

        var result = service.resolve(auth);
        assertTrue(result.access().enabled());
        assertTrue(result.access().owner());
        assertEquals(CommercePermission.values().length, result.access().permissions().size());
        assertTrue(service.require(auth, CommercePermission.PAYMENTS_RECORD).access().owner());
        assertEquals(3, service.requireOwner(auth).getId());
        verifyNoInteractions(jdbc);
    }

    @Test
    void missingTrustedOwnerOrCommunityConfigurationFailsClosed() {
        var noOwner = new CommerceAuthorizationService(users, jdbc, 0, 99);
        var wrongCommunity = new CommerceAuthorizationService(users, jdbc, 3, 0);

        assertFalse(noOwner.resolve(auth).access().enabled());
        assertFalse(wrongCommunity.resolve(auth).access().enabled());
        assertTrue(noOwner.resolve(auth).access().permissions().isEmpty());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> noOwner.requireOwner(auth)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void noPersonalApprovalOrRoleIntersectionMeansNoAccessEvenForCommunityAdmin() {
        // Authentication roles/authorities are intentionally ignored entirely.
        var service = new CommerceAuthorizationService(users, jdbc, 3, 99);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(99), eq(35)))
            .thenReturn(List.of());

        assertFalse(service.resolve(auth).access().has(CommercePermission.SALES_MANAGE));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.require(auth, CommercePermission.FINANCE_VIEW)).getStatusCode());
        verify(jdbc, times(2)).queryForList(anyString(), eq(String.class), eq(99), eq(35));
    }

    @Test
    void effectivePermissionsAreOnlyTheSqlIntersectionAndNeverImplicitAll() {
        var service = new CommerceAuthorizationService(users, jdbc, 3, 99);
        when(jdbc.queryForList(anyString(), eq(String.class), eq(99), eq(35)))
            .thenReturn(List.of("PRODUCTS_MANAGE", "SALES_MANAGE"));

        var result = service.resolve(auth);
        assertFalse(result.access().owner());
        assertEquals(2, result.access().permissions().size());
        assertTrue(result.access().has(CommercePermission.PRODUCTS_MANAGE));
        assertFalse(result.access().has(CommercePermission.FINANCE_VIEW));
        assertFalse(result.access().has(CommercePermission.PAYMENTS_RECORD));
        verify(jdbc).queryForList(argThat(sql ->
            sql.contains("commerce_authorized_users")
                && sql.contains("commerce_user_permissions")
                && sql.contains("commerce_role_permissions")
                && sql.contains("community_team_role_assignments")
                && sql.contains("role.community_id = ?")
                && sql.contains("role.active = 1")
                && sql.contains("membership.is_present = 1")
        ), eq(String.class), eq(99), eq(35));
    }

    @Test
    void unauthenticatedCallerIsRejectedBeforeAnyDatabaseGrantQuery() {
        var service = new CommerceAuthorizationService(users, jdbc, 3, 99);
        when(auth.isAuthenticated()).thenReturn(false);
        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ResponseStatusException.class,
            () -> service.resolve(auth)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void unknownCommercePermissionCodeIsBadRequest() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ResponseStatusException.class,
            () -> CommercePermission.parse("COMMUNITY_ADMIN")).getStatusCode());
    }
}
