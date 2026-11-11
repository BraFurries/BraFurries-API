package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class VoiceXpAuditRetentionJobTest {

    @Test
    void usesAnIndexed72HourCutoffAndOnlyDeletesDetailLogs() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(2);
        when(jdbc.update(anyString())).thenReturn(17);
        VoiceXpAuditRetentionJob job = new VoiceXpAuditRetentionJob(jdbc);

        job.purgeExpiredDiagnostics();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture());
        String statement = sql.getValue();

        assertTrue(statement.contains("DELETE FROM voice_xp_audit_log"));
        assertTrue(statement.contains("created_at < CURRENT_TIMESTAMP - INTERVAL 72 HOUR"));
        assertTrue(statement.contains("ORDER BY created_at ASC, id ASC"));
        assertTrue(statement.contains("LIMIT 500"));
        assertFalse(statement.contains("voice_xp_daily_metrics"));
        assertFalse(statement.contains("user_level"));
        assertFalse(statement.contains("voice_sessions"));
        assertFalse(statement.toUpperCase().contains("TRUNCATE"));
    }

    @Test
    void limitsWorkEvenWithAFullBacklog() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(2);
        when(jdbc.update(anyString())).thenReturn(VoiceXpAuditRetentionJob.BATCH_SIZE);
        new VoiceXpAuditRetentionJob(jdbc).purgeExpiredDiagnostics();
        verify(jdbc, times(VoiceXpAuditRetentionJob.MAX_BATCHES)).update(anyString());
    }

    @Test
    void stopsEarlyOnShortOrEmptyBatch() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(2);
        when(jdbc.update(anyString())).thenReturn(VoiceXpAuditRetentionJob.BATCH_SIZE, 12, 0);
        new VoiceXpAuditRetentionJob(jdbc).purgeExpiredDiagnostics();
        verify(jdbc, times(2)).update(anyString());

        JdbcTemplate empty = mock(JdbcTemplate.class);
        when(empty.queryForObject(anyString(), eq(Integer.class))).thenReturn(2);
        when(empty.update(anyString())).thenReturn(0);
        new VoiceXpAuditRetentionJob(empty).purgeExpiredDiagnostics();
        verify(empty, times(1)).update(anyString());
    }

    @Test
    void doesNotDeleteAnythingWhenRequiredDatabaseIndexIsMissing() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        new VoiceXpAuditRetentionJob(jdbc).purgeExpiredDiagnostics();

        verify(jdbc, times(0)).update(anyString());
    }

    @Test
    void boundsAreDeliberatelySmallForOnlineMaintenance() {
        assertEquals(500, VoiceXpAuditRetentionJob.BATCH_SIZE);
        assertEquals(10, VoiceXpAuditRetentionJob.MAX_BATCHES);
    }
}
