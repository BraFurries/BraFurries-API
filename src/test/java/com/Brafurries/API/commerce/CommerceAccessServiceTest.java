package com.Brafurries.API.commerce;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.user.User;
import java.util.List;
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
class CommerceAccessServiceTest {
    @Mock CommerceAuthorizationService authorization;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;

    private CommerceAccessService service;
    private User owner;

    @BeforeEach
    void setUp() {
        service = new CommerceAccessService(authorization, jdbc);
        owner = new User();
        owner.setId(7);
        lenient().when(authorization.requireOwner(auth)).thenReturn(owner);
    }

    @Test
    void ownerApprovalIsIdempotentAndAlwaysAuditedWhenNew() {
        when(jdbc.queryForList(startsWith("SELECT id FROM users"), eq(Integer.class), eq(35)))
            .thenReturn(List.of(35));
        when(jdbc.queryForObject(contains("commerce_authorized_users"), eq(Integer.class), eq(35)))
            .thenReturn(0);

        service.approvePerson(auth, 35);

        verify(jdbc).update(contains("INSERT INTO commerce_authorized_users"), eq(35), eq(7));
        verify(jdbc).update(contains("INSERT INTO commerce_access_audit"),
            eq(7), eq("PERSON_APPROVED"), eq(35), isNull(), isNull());
    }

    @Test
    void repeatedApprovalDoesNotDuplicateAudit() {
        when(jdbc.queryForList(startsWith("SELECT id FROM users"), eq(Integer.class), eq(35)))
            .thenReturn(List.of(35));
        when(jdbc.queryForObject(contains("commerce_authorized_users"), eq(Integer.class), eq(35)))
            .thenReturn(1);

        service.approvePerson(auth, 35);

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void cannotGrantPersonPermissionWithoutExplicitOwnerApproval() {
        when(jdbc.queryForList(startsWith("SELECT id FROM users"), eq(Integer.class), eq(35)))
            .thenReturn(List.of(35));
        when(jdbc.queryForObject(contains("commerce_authorized_users"), eq(Integer.class), eq(35)))
            .thenReturn(0);

        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> service.grantPersonPermission(auth, 35, "FINANCE_VIEW")).getStatusCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void refusesPermissionsOnTeamRolesOutsideConfiguredBrafurries() {
        when(authorization.officialCommunityId()).thenReturn(99);
        when(jdbc.queryForList(contains("SELECT id FROM community_team_roles"), eq(Integer.class),
            eq(66), eq(99))).thenReturn(List.of());

        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException.class,
            () -> service.grantRolePermission(auth, 66, "PAYMENTS_RECORD")).getStatusCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void grantsToAuthorizedTeamRolesWriteAuditWithoutTouchingCommunityGrants() {
        when(authorization.officialCommunityId()).thenReturn(99);
        when(jdbc.queryForList(contains("SELECT id FROM community_team_roles"), eq(Integer.class),
            eq(66), eq(99))).thenReturn(List.of(66));
        when(jdbc.queryForObject(contains("commerce_role_permissions"), eq(Integer.class),
            eq(66), eq("PRODUCTS_MANAGE"))).thenReturn(0);

        service.grantRolePermission(auth, 66, "PRODUCTS_MANAGE");

        verify(jdbc).update(contains("INSERT INTO commerce_role_permissions"),
            eq(66), eq("PRODUCTS_MANAGE"), eq(7));
        verify(jdbc).update(contains("INSERT INTO commerce_access_audit"),
            eq(7), eq("ROLE_PERMISSION_GRANTED"), isNull(), eq(66), eq("PRODUCTS_MANAGE"));
    }

    @Test
    void communityAdminCannotGrantByBeingAdminInAnotherDomain() {
        when(authorization.requireOwner(auth)).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN, "Acesso exclusivo do proprietário")
        );

        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.grantRolePermission(auth, 66, "FINANCE_VIEW")).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void ownerCannotApproveOrRevokeSelf() {
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
            () -> service.approvePerson(auth, 7)).getStatusCode());
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, assertThrows(ResponseStatusException.class,
            () -> service.revokePerson(auth, 7)).getStatusCode());
        verifyNoInteractions(jdbc);
    }
}
