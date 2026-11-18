package com.Brafurries.API.creatorstudio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CreatorStudioRetentionHealthServiceTest {
    @Mock PlatformAuthorizationService identity;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    CreatorStudioRetentionHealthService service;

    @BeforeEach void setUp() {
        service = new CreatorStudioRetentionHealthService(identity, jdbc);
    }

    private User owner() {
        User user = new User();
        user.setId(1);
        return user;
    }

    @Test void rejectsCommunityAdminsWithoutPlatformOwnership() {
        when(identity.requireOwner(auth)).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.inspect(auth)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void rejectsMergedOwnerAccounts() {
        User merged = owner();
        User target = new User();
        target.setId(2);
        merged.setMergedIntoUser(target);
        when(identity.requireOwner(auth)).thenReturn(merged);
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.inspect(auth)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void distinguishesUncertainStuckBounceAndProviderStatesWithoutMutations() {
        when(identity.requireOwner(auth)).thenReturn(owner());
        var expected = new CreatorStudioRetentionHealthService.Health(
            22, 4, 2, 3, 1, 5, 4, 2, 1);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(expected));

        var result = service.inspect(auth);
        assertEquals(expected, result);
        verify(jdbc).query(org.mockito.ArgumentMatchers.<String>argThat(sql ->
            sql.contains("FROM creator_studio_retention_notices n")
            && sql.contains("c.owner_user_id=n.owner_user_id")
            && sql.contains("c.completed_at=n.completed_at")
            && sql.contains("c.status='COMPLETED'")
            && sql.contains("n.delivery_state='COMPLAINT'")
            && sql.contains("n.delivery_state='BOUNCED'")
            && sql.contains("n.status='UNKNOWN'")
            && sql.contains("INTERVAL 30 MINUTE")
            && sql.contains("n.status='PENDING'")
            && sql.contains("n.status='SENT' AND n.delivery_state='SERVER_ACCEPTED'")
            && !sql.contains("recipient_email")
            && !sql.contains("community_id")), any(RowMapper.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void emptyLedgerIsNormal() {
        when(identity.requireOwner(auth)).thenReturn(owner());
        var empty = new CreatorStudioRetentionHealthService.Health(0,0,0,0,0,0,0,0,0);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(empty));
        assertEquals(empty, service.inspect(auth));
        verify(jdbc).query(anyString(), any(RowMapper.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void mismatchedBucketsAreRejected() {
        when(identity.requireOwner(auth)).thenReturn(owner());
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of(
            new CreatorStudioRetentionHealthService.Health(5,1,1,0,0,0,0,0,0)));
        assertThrows(IllegalStateException.class, () -> service.inspect(auth));
        verify(jdbc).query(anyString(), any(RowMapper.class));
        verifyNoMoreInteractions(jdbc);
    }

    @Test void controllerReturnsNonCacheableAggregatesOnly() {
        var expected = new CreatorStudioRetentionHealthService.Health(1,1,0,0,0,0,0,0,0);
        var mocked = mock(CreatorStudioRetentionHealthService.class);
        when(mocked.inspect(auth)).thenReturn(expected);
        var response = new CreatorStudioRetentionHealthController(mocked).inspect(auth);
        assertEquals(expected, response.getBody());
        assertEquals(CacheControl.noStore().getHeaderValue(),
            response.getHeaders().getCacheControl());
        verifyNoInteractions(jdbc);
    }
}
