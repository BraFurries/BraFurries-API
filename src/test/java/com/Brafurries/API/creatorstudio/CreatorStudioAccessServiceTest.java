package com.Brafurries.API.creatorstudio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.util.List;
import java.util.Map;
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
class CreatorStudioAccessServiceTest {
    @Mock PlatformAuthorizationService identity;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    CreatorStudioAccessService access;

    @BeforeEach
    void init() { access = new CreatorStudioAccessService(identity, jdbc); }

    private static User user(int id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    @Test
    void deniesWithoutExplicitActiveEntitlementEvenForFree() {
        when(identity.currentUser(auth)).thenReturn(user(22));
        when(jdbc.queryForObject(contains("creator_studio_subscriptions"),
                eq(Integer.class), eq(22), isNull(), isNull())).thenReturn(0);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> access.requireActive(auth));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(jdbc).queryForObject(argThat(sql ->
                sql.contains("status='ACTIVE'")
                && sql.contains("starts_at<=UTC_TIMESTAMP(6)")
                && sql.contains("expires_at IS NULL")
                && sql.contains("expires_at>UTC_TIMESTAMP(6)")),
            eq(Integer.class), eq(22), isNull(), isNull());
    }

    @Test
    void acceptsAuthorizedFreeAndPermanentPilotWhenDbGrantsAccess() {
        when(identity.currentUser(auth)).thenReturn(user(22));
        when(jdbc.queryForObject(contains("creator_studio_subscriptions"),
                eq(Integer.class), eq(22), isNull(), isNull())).thenReturn(1);
        assertEquals(22, access.requireActive(auth));
    }

    @Test
    void paidOnlyMethodDoesNotGrantAccessToFreeTier() {
        when(identity.currentUser(auth)).thenReturn(user(22));
        when(jdbc.queryForObject(contains("creator_studio_subscriptions"),
                eq(Integer.class), eq(22), eq("PRO"), eq("PRO"))).thenReturn(0);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> access.requirePro(auth)).getStatusCode());
    }

    @Test
    void refusesConsolidatedAccountBeforeCheckingEntitlement() {
        User alias = user(22);
        alias.setMergedIntoUser(user(99));
        when(identity.currentUser(auth)).thenReturn(alias);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> access.requireActive(auth)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void onlyOwnerCanGrantPilotAccess() {
        when(identity.requireOwner(auth)).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> access.grantPilotLifetimePro(auth, 22)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test
    void grantsPermanentProPilotAndAuditsIt() {
        when(identity.requireOwner(auth)).thenReturn(user(1));
        when(jdbc.queryForObject(contains("FROM users"), eq(Integer.class), eq(22)))
            .thenReturn(1);
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(22)))
            .thenReturn(List.of());

        access.grantPilotLifetimePro(auth, 22);

        verify(jdbc).update(argThat(sql ->
                sql.contains("INSERT INTO creator_studio_subscriptions")
                && sql.contains("UTC_TIMESTAMP(6),NULL")),
            eq(22), eq("PRO"), eq("PILOT"), eq(1));
        verify(jdbc).update(contains("INSERT INTO creator_studio_subscription_events"),
            eq(22), eq(1), eq("ACTIVATED"), isNull(), eq(22));
    }

    @Test
    void grantsInvitedFreeInsteadOfEnablingEveryone() {
        when(identity.requireOwner(auth)).thenReturn(user(1));
        when(jdbc.queryForObject(contains("FROM users"), eq(Integer.class), eq(22)))
            .thenReturn(1);
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(22)))
            .thenReturn(List.of());

        access.grantFree(auth, 22);

        verify(jdbc).update(contains("INSERT INTO creator_studio_subscriptions"),
            eq(22), eq("FREE"), eq("INVITE"), eq(1));
    }

    @Test
    void cannotDowngradeFoundingLifetimeProToFree() {
        when(identity.requireOwner(auth)).thenReturn(user(1));
        when(jdbc.queryForObject(contains("FROM users"), eq(Integer.class), eq(22)))
            .thenReturn(1);
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(22))).thenReturn(List.of(
            Map.of("source_kind", "PILOT", "tier", "PRO", "status", "ACTIVE")
        ));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> access.grantFree(auth, 22)).getStatusCode());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void cannotReplaceFutureBillingSubscriptionWithManualGrant() {
        when(identity.requireOwner(auth)).thenReturn(user(1));
        when(jdbc.queryForObject(contains("FROM users"), eq(Integer.class), eq(22)))
            .thenReturn(1);
        when(jdbc.queryForList(contains("FOR UPDATE"), eq(22))).thenReturn(List.of(
            Map.of("source_kind", "BILLING", "tier", "PRO", "status", "ACTIVE")
        ));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ResponseStatusException.class,
            () -> access.grantPilotLifetimePro(auth, 22)).getStatusCode());
    }

    @Test
    void ownerRevokesGrantAndCreatesAuditEvent() {
        when(identity.requireOwner(auth)).thenReturn(user(1));
        when(jdbc.update(contains("UPDATE creator_studio_subscriptions"),
            eq(22))).thenReturn(1);
        access.revokeGrant(auth, 22);
        verify(jdbc).update(contains("INSERT INTO creator_studio_subscription_events"),
            eq(22), eq(1), eq(22));
    }

    @Test
    void noRawUserIdMayBeSuppliedAsActiveActor() {
        when(identity.currentUser(auth)).thenReturn(user(8));
        when(jdbc.queryForObject(contains("creator_studio_subscriptions"),
            eq(Integer.class), eq(8), isNull(), isNull())).thenReturn(1);
        assertEquals(8, access.requireActive(auth));
    }
}
