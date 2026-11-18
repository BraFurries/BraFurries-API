package com.Brafurries.API.creatorstudio;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs only after the retention job's separate feature gates are enabled.
 * Each deletion rechecks status and elapsed calendar year under a row lock.
 * Children and parent are deleted in the SAME transaction; no cascade DDL.
 */
@Service
public class CreatorStudioRetentionPurgeWorker {
    private final JdbcTemplate jdbc;

    public CreatorStudioRetentionPurgeWorker(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public boolean purgeEligibleCommission(long id) {
        List<Integer> owners = jdbc.query("""
            SELECT owner_user_id FROM creator_studio_commissions
             WHERE id=? AND status='COMPLETED'
               AND completed_at IS NOT NULL
               AND completed_at<=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 364 DAY)
               AND DATE_ADD(completed_at,INTERVAL 1 YEAR)<=UTC_TIMESTAMP(6)
               AND NOT EXISTS (
                   SELECT 1 FROM creator_studio_retention_holds h
                    WHERE h.owner_user_id=creator_studio_commissions.owner_user_id
                      AND h.commission_id=creator_studio_commissions.id
                      AND h.active=1
               )
               AND EXISTS (
                   SELECT 1 FROM creator_studio_retention_notices n
                    WHERE n.owner_user_id=creator_studio_commissions.owner_user_id
                      AND n.commission_id=creator_studio_commissions.id
                      AND n.completed_at=creator_studio_commissions.completed_at
                      AND n.notice_kind='D30' AND n.status='SENT'
               )
               AND EXISTS (
                   SELECT 1 FROM creator_studio_retention_notices n
                    WHERE n.owner_user_id=creator_studio_commissions.owner_user_id
                      AND n.commission_id=creator_studio_commissions.id
                      AND n.completed_at=creator_studio_commissions.completed_at
                      AND n.notice_kind='D7' AND n.status='SENT'
               )
             FOR UPDATE
            """, (rs, n) -> rs.getInt("owner_user_id"), id);
        if (owners.isEmpty()) return false;
        int ownerId = owners.get(0);
        // Exact composite owner+commission conditions are important here:
        // no other artist's data is ever addressed by a child deletion.
        jdbc.update("""
            DELETE FROM creator_studio_activity
             WHERE owner_user_id=? AND commission_id=?
            """, ownerId, id);
        jdbc.update("""
            DELETE FROM creator_studio_reference_links
             WHERE owner_user_id=? AND commission_id=?
            """, ownerId, id);
        jdbc.update("""
            DELETE FROM creator_studio_commission_steps
             WHERE owner_user_id=? AND commission_id=?
            """, ownerId, id);
        // Historical notice rows (including old completion epochs) and
        // released holds must be removed before the parent due to RESTRICT FKs.
        jdbc.update("""
            DELETE FROM creator_studio_retention_notices
             WHERE owner_user_id=? AND commission_id=?
            """, ownerId, id);
        jdbc.update("""
            DELETE FROM creator_studio_retention_holds
             WHERE owner_user_id=? AND commission_id=? AND active=0
            """, ownerId, id);
        int parent = jdbc.update("""
            DELETE FROM creator_studio_commissions
             WHERE owner_user_id=? AND id=? AND status='COMPLETED'
               AND completed_at IS NOT NULL
               AND completed_at<=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 364 DAY)
               AND DATE_ADD(completed_at,INTERVAL 1 YEAR)<=UTC_TIMESTAMP(6)
               AND NOT EXISTS (
                   SELECT 1 FROM creator_studio_retention_holds h
                    WHERE h.owner_user_id=creator_studio_commissions.owner_user_id
                      AND h.commission_id=creator_studio_commissions.id
                      AND h.active=1
               )
               AND EXISTS (
                   SELECT 1 FROM creator_studio_retention_notices n
                    WHERE n.owner_user_id=creator_studio_commissions.owner_user_id
                      AND n.commission_id=creator_studio_commissions.id
                      AND n.completed_at=creator_studio_commissions.completed_at
                      AND n.notice_kind='D30' AND n.status='SENT'
               )
               AND EXISTS (
                   SELECT 1 FROM creator_studio_retention_notices n
                    WHERE n.owner_user_id=creator_studio_commissions.owner_user_id
                      AND n.commission_id=creator_studio_commissions.id
                      AND n.completed_at=creator_studio_commissions.completed_at
                      AND n.notice_kind='D7' AND n.status='SENT'
               )
            """, ownerId, id);
        if (parent != 1) {
            throw new IllegalStateException("Commission changed during locked retention purge");
        }
        return true;
    }
}
