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
 * Non-destructive owner-only operational health of the CURRENT completion epoch.
 * No recipient, artist, commission, client, reference or error detail is returned.
 *
 * These mutually exclusive categories are for operator diagnosis only. They
 * neither prove inbox delivery nor authorize any automatic resend/deletion.
 */
@Service
public class CreatorStudioRetentionHealthService {
    private static final String HEALTH_SQL = """
        SELECT COUNT(*) AS total_count,
               COALESCE(SUM(CASE WHEN state='PENDING' THEN 1 ELSE 0 END),0)
                   AS pending_count,
               COALESCE(SUM(CASE WHEN state='SENDING' THEN 1 ELSE 0 END),0)
                   AS sending_count,
               COALESCE(SUM(CASE WHEN state='STUCK_SENDING' THEN 1 ELSE 0 END),0)
                   AS stuck_sending_count,
               COALESCE(SUM(CASE WHEN state='SMTP_UNKNOWN' THEN 1 ELSE 0 END),0)
                   AS smtp_unknown_count,
               COALESCE(SUM(CASE WHEN state='SMTP_UNCONFIRMED' THEN 1 ELSE 0 END),0)
                   AS smtp_unconfirmed_count,
               COALESCE(SUM(CASE WHEN state='PROVIDER_CONFIRMED' THEN 1 ELSE 0 END),0)
                   AS provider_confirmed_count,
               COALESCE(SUM(CASE WHEN state='BOUNCED' THEN 1 ELSE 0 END),0)
                   AS bounced_count,
               COALESCE(SUM(CASE WHEN state='COMPLAINT' THEN 1 ELSE 0 END),0)
                   AS complaint_count
          FROM (
            SELECT CASE
              WHEN n.delivery_state='COMPLAINT' THEN 'COMPLAINT'
              WHEN n.delivery_state='BOUNCED' THEN 'BOUNCED'
              WHEN n.status='UNKNOWN' THEN 'SMTP_UNKNOWN'
              WHEN n.status='SENDING'
                   AND (n.claimed_at IS NULL
                     OR n.claimed_at<=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 30 MINUTE))
                THEN 'STUCK_SENDING'
              WHEN n.status='SENDING' THEN 'SENDING'
              WHEN n.status='PENDING' THEN 'PENDING'
              WHEN n.status='SENT' AND n.delivery_state='SERVER_ACCEPTED'
                THEN 'PROVIDER_CONFIRMED'
              ELSE 'SMTP_UNCONFIRMED'
            END AS state
              FROM creator_studio_retention_notices n
              JOIN creator_studio_commissions c
                ON c.owner_user_id=n.owner_user_id
               AND c.id=n.commission_id AND c.completed_at=n.completed_at
             WHERE c.status='COMPLETED'
          ) classified
        """;

    public record Health(long totalNotices, long pending, long sending,
                         long stuckSending, long smtpUnknown, long smtpUnconfirmed,
                         long providerConfirmed, long bounced, long complaints) {}

    private final PlatformAuthorizationService identity;
    private final JdbcTemplate jdbc;

    public CreatorStudioRetentionHealthService(PlatformAuthorizationService identity,
                                               JdbcTemplate jdbc) {
        this.identity = identity;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Health inspect(Authentication auth) {
        User admin = identity.requireOwner(auth);
        if (admin.getId() == null || admin.getMergedIntoUser() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        List<Health> rows = jdbc.query(HEALTH_SQL, (rs, index) -> new Health(
            rs.getLong("total_count"),
            rs.getLong("pending_count"),
            rs.getLong("sending_count"),
            rs.getLong("stuck_sending_count"),
            rs.getLong("smtp_unknown_count"),
            rs.getLong("smtp_unconfirmed_count"),
            rs.getLong("provider_confirmed_count"),
            rs.getLong("bounced_count"),
            rs.getLong("complaint_count")
        ));
        if (rows.size() != 1) {
            throw new IllegalStateException("Retention health aggregate unavailable");
        }
        Health result = rows.get(0);
        if (result.totalNotices() != result.pending() + result.sending()
                + result.stuckSending() + result.smtpUnknown()
                + result.smtpUnconfirmed() + result.providerConfirmed()
                + result.bounced() + result.complaints()) {
            throw new IllegalStateException("Retention health buckets inconsistent");
        }
        return result;
    }
}
