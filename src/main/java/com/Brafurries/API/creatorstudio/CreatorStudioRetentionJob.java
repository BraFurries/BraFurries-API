package com.Brafurries.API.creatorstudio;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DESTRUCTIVE job is opt-in with TWO independent gates:
 * purge-enabled AND notification-delivery-ready.
 *
 * Both default FALSE during the pilot. Do not enable without tested 30/7-day
 * notification delivery, data export availability and reviewed operations.
 * Selecting candidates never mutates anything.
 */
@Component
@ConditionalOnProperty(prefix = "app.creator-studio.retention",
    name = {"purge-enabled","notification-delivery-ready"}, havingValue = "true")
public class CreatorStudioRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(CreatorStudioRetentionJob.class);
    private static final int MAX_PER_DAY = 50;
    private final JdbcTemplate jdbc;
    private final CreatorStudioRetentionPurgeWorker worker;

    public CreatorStudioRetentionJob(JdbcTemplate jdbc, CreatorStudioRetentionPurgeWorker worker) {
        this.jdbc = jdbc;
        this.worker = worker;
    }

    @Scheduled(cron = "0 10 4 * * *", zone = "UTC")
    public void purgeExpired() {
        List<Long> candidates = jdbc.query("""
            SELECT id FROM creator_studio_commissions
             WHERE status='COMPLETED' AND completed_at IS NOT NULL
               AND DATE_ADD(completed_at,INTERVAL 1 YEAR)<=UTC_TIMESTAMP(6)
             ORDER BY completed_at ASC,id ASC LIMIT 50
            """, (rs, n) -> rs.getLong("id"));
        int removed = 0;
        for (long id : candidates) {
            if (worker.purgeEligibleCommission(id)) removed++;
        }
        if (removed > 0) log.info("Creator Studio retention: {} expired commissions purged", removed);
        if (removed == MAX_PER_DAY) log.warn(
            "Creator Studio retention reached daily cap {}; remaining backlog must be reviewed",
            MAX_PER_DAY);
    }
}
