package com.Brafurries.API.user;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class BackupControlPlaneStoreTest {
    private JdbcTemplate jdbc;
    private BackupControlPlaneStore store;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        store = new BackupControlPlaneStore(jdbc);
    }

    @Test
    void guildPresenceUsesIndependentTransactionBoundary() throws Exception {
        Transactional annotation = BackupControlPlaneStore.class
            .getDeclaredMethod("observeGuildBackupPresence", long.class, boolean.class)
            .getAnnotation(Transactional.class);

        org.junit.jupiter.api.Assertions.assertNotNull(annotation);
        org.junit.jupiter.api.Assertions.assertEquals(
            Propagation.REQUIRES_NEW,
            annotation.propagation()
        );
    }

    @Test
    void activeGuildBeforeDeadlineCancelsPurgeWithoutDeletingBackups() {
        when(jdbc.query(
            anyString(),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<Boolean>>any(),
            eq(123L)
        )).thenReturn(false);

        store.observeGuildBackupPresence(123L, true);

        verify(jdbc, never()).update(
            startsWith("DELETE FROM backup_discord"),
            eq(123L)
        );
        verify(jdbc).update(
            contains("bot_removed_at=NULL"),
            eq(123L)
        );
    }

    @Test
    void activeGuildAfterDeadlinePurgesExpiredDomainBeforeFreshDefaults() {
        when(jdbc.query(
            anyString(),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<Boolean>>any(),
            eq(123L)
        )).thenReturn(true);

        store.observeGuildBackupPresence(123L, true);

        var inOrder = inOrder(jdbc);
        inOrder.verify(jdbc).query(
            anyString(),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<Boolean>>any(),
            eq(123L)
        );
        inOrder.verify(jdbc).update(
            "DELETE FROM backup_operation_steps WHERE guild_id=?",
            123L
        );
        inOrder.verify(jdbc).update(
            "DELETE FROM backup_snapshot_operations WHERE guild_id=?",
            123L
        );
        inOrder.verify(jdbc).update(
            "DELETE FROM backup_restore_operations WHERE guild_id=?",
            123L
        );
        inOrder.verify(jdbc).update(
            "DELETE FROM backup_discord WHERE guild_id=?",
            123L
        );
        inOrder.verify(jdbc).update(
            "DELETE FROM backup_guild_locks WHERE guild_id=?",
            123L
        );
        inOrder.verify(jdbc).update(
            "DELETE FROM backup_server_settings WHERE guild_id=?",
            123L
        );
        inOrder.verify(jdbc).update(
            contains("VALUES (?, 1, 1, 0, 10080, 'weekly', NULL, NULL)"),
            eq(123L)
        );
    }

    @Test
    void deleteBackupIfIdleIsGuildScopedAndProtectsActiveRestore() {
        when(jdbc.update(
            anyString(),
            eq(123L),
            eq(9),
            eq(123L),
            eq(9)
        )).thenReturn(1);

        boolean deleted = store.deleteBackupIfIdle(123L, 9);

        org.junit.jupiter.api.Assertions.assertTrue(deleted);
        verify(jdbc).update(
            argThat(sql ->
                sql.contains("DELETE FROM backup_discord")
                    && sql.contains("guild_id=?")
                    && sql.contains("NOT EXISTS")
                    && sql.contains("status IN ('PENDING', 'RUNNING')")
            ),
            eq(123L),
            eq(9),
            eq(123L),
            eq(9)
        );
    }

    @Test
    void activeRestoreLookupIsGuildAndBackupScoped() {
        when(jdbc.query(
            anyString(),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<Boolean>>any(),
            eq(123L),
            eq(9)
        )).thenReturn(true);

        org.junit.jupiter.api.Assertions.assertTrue(
            store.hasActiveRestore(123L, 9)
        );

        verify(jdbc).query(
            argThat(sql ->
                sql.contains("guild_id=?")
                    && sql.contains("backup_id=?")
                    && sql.contains("PENDING")
                    && sql.contains("RUNNING")
            ),
            org.mockito.ArgumentMatchers.<ResultSetExtractor<Boolean>>any(),
            eq(123L),
            eq(9)
        );
    }

    @Test
    void inactiveGuildSchedulesSevenDayGraceWithoutDeletingBackupDomain() {
        store.observeGuildBackupPresence(123L, false);

        verify(jdbc).update(
            argThat(sql ->
                sql.contains("DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 7 DAY)")
                    && sql.contains("purge_after=COALESCE")
            ),
            eq(123L)
        );
        verify(jdbc, never()).update(
            startsWith("DELETE FROM backup_discord"),
            eq(123L)
        );
    }
}
