package com.Brafurries.API.user;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Only stale jobs are finalized. Coddy refreshes RUNNING.updated_at every 15s,
 * so an active worker cannot be mistaken for an abandoned one. This also frees
 * unique active-guild slots after a process crash or failed dispatch.
 */
@Service
public class RoleToolStaleOperationJob {
    private final JdbcTemplate jdbc;

    public RoleToolStaleOperationJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Candidate(long id, long guildId, int communityId,
                             int addedCount, String status, String operationType) {}

    static String terminalStatus(String status, String operationType, int addedCount) {
        // Discord mutations may have succeeded after the last progress checkpoint.
        boolean mutating = "BULK_ADD".equals(operationType) || "RECOVERY_APPLY".equals(operationType);
        return addedCount > 0 || ("RUNNING".equals(status) && mutating)
            ? "PARTIAL" : "FAILED";
    }

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void reap() {
        List<Candidate> expired = jdbc.query("""
            SELECT id,guild_id,community_id,added_count,status,operation_type
            FROM guild_role_tool_operations
            WHERE (status='RUNNING'
                   AND updated_at < CURRENT_TIMESTAMP(6) - INTERVAL 10 MINUTE)
               OR (status='PENDING'
                   AND created_at < CURRENT_TIMESTAMP(6) - INTERVAL 1 HOUR)
            ORDER BY id
            LIMIT 100
            """, (rs, row) -> new Candidate(
                rs.getLong("id"), rs.getLong("guild_id"),
                rs.getInt("community_id"), rs.getInt("added_count"),
                rs.getString("status"), rs.getString("operation_type")
            ));
        for (Candidate candidate : expired) {
            String outcome = terminalStatus(
                candidate.status(), candidate.operationType(), candidate.addedCount());
            String reason = "RUNNING".equals(candidate.status())
                ? "WORKER_HEARTBEAT_EXPIRED" : "PENDING_DISPATCH_EXPIRED";
            int changed = jdbc.update("""
                UPDATE guild_role_tool_operations
                SET status=?, error_code=?, finished_at=CURRENT_TIMESTAMP(6)
                WHERE id=? AND guild_id=? AND community_id=? AND status=?
                  AND (
                      (status='RUNNING' AND updated_at < CURRENT_TIMESTAMP(6) - INTERVAL 10 MINUTE)
                      OR (status='PENDING' AND created_at < CURRENT_TIMESTAMP(6) - INTERVAL 1 HOUR)
                  )
                """, outcome, reason, candidate.id(), candidate.guildId(),
                candidate.communityId(), candidate.status());
            if (changed == 1) {
                jdbc.update("""
                    INSERT INTO guild_role_tool_audit
                        (operation_id,community_id,guild_id,actor_user_id,event_type,detail)
                    VALUES (?,?,?,NULL,'EXPIRED',?)
                    """, candidate.id(), candidate.communityId(),
                    candidate.guildId(), reason);
            }
        }
    }
}
