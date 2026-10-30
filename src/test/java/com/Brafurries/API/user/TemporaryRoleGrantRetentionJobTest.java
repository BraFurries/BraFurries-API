package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class TemporaryRoleGrantRetentionJobTest {
    @Test
    void neverPurgesActivePendingFailedOrRecentlyCompletedGrants() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString())).thenReturn(3);
        var job = new TemporaryRoleGrantRetentionJob(jdbc);

        job.purgeCompletedGrants();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture());
        String statement = sql.getValue();
        assertTrue(statement.contains("DELETE FROM user_temp_roles"));
        assertTrue(statement.contains("status IN ('EXPIRED', 'CANCELLED')"));
        assertTrue(statement.contains("sync_status = 'APPLIED'"));
        assertTrue(statement.contains("updated_at IS NOT NULL"));
        assertTrue(statement.contains("updated_at < UTC_TIMESTAMP(6) - INTERVAL 1 MONTH"));
        assertTrue(statement.contains("ORDER BY updated_at ASC, id ASC"));
        assertTrue(statement.contains("LIMIT 250"));
        assertFalse(statement.contains("created_at <"));
        assertFalse(statement.contains("expiring_date <"));
    }

    @Test
    void boundsDeletesPerRunAndStopsOnShortBatch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString())).thenReturn(250);
        var job = new TemporaryRoleGrantRetentionJob(jdbc);

        job.purgeCompletedGrants();

        verify(jdbc, times(TemporaryRoleGrantRetentionJob.MAX_BATCHES)).update(anyString());
    }

    @Test
    void stopsWhenNoEligibleRecordsExist() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString())).thenReturn(0);
        new TemporaryRoleGrantRetentionJob(jdbc).purgeCompletedGrants();
        verify(jdbc, times(1)).update(anyString());
    }
}
