package com.Brafurries.API.user;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retains only a short window of Coddy's per-tick voice XP diagnostics.
 *
 * The authoritative XP ledger, active voice sessions and aggregate daily
 * metrics are not touched. This job MUST NOT be enabled until the Database
 * migration creating idx_voice_xp_audit_retention has been applied.
 *
 * It intentionally uses autocommit per bounded batch rather than a single
 * long transaction across the complete cleanup.
 */
@Component
@ConditionalOnProperty(prefix = "app.voice-xp.audit-retention", name = "enabled", havingValue = "true")
public class VoiceXpAuditRetentionJob {
    private static final Logger log = LoggerFactory.getLogger(VoiceXpAuditRetentionJob.class);

    static final int BATCH_SIZE = 500;
    static final int MAX_BATCHES = 10;
    static final String DELETE_EXPIRED = """
        DELETE FROM voice_xp_audit_log
        WHERE created_at < CURRENT_TIMESTAMP - INTERVAL 72 HOUR
        ORDER BY created_at ASC, id ASC
        LIMIT 500
        """;

    private final JdbcTemplate jdbc;

    public VoiceXpAuditRetentionJob(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    int purgeBatch() {
        return jdbc.update(DELETE_EXPIRED);
    }

    @Scheduled(cron = "0 */5 * * * *", zone = "UTC")
    public void purgeExpiredDiagnostics() {
        int deleted = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            int count = purgeBatch();
            deleted += count;
            if (count < BATCH_SIZE) {
                break;
            }
        }
        if (deleted > 0) {
            log.info("Voice XP audit retention: removed {} diagnostics older than 72 hours", deleted);
        }
    }
}
