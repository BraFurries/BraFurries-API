package com.Brafurries.API.creatorstudio;

import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable delivery ledger, scoped to an individual completion epoch.
 * Unknown outcomes NEVER retry automatically: SMTP acceptance and final inbox
 * delivery are different facts. Human review is needed to resolve ambiguity.
 */
@Service
public class CreatorStudioRetentionNoticeLedger {
    public record ClaimedNotice(long id, int ownerUserId, long commissionId,
                                String kind, String email, LocalDateTime completedAtUtc) {}

    private final JdbcTemplate jdbc;

    public CreatorStudioRetentionNoticeLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public int enqueue(String kind) {
        if (!"D30".equals(kind) && !"D7".equals(kind))
            throw new IllegalArgumentException("Unsupported retention notice");
        return jdbc.update("""
            INSERT INTO creator_studio_retention_notices
                (owner_user_id,commission_id,completed_at,notice_kind,status)
            SELECT c.owner_user_id,c.id,c.completed_at,?,'PENDING'
              FROM creator_studio_commissions c
              JOIN users u ON u.id=c.owner_user_id
             WHERE c.status='COMPLETED' AND c.completed_at IS NOT NULL
               AND u.merged_into_user_id IS NULL AND u.email_verified_at IS NOT NULL
               AND u.email IS NOT NULL AND u.email<>''
               AND c.completed_at<=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 334 DAY)
               AND ((?='D30' AND DATE_ADD(c.completed_at,INTERVAL 1 YEAR)
                        <=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 30 DAY)
                      AND DATE_ADD(c.completed_at,INTERVAL 1 YEAR)
                        >DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 7 DAY))
                    OR (?='D7' AND DATE_ADD(c.completed_at,INTERVAL 1 YEAR)
                        <=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 7 DAY)
                      AND DATE_ADD(c.completed_at,INTERVAL 1 YEAR)>UTC_TIMESTAMP(6)))
               AND NOT EXISTS (
                   SELECT 1 FROM creator_studio_retention_holds h
                    WHERE h.owner_user_id=c.owner_user_id
                      AND h.commission_id=c.id AND h.active=1
               )
            ON DUPLICATE KEY UPDATE id=id
            """, kind,kind,kind);
    }

    @Transactional(readOnly = true)
    public List<Long> pendingIds() {
        return jdbc.query("""
            SELECT n.id
              FROM creator_studio_retention_notices n
              JOIN creator_studio_commissions c
                ON c.id=n.commission_id AND c.owner_user_id=n.owner_user_id
              JOIN users u ON u.id=n.owner_user_id
              LEFT JOIN creator_studio_retention_holds h
                ON h.owner_user_id=n.owner_user_id
               AND h.commission_id=n.commission_id AND h.active=1
             WHERE n.status='PENDING' AND c.status='COMPLETED'
               AND c.completed_at=n.completed_at
               AND u.merged_into_user_id IS NULL
               AND u.email_verified_at IS NOT NULL AND u.email IS NOT NULL
               AND u.email<>'' AND h.commission_id IS NULL
               AND DATE_ADD(c.completed_at,INTERVAL 1 YEAR)>UTC_TIMESTAMP(6)
               AND ((n.notice_kind='D30'
                      AND DATE_ADD(c.completed_at,INTERVAL 1 YEAR)
                        >DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 7 DAY))
                    OR n.notice_kind='D7')
             ORDER BY n.created_at,n.id LIMIT 30
            """, (rs, n) -> rs.getLong("id"));
    }

    /**
     * Atomic claim prevents concurrent schedulers from emailing the same row.
     * No recovery of stale SENDING rows is automated, preventing ambiguous
     * SMTP outcomes from sending duplicates.
     */
    @Transactional
    public ClaimedNotice claim(long noticeId) {
        int claimed = jdbc.update("""
            UPDATE creator_studio_retention_notices n
            JOIN creator_studio_commissions c
              ON c.owner_user_id=n.owner_user_id AND c.id=n.commission_id
            JOIN users u ON u.id=n.owner_user_id
            LEFT JOIN creator_studio_retention_holds h
              ON h.owner_user_id=n.owner_user_id
             AND h.commission_id=n.commission_id AND h.active=1
               SET n.status='SENDING',n.claimed_at=UTC_TIMESTAMP(6),
                   n.attempts=n.attempts+1,n.last_error_code=NULL,
                   n.recipient_email=u.email
             WHERE n.id=? AND n.status='PENDING'
               AND c.status='COMPLETED' AND c.completed_at=n.completed_at
               AND h.commission_id IS NULL
               AND u.merged_into_user_id IS NULL
               AND u.email_verified_at IS NOT NULL AND u.email IS NOT NULL
               AND u.email<>''
               AND DATE_ADD(c.completed_at,INTERVAL 1 YEAR)>UTC_TIMESTAMP(6)
               AND ((n.notice_kind='D30'
                      AND DATE_ADD(c.completed_at,INTERVAL 1 YEAR)
                        >DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 7 DAY))
                    OR n.notice_kind='D7')
            """, noticeId);
        if (claimed != 1) return null;
        List<ClaimedNotice> found = jdbc.query("""
            SELECT n.id,n.owner_user_id,n.commission_id,n.notice_kind,
                   n.completed_at,n.recipient_email AS email
              FROM creator_studio_retention_notices n
             WHERE n.id=? AND n.status='SENDING'
            """, (rs, i) -> new ClaimedNotice(
                rs.getLong("id"), rs.getInt("owner_user_id"),
                rs.getLong("commission_id"), rs.getString("notice_kind"),
                rs.getString("email"),
                rs.getObject("completed_at",LocalDateTime.class)
            ), noticeId);
        if (found.size()!=1) throw new IllegalStateException("Notice claim inconsistent");
        return found.get(0);
    }

    /** SENT means accepted by SMTP, NOT guaranteed delivered/read by the user. */
    @Transactional
    public void markSmtpAccepted(long noticeId) {
        int rows = jdbc.update("""
            UPDATE creator_studio_retention_notices
               SET status='SENT',sent_at=UTC_TIMESTAMP(6)
             WHERE id=? AND status='SENDING'
            """, noticeId);
        if (rows!=1) throw new IllegalStateException("Notice outcome changed");
    }

    @Transactional
    public void markDeliveryUnknown(long noticeId) {
        jdbc.update("""
            UPDATE creator_studio_retention_notices
               SET status='UNKNOWN',last_error_code='SMTP_OUTCOME_UNKNOWN'
             WHERE id=? AND status='SENDING'
            """, noticeId);
    }
}
