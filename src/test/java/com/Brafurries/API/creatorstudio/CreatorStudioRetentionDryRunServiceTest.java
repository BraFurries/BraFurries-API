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
class CreatorStudioRetentionDryRunServiceTest {
    @Mock PlatformAuthorizationService identity;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    CreatorStudioRetentionDryRunService service;

    @BeforeEach void setUp() {
        service = new CreatorStudioRetentionDryRunService(identity, jdbc);
    }

    private User owner() {
        User user = new User();
        user.setId(1);
        return user;
    }

    @Test void refusesCommunityAdminWithoutPlatformOwnership() {
        when(identity.requireOwner(auth)).thenThrow(
            new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ResponseStatusException.class,
            () -> service.inspect(auth)).getStatusCode());
        verifyNoInteractions(jdbc);
    }

    @Test void countsExclusiveBucketsUsingCurrentCompletionEpochWithoutMutation() {
        when(identity.requireOwner(auth)).thenReturn(owner());
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(
            List.of(new CreatorStudioRetentionDryRunService.DryRun(14, 2, 3, 4, 2, 3, false)));

        var result = service.inspect(auth);
        assertEquals(14, result.expiredCommissions());
        assertEquals(2, result.blockedByHold());
        assertEquals(3, result.blockedByMissingD30());
        assertEquals(4, result.blockedByMissingD7());
        assertEquals(2, result.smtpAcceptedBoth());
        assertEquals(3, result.providerConfirmedBoth());
        assertFalse(result.deliveryProofSupported());

        verify(jdbc).query(org.mockito.ArgumentMatchers.<String>argThat(sql ->
            sql.contains("FROM creator_studio_commissions c")
            && sql.contains("c.status='COMPLETED'")
            && sql.contains("DATE_ADD(c.completed_at,INTERVAL 1 YEAR)<=UTC_TIMESTAMP(6)")
            && sql.contains("h.active=1")
            && sql.contains("n.completed_at=c.completed_at")
            && sql.contains("n.notice_kind='D30' AND n.status='SENT'")
            && sql.contains("n.notice_kind='D7' AND n.status='SENT'")
            && sql.contains("n.sent_at IS NOT NULL")
            && sql.contains("THEN 'HELD'")
            && sql.contains("THEN 'MISSING_D30'")
            && sql.contains("THEN 'MISSING_D7'")
            && sql.contains("THEN 'PROVIDER_CONFIRMED_BOTH'")
            && sql.contains("n.delivery_state='SERVER_ACCEPTED'")
            && sql.contains("n.delivery_event_ref IS NOT NULL")), any(RowMapper.class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void emptyReportReturnsOnlyZeroCounts() {
        when(identity.requireOwner(auth)).thenReturn(owner());
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(
            List.of(new CreatorStudioRetentionDryRunService.DryRun(0, 0, 0, 0, 0, 0, false)));
        assertEquals(0, service.inspect(auth).expiredCommissions());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void inconsistentMetricsAreRejectedInsteadOfMisleadingOperator() {
        when(identity.requireOwner(auth)).thenReturn(owner());
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(
            List.of(new CreatorStudioRetentionDryRunService.DryRun(5, 1, 1, 1, 1, 0, false)));
        assertThrows(IllegalStateException.class, () -> service.inspect(auth));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test void endpointIsNonCacheableAndReturnsOnlyAggregates() {
        var expected = new CreatorStudioRetentionDryRunService.DryRun(1, 0, 1, 0, 0, 0, false);
        // Test the HTTP contract separately from the service's JDBC dependencies.
        var mockedService = mock(CreatorStudioRetentionDryRunService.class);
        when(mockedService.inspect(auth)).thenReturn(expected);
        var response = new CreatorStudioRetentionDryRunController(mockedService).inspect(auth);
        assertEquals(expected, response.getBody());
        assertEquals(CacheControl.noStore().getHeaderValue(),
            response.getHeaders().getCacheControl());
        verifyNoInteractions(jdbc);
    }
}
