package com.Brafurries.API.user;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BackupControlPlaneStore {
    private final JdbcTemplate jdbc;

    public BackupControlPlaneStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public BackupSettingsStored readSettings(long guildId) {
        List<BackupSettingsStored> rows = jdbc.query("""
            SELECT periodic_backups_enabled, periodicity_frequency
            FROM backup_server_settings
            WHERE guild_id=?
            LIMIT 1
            """, (rs, row) -> new BackupSettingsStored(
                rs.getBoolean("periodic_backups_enabled"),
                normalizeFrequency(rs.getString("periodicity_frequency"))
            ), guildId);
        return rows.isEmpty()
            ? new BackupSettingsStored(false, "weekly")
            : rows.getFirst();
    }

    public void writeSettings(
        long guildId,
        boolean periodicEnabled,
        String frequency,
        int legacyMinutes
    ) {
        jdbc.update("""
            INSERT INTO backup_server_settings
                (guild_id, max_normal_backups, max_periodic_backups,
                 periodic_backups_enabled, periodicity_minutes, periodicity_frequency)
            VALUES (?, 1, 1, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                max_normal_backups=1,
                max_periodic_backups=1,
                periodic_backups_enabled=VALUES(periodic_backups_enabled),
                periodicity_minutes=VALUES(periodicity_minutes),
                periodicity_frequency=VALUES(periodicity_frequency),
                updated_at=CURRENT_TIMESTAMP
            """,
            guildId,
            periodicEnabled,
            legacyMinutes,
            frequency
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void observeGuildBackupPresence(long guildId, boolean active) {
        if (active) {
            Boolean expired = jdbc.query(
                """
                SELECT purge_after IS NOT NULL
                    AND purge_after <= UTC_TIMESTAMP(6) AS expired
                FROM backup_server_settings
                WHERE guild_id=?
                LIMIT 1
                """,
                rs -> rs.next() ? rs.getBoolean("expired") : null,
                guildId
            );
            if (Boolean.TRUE.equals(expired)) {
                purgeGuildBackupDomain(guildId);
            }

            jdbc.update("""
                INSERT INTO backup_server_settings
                    (guild_id, max_normal_backups, max_periodic_backups,
                     periodic_backups_enabled, periodicity_minutes, periodicity_frequency,
                     bot_removed_at, purge_after)
                VALUES (?, 1, 1, 0, 10080, 'weekly', NULL, NULL)
                ON DUPLICATE KEY UPDATE
                    max_normal_backups=1,
                    max_periodic_backups=1,
                    bot_removed_at=NULL,
                    purge_after=NULL,
                    updated_at=CURRENT_TIMESTAMP
                """, guildId);
            return;
        }

        jdbc.update("""
            INSERT INTO backup_server_settings
                (guild_id, max_normal_backups, max_periodic_backups,
                 periodic_backups_enabled, periodicity_minutes, periodicity_frequency,
                 bot_removed_at, purge_after)
            VALUES (
                ?, 1, 1, 0, 10080, 'weekly',
                UTC_TIMESTAMP(6),
                DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 7 DAY)
            )
            ON DUPLICATE KEY UPDATE
                max_normal_backups=1,
                max_periodic_backups=1,
                bot_removed_at=COALESCE(bot_removed_at, UTC_TIMESTAMP(6)),
                purge_after=COALESCE(
                    purge_after,
                    DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 7 DAY)
                ),
                updated_at=CURRENT_TIMESTAMP
            """, guildId);
    }

    private void purgeGuildBackupDomain(long guildId) {
        jdbc.update("DELETE FROM backup_operation_steps WHERE guild_id=?", guildId);
        jdbc.update("DELETE FROM backup_snapshot_operations WHERE guild_id=?", guildId);
        jdbc.update("DELETE FROM backup_restore_operations WHERE guild_id=?", guildId);
        jdbc.update("DELETE FROM backup_discord WHERE guild_id=?", guildId);
        jdbc.update("DELETE FROM backup_guild_locks WHERE guild_id=?", guildId);
        jdbc.update("DELETE FROM backup_server_settings WHERE guild_id=?", guildId);
    }

    public List<BackupStored> listBackups(long guildId) {
        return jdbc.query("""
            SELECT
                bd.id,
                bd.guild_id,
                bd.original_name,
                bd.backup_type,
                bd.created_by_discord_user_id,
                bd.idempotency_key,
                bd.creation_audited_at,
                bd.created_at,
                (SELECT COUNT(*) FROM backup_roles br WHERE br.backup_id = bd.id) AS role_count,
                (SELECT COUNT(*) FROM backup_channels bc WHERE bc.backup_id = bd.id) AS channel_count,
                (
                    SELECT COUNT(*)
                    FROM backup_overwrites bo
                    INNER JOIN backup_channels bc2 ON bc2.id = bo.channel_id
                    WHERE bc2.backup_id = bd.id
                ) AS overwrite_count
            FROM backup_discord bd
            INNER JOIN (
                SELECT backup_type, MAX(id) AS latest_id
                FROM backup_discord
                WHERE guild_id = ?
                GROUP BY backup_type
            ) latest ON latest.latest_id = bd.id
            WHERE bd.guild_id = ?
            ORDER BY bd.id DESC
            """, (rs, row) -> mapBackup(rs), guildId, guildId);
    }

    public BackupStored findBackup(long guildId, int backupId) {
        List<BackupStored> rows = jdbc.query("""
            SELECT
                bd.id,
                bd.guild_id,
                bd.original_name,
                bd.backup_type,
                bd.created_by_discord_user_id,
                bd.idempotency_key,
                bd.creation_audited_at,
                bd.created_at,
                (SELECT COUNT(*) FROM backup_roles br WHERE br.backup_id = bd.id) AS role_count,
                (SELECT COUNT(*) FROM backup_channels bc WHERE bc.backup_id = bd.id) AS channel_count,
                (
                    SELECT COUNT(*)
                    FROM backup_overwrites bo
                    INNER JOIN backup_channels bc2 ON bc2.id = bo.channel_id
                    WHERE bc2.backup_id = bd.id
                ) AS overwrite_count
            FROM backup_discord bd
            WHERE bd.guild_id = ? AND bd.id = ?
            LIMIT 1
            """, (rs, row) -> mapBackup(rs), guildId, backupId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public boolean deleteBackupIfIdle(long guildId, int backupId) {
        return jdbc.update("""
            DELETE FROM backup_discord
            WHERE guild_id=?
              AND id=?
              AND NOT EXISTS (
                  SELECT 1
                  FROM backup_restore_operations bro
                  WHERE bro.guild_id=?
                    AND bro.backup_id=?
                    AND bro.status IN ('PENDING', 'RUNNING')
              )
            """,
            guildId,
            backupId,
            guildId,
            backupId
        ) > 0;
    }

    public boolean hasActiveRestore(long guildId, int backupId) {
        Boolean active = jdbc.query(
            """
            SELECT EXISTS(
                SELECT 1
                FROM backup_restore_operations
                WHERE guild_id=?
                  AND backup_id=?
                  AND status IN ('PENDING', 'RUNNING')
            ) AS active
            """,
            rs -> rs.next() && rs.getBoolean("active"),
            guildId,
            backupId
        );
        return Boolean.TRUE.equals(active);
    }

    public SnapshotOperationStored createOrGetSnapshotOperation(
        long guildId,
        String operationKey,
        String requestedName,
        long actorDiscordUserId,
        Integer actorUserId
    ) {
        jdbc.update("""
            INSERT IGNORE INTO backup_snapshot_operations
                (guild_id, operation_key, backup_type, requested_name,
                 actor_discord_user_id, actor_user_id, status)
            VALUES (?, ?, 'normal', ?, ?, ?, 'PENDING')
            """,
            guildId,
            operationKey,
            requestedName,
            actorDiscordUserId,
            actorUserId
        );
        return findSnapshotOperationByKey(guildId, operationKey);
    }

    public SnapshotOperationStored findSnapshotOperation(
        long guildId,
        long operationId
    ) {
        List<SnapshotOperationStored> rows = jdbc.query("""
            SELECT id, guild_id, operation_key, backup_type, requested_name,
                   actor_discord_user_id, actor_user_id, status, backup_id,
                   progress_current, progress_total, current_step,
                   result_json, error_code, requested_audited_at,
                   completion_audited_at, started_at, finished_at, created_at
            FROM backup_snapshot_operations
            WHERE guild_id=? AND id=?
            LIMIT 1
            """, (rs, row) -> mapSnapshot(rs), guildId, operationId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public SnapshotOperationStored findSnapshotOperationByKey(
        long guildId,
        String operationKey
    ) {
        List<SnapshotOperationStored> rows = jdbc.query("""
            SELECT id, guild_id, operation_key, backup_type, requested_name,
                   actor_discord_user_id, actor_user_id, status, backup_id,
                   progress_current, progress_total, current_step,
                   result_json, error_code, requested_audited_at,
                   completion_audited_at, started_at, finished_at, created_at
            FROM backup_snapshot_operations
            WHERE guild_id=? AND operation_key=?
            LIMIT 1
            """, (rs, row) -> mapSnapshot(rs), guildId, operationKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<SnapshotOperationStored> listSnapshotOperations(long guildId) {
        return jdbc.query("""
            SELECT id, guild_id, operation_key, backup_type, requested_name,
                   actor_discord_user_id, actor_user_id, status, backup_id,
                   progress_current, progress_total, current_step,
                   result_json, error_code, requested_audited_at,
                   completion_audited_at, started_at, finished_at, created_at
            FROM backup_snapshot_operations
            WHERE guild_id=?
            ORDER BY id DESC
            LIMIT 50
            """, (rs, row) -> mapSnapshot(rs), guildId);
    }

    public boolean claimSnapshotRequestedAudit(long guildId, long operationId) {
        return jdbc.update("""
            UPDATE backup_snapshot_operations
            SET requested_audited_at=UTC_TIMESTAMP(6)
            WHERE guild_id=? AND id=? AND requested_audited_at IS NULL
            """, guildId, operationId) > 0;
    }

    public boolean claimSnapshotCompletionAudit(long guildId, long operationId) {
        return jdbc.update("""
            UPDATE backup_snapshot_operations
            SET completion_audited_at=UTC_TIMESTAMP(6)
            WHERE guild_id=?
              AND id=?
              AND status IN ('SUCCEEDED', 'PARTIAL', 'FAILED')
              AND completion_audited_at IS NULL
            """, guildId, operationId) > 0;
    }

    public RestoreOperationStored createOrGetRestoreOperation(
        long guildId,
        int backupId,
        String operationKey,
        String scope,
        long actorDiscordUserId,
        Integer actorUserId,
        String decisionJson
    ) {
        jdbc.update("""
            INSERT IGNORE INTO backup_restore_operations
                (guild_id, backup_id, operation_key, scope,
                 actor_discord_user_id, actor_user_id, status, decision_json)
            SELECT
                ?, bd.id, ?, ?, ?, ?, 'PENDING', ?
            FROM backup_discord bd
            WHERE bd.id=? AND bd.guild_id=?
            """,
            guildId,
            operationKey,
            scope,
            actorDiscordUserId,
            actorUserId,
            decisionJson,
            backupId,
            guildId
        );
        return findRestoreOperationByKey(guildId, operationKey);
    }

    public RestoreOperationStored findRestoreOperation(long guildId, long operationId) {
        List<RestoreOperationStored> rows = jdbc.query("""
            SELECT id, guild_id, backup_id, operation_key, scope,
                   actor_discord_user_id, actor_user_id, status, decision_json,
                   result_json, error_code, progress_current, progress_total,
                   current_step, requested_audited_at, completion_audited_at,
                   started_at, finished_at, created_at
            FROM backup_restore_operations
            WHERE guild_id=? AND id=?
            LIMIT 1
            """, (rs, row) -> mapRestore(rs), guildId, operationId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public RestoreOperationStored findRestoreOperationByKey(long guildId, String operationKey) {
        List<RestoreOperationStored> rows = jdbc.query("""
            SELECT id, guild_id, backup_id, operation_key, scope,
                   actor_discord_user_id, actor_user_id, status, decision_json,
                   result_json, error_code, progress_current, progress_total,
                   current_step, requested_audited_at, completion_audited_at,
                   started_at, finished_at, created_at
            FROM backup_restore_operations
            WHERE guild_id=? AND operation_key=?
            LIMIT 1
            """, (rs, row) -> mapRestore(rs), guildId, operationKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<RestoreOperationStored> listRestoreOperations(long guildId) {
        return jdbc.query("""
            SELECT id, guild_id, backup_id, operation_key, scope,
                   actor_discord_user_id, actor_user_id, status, decision_json,
                   result_json, error_code, progress_current, progress_total,
                   current_step, requested_audited_at, completion_audited_at,
                   started_at, finished_at, created_at
            FROM backup_restore_operations
            WHERE guild_id=?
            ORDER BY id DESC
            LIMIT 50
            """, (rs, row) -> mapRestore(rs), guildId);
    }

    public boolean claimRestoreRequestedAudit(long guildId, long operationId) {
        return jdbc.update("""
            UPDATE backup_restore_operations
            SET requested_audited_at=UTC_TIMESTAMP(6)
            WHERE guild_id=? AND id=? AND requested_audited_at IS NULL
            """, guildId, operationId) > 0;
    }

    public boolean claimRestoreCompletionAudit(long guildId, long operationId) {
        return jdbc.update("""
            UPDATE backup_restore_operations
            SET completion_audited_at=UTC_TIMESTAMP(6)
            WHERE guild_id=?
              AND id=?
              AND status IN ('SUCCEEDED', 'PARTIAL', 'FAILED')
              AND completion_audited_at IS NULL
            """, guildId, operationId) > 0;
    }

    public boolean existsOperationStep(
        long guildId, String operationKind, long operationId, long stepId
    ) {
        Integer found = jdbc.queryForObject("""
            SELECT COUNT(*)
            FROM backup_operation_steps
            WHERE guild_id=? AND operation_kind=? AND operation_id=? AND id=?
            """, Integer.class, guildId, operationKind, operationId, stepId);
        return found != null && found > 0;
    }

    public List<OperationStepStored> listOperationSteps(
        long guildId,
        String operationKind,
        long operationId
    ) {
        return jdbc.query("""
            SELECT id, guild_id, operation_kind, operation_id, step_code,
                   step_status, message, progress_current, progress_total,
                   detail_json, created_at
            FROM backup_operation_steps
            WHERE guild_id=? AND operation_kind=? AND operation_id=?
            ORDER BY id
            """, (rs, row) -> new OperationStepStored(
                rs.getLong("id"),
                rs.getLong("guild_id"),
                rs.getString("operation_kind"),
                rs.getLong("operation_id"),
                rs.getString("step_code"),
                rs.getString("step_status"),
                rs.getString("message"),
                rs.getInt("progress_current"),
                rs.getInt("progress_total"),
                rs.getString("detail_json"),
                instant(rs.getTimestamp("created_at"))
            ), guildId, operationKind, operationId);
    }

    private BackupStored mapBackup(java.sql.ResultSet rs) throws java.sql.SQLException {
        Object creator = rs.getObject("created_by_discord_user_id");
        return new BackupStored(
            rs.getInt("id"),
            rs.getLong("guild_id"),
            rs.getString("original_name"),
            rs.getString("backup_type"),
            creator == null ? null : rs.getLong("created_by_discord_user_id"),
            rs.getString("idempotency_key"),
            instant(rs.getTimestamp("creation_audited_at")),
            instant(rs.getTimestamp("created_at")),
            rs.getInt("role_count"),
            rs.getInt("channel_count"),
            rs.getInt("overwrite_count")
        );
    }

    private SnapshotOperationStored mapSnapshot(
        java.sql.ResultSet rs
    ) throws java.sql.SQLException {
        Object actorDiscord = rs.getObject("actor_discord_user_id");
        Object actorUser = rs.getObject("actor_user_id");
        Object backupId = rs.getObject("backup_id");
        return new SnapshotOperationStored(
            rs.getLong("id"),
            rs.getLong("guild_id"),
            rs.getString("operation_key"),
            rs.getString("backup_type"),
            rs.getString("requested_name"),
            actorDiscord == null ? null : rs.getLong("actor_discord_user_id"),
            actorUser == null ? null : rs.getInt("actor_user_id"),
            rs.getString("status"),
            backupId == null ? null : rs.getInt("backup_id"),
            rs.getInt("progress_current"),
            rs.getInt("progress_total"),
            rs.getString("current_step"),
            rs.getString("result_json"),
            rs.getString("error_code"),
            instant(rs.getTimestamp("requested_audited_at")),
            instant(rs.getTimestamp("completion_audited_at")),
            instant(rs.getTimestamp("started_at")),
            instant(rs.getTimestamp("finished_at")),
            instant(rs.getTimestamp("created_at"))
        );
    }

    private RestoreOperationStored mapRestore(
        java.sql.ResultSet rs
    ) throws java.sql.SQLException {
        Object actorUser = rs.getObject("actor_user_id");
        return new RestoreOperationStored(
            rs.getLong("id"),
            rs.getLong("guild_id"),
            rs.getInt("backup_id"),
            rs.getString("operation_key"),
            rs.getString("scope"),
            rs.getLong("actor_discord_user_id"),
            actorUser == null ? null : rs.getInt("actor_user_id"),
            rs.getString("status"),
            rs.getString("decision_json"),
            rs.getString("result_json"),
            rs.getString("error_code"),
            rs.getInt("progress_current"),
            rs.getInt("progress_total"),
            rs.getString("current_step"),
            instant(rs.getTimestamp("requested_audited_at")),
            instant(rs.getTimestamp("completion_audited_at")),
            instant(rs.getTimestamp("started_at")),
            instant(rs.getTimestamp("finished_at")),
            instant(rs.getTimestamp("created_at"))
        );
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String normalizeFrequency(String value) {
        String frequency = value == null ? "weekly" : value.trim().toLowerCase();
        return switch (frequency) {
            case "daily", "weekly", "monthly" -> frequency;
            default -> "weekly";
        };
    }

    public record BackupSettingsStored(
        boolean periodicEnabled,
        String frequency
    ) {}

    public record BackupStored(
        Integer id,
        long guildId,
        String name,
        String backupType,
        Long creatorDiscordUserId,
        String idempotencyKey,
        Instant creationAuditedAt,
        Instant createdAt,
        int roleCount,
        int channelCount,
        int overwriteCount
    ) {}

    public record SnapshotOperationStored(
        Long id,
        long guildId,
        String operationKey,
        String backupType,
        String requestedName,
        Long actorDiscordUserId,
        Integer actorUserId,
        String status,
        Integer backupId,
        int progressCurrent,
        int progressTotal,
        String currentStep,
        String resultJson,
        String errorCode,
        Instant requestedAuditedAt,
        Instant completionAuditedAt,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt
    ) {}

    public record RestoreOperationStored(
        Long id,
        long guildId,
        Integer backupId,
        String operationKey,
        String scope,
        long actorDiscordUserId,
        Integer actorUserId,
        String status,
        String decisionJson,
        String resultJson,
        String errorCode,
        int progressCurrent,
        int progressTotal,
        String currentStep,
        Instant requestedAuditedAt,
        Instant completionAuditedAt,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt
    ) {}

    public record OperationStepStored(
        Long id,
        long guildId,
        String operationKind,
        long operationId,
        String stepCode,
        String stepStatus,
        String message,
        int progressCurrent,
        int progressTotal,
        String detailJson,
        Instant createdAt
    ) {}
}
