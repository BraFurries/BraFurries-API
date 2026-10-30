package com.Brafurries.API.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The API owns administrative ledger retention, while Coddy owns Discord role
 * effects. Do not delete an ACTIVE grant or any grant whose Discord state has
 * not been reconciled successfully.
 *
 * updated_at is the only reliable end-of-lifecycle timestamp available in the
 * unified ledger. It is updated on terminal status transitions and later sync
 * completion; using it prevents premature deletion even after slow retries.
 */
@Component
public class TemporaryRoleGrantRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(TemporaryRoleGrantRetentionJob.class);
    static final int BATCH_SIZE = 250;
    static final int MAX_BATCHES = 10;

    private final JdbcTemplate jdbc;

    public TemporaryRoleGrantRetentionJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Calendar-month retention, evaluated in the database's UTC clock.
     * The WHERE clause is rechecked atomically by MariaDB during DELETE.
     * No API endpoint exposes this maintenance job to users or tenants.
     */
    int purgeBatch() {
        return jdbc.update("""
            DELETE FROM user_temp_roles
            WHERE status IN ('EXPIRED', 'CANCELLED')
              AND sync_status = 'APPLIED'
              AND updated_at IS NOT NULL
              AND updated_at < UTC_TIMESTAMP(6) - INTERVAL 1 MONTH
            ORDER BY updated_at ASC, id ASC
            LIMIT 250
            """);
    }

    @Scheduled(cron = "0 20 3 * * *", zone = "UTC")
    public void purgeCompletedGrants() {
        int removed = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int rows = purgeBatch();
            removed += rows;
            if (rows < BATCH_SIZE) break;
        }
        if (removed > 0) {
            log.info("Temporary role ledger retention: purged {} completed records older than one calendar month", removed);
        }
    }
}
