package com.Brafurries.API.creatorstudio;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner-only, read-only inventory of expired Creator Studio commissions.
 *
 * Buckets are mutually exclusive: active hold, missing D30, missing D7,
 * or both notices accepted by SMTP. An SMTP acceptance is NOT proof of
 * final delivery; this report never authorizes or executes a deletion.
 */
@Service
public class CreatorStudioRetentionDryRunService {
    private static final String REPORT_SQL = """
        SELECT COUNT(*) AS expired_count,
               COALESCE(SUM(CASE WHEN b.reason='HELD' THEN 1 ELSE 0 END),0)
                   AS held_count,
               COALESCE(SUM(CASE WHEN b.reason='MISSING_D30' THEN 1 ELSE 0 END),0)
                   AS missing_d30_count,
               COALESCE(SUM(CASE WHEN b.reason='MISSING_D7' THEN 1 ELSE 0 END),0)
                   AS missing_d7_count,
               COALESCE(SUM(CASE WHEN b.reason='SMTP_ACCEPTED_BOTH' THEN 1 ELSE 0 END),0)
                   AS smtp_accepted_both_count
          FROM (
            SELECT CASE
              WHEN EXISTS (
                SELECT 1 FROM creator_studio_retention_holds h
                 WHERE h.owner_user_id=c.owner_user_id
                   AND h.commission_id=c.id AND h.active=1
              ) THEN 'HELD'
              WHEN NOT EXISTS (
                SELECT 1 FROM creator_studio_retention_notices n
                 WHERE n.owner_user_id=c.owner_user_id
                   AND n.commission_id=c.id
                   AND n.completed_at=c.completed_at
                   AND n.notice_kind='D30' AND n.status='SENT'
                   AND n.sent_at IS NOT NULL
              ) THEN 'MISSING_D30'
              WHEN NOT EXISTS (
                SELECT 1 FROM creator_studio_retention_notices n
                 WHERE n.owner_user_id=c.owner_user_id
                   AND n.commission_id=c.id
                   AND n.completed_at=c.completed_at
                   AND n.notice_kind='D7' AND n.status='SENT'
                   AND n.sent_at IS NOT NULL
              ) THEN 'MISSING_D7'
              ELSE 'SMTP_ACCEPTED_BOTH'
            END AS reason
              FROM creator_studio_commissions c
             WHERE c.status='COMPLETED' AND c.completed_at IS NOT NULL
               AND c.completed_at<=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 364 DAY)
               AND DATE_ADD(c.completed_at,INTERVAL 1 YEAR)<=UTC_TIMESTAMP(6)
          ) b
        """;

    /**
     * smtpAcceptedBoth counts records that pass *database* preconditions
     * only. It deliberately does not claim the artist actually received email.
     */
    public record DryRun(long expiredCommissions, long blockedByHold,
                         long blockedByMissingD30, long blockedByMissingD7,
                         long smtpAcceptedBoth, boolean deliveryProofSupported) {}

    private final PlatformAuthorizationService identity;
    private final JdbcTemplate jdbc;

    public CreatorStudioRetentionDryRunService(PlatformAuthorizationService identity,
                                               JdbcTemplate jdbc) {
        this.identity = identity;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public DryRun inspect(Authentication auth) {
        User admin = identity.requireOwner(auth);
        if (admin.getId() == null || admin.getMergedIntoUser() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        List<DryRun> rows = jdbc.query(REPORT_SQL, (rs, index) -> new DryRun(
            rs.getLong("expired_count"),
            rs.getLong("held_count"),
            rs.getLong("missing_d30_count"),
            rs.getLong("missing_d7_count"),
            rs.getLong("smtp_accepted_both_count"),
            false
        ));
        if (rows.size() != 1) {
            throw new IllegalStateException("Retention dry-run aggregate unavailable");
        }
        DryRun result = rows.get(0);
        if (result.expiredCommissions() != result.blockedByHold()
                + result.blockedByMissingD30() + result.blockedByMissingD7()
                + result.smtpAcceptedBoth()) {
            throw new IllegalStateException("Retention dry-run buckets inconsistent");
        }
        return result;
    }
}
