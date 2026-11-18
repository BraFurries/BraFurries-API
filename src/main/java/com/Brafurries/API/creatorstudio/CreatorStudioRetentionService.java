package com.Brafurries.API.creatorstudio;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only retention warnings. Never delete on a GET request.
 * Notice windows are computed from the LAST completion, not creation/request.
 * Works for an authenticated artist after an entitlement revocation too.
 */
@Service
public class CreatorStudioRetentionService {
    private final CreatorStudioAccessService access;
    private final JdbcTemplate jdbc;

    public record ExpiringCommission(long id, String title, LocalDateTime completedAtUtc,
                                     LocalDateTime expiresAtUtc, String warning) {}
    public record RetentionOverview(String policy, int retentionMonths,
                                    int advanceNoticeDays, int finalNoticeDays,
                                    boolean automaticDeletionEnabled,
                                    List<ExpiringCommission> commissions) {}

    public CreatorStudioRetentionService(CreatorStudioAccessService access, JdbcTemplate jdbc) {
        this.access = access;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public RetentionOverview myUpcomingRetention(Authentication auth) {
        int ownerId = access.actorId(auth);
        List<ExpiringCommission> rows = jdbc.query("""
            SELECT id,title,completed_at,
                   DATE_ADD(completed_at,INTERVAL 1 YEAR) AS expires_at,
                   CASE
                     WHEN DATE_ADD(completed_at,INTERVAL 1 YEAR)<=UTC_TIMESTAMP(6)
                         THEN 'OVERDUE'
                     WHEN DATE_ADD(completed_at,INTERVAL 1 YEAR)
                         <=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 7 DAY)
                         THEN 'FINAL_7_DAYS'
                     ELSE 'ADVANCE_30_DAYS'
                   END AS warning
              FROM creator_studio_commissions
             WHERE owner_user_id=? AND status='COMPLETED'
               AND completed_at IS NOT NULL
               AND DATE_ADD(completed_at,INTERVAL 1 YEAR)
                   <=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 DAY)
             ORDER BY completed_at ASC,id ASC
             LIMIT 100
            """, (rs, n) -> new ExpiringCommission(
                rs.getLong("id"),rs.getString("title"),
                rs.getObject("completed_at", LocalDateTime.class),
                rs.getObject("expires_at", LocalDateTime.class),
                rs.getString("warning")
            ), ownerId);
        return new RetentionOverview("12_MONTHS_AFTER_LAST_COMPLETION",12,30,7,
            false,rows);
    }
}
