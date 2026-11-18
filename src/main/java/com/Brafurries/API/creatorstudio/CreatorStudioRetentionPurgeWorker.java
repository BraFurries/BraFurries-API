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
               AND DATE_ADD(completed_at,INTERVAL 1 YEAR)<=UTC_TIMESTAMP(6)
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
        int parent = jdbc.update("""
            DELETE FROM creator_studio_commissions
             WHERE owner_user_id=? AND id=? AND status='COMPLETED'
               AND completed_at IS NOT NULL
               AND DATE_ADD(completed_at,INTERVAL 1 YEAR)<=UTC_TIMESTAMP(6)
            """, ownerId, id);
        if (parent != 1) {
            throw new IllegalStateException("Commission changed during locked retention purge");
        }
        return true;
    }
}
