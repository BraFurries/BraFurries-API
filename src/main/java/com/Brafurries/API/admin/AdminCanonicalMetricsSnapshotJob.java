package com.Brafurries.API.admin;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Records actual observations after Database migration V20261112_002.
 * Existing legacy last-used timestamps cannot produce historical membership.
 * Safe on multiple API instances: a newer hourly sample wins for each UTC day.
 */
@Component
@ConditionalOnProperty(prefix="app.metrics.snapshots",name="enabled",havingValue="true")
public class AdminCanonicalMetricsSnapshotJob {
    private static final Logger log=LoggerFactory.getLogger(AdminCanonicalMetricsSnapshotJob.class);
    private final JdbcTemplate jdbc;
    private final PlatformAuthorizationService platform;
    private final AdminCoddyReachService reach;

    public AdminCanonicalMetricsSnapshotJob(
        JdbcTemplate jdbc, PlatformAuthorizationService platform, AdminCoddyReachService reach
    ) {
        this.jdbc=jdbc;
        this.platform=platform;
        this.reach=reach;
    }

    @Scheduled(initialDelayString="${app.metrics.snapshots.initial-delay-ms:60000}",
               fixedDelayString="${app.metrics.snapshots.interval-ms:3600000}")
    public void capture() {
        try {
            captureOnce();
        } catch (RuntimeException ex) {
            log.error("Canonical administrative snapshot not recorded; no substitute zeros written",ex);
        }
    }

    private static boolean isEnabled(Object value) {
        return Boolean.TRUE.equals(value) ||
            (value instanceof Number number && number.intValue()==1);
    }

    void captureOnce() {
        int officialCommunityId=platform.officialCommunityId();
        LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);
        LocalDate date=now.toLocalDate();
        // The actual official tenant is resolved by the platform control plane.
        List<Map<String,Object>> guilds=jdbc.queryForList("""
            SELECT guild_id,active,membership_sync_state,last_full_reconciliation_at
              FROM community_discord WHERE community_id=?
            """,officialCommunityId);
        boolean healthy=guilds.size()==1
            && isEnabled(guilds.getFirst().get("active"))
            && "HEALTHY".equals(guilds.getFirst().get("membership_sync_state"))
            && guilds.getFirst().get("last_full_reconciliation_at")!=null;
        Long members=null;
        if(healthy) {
            Long guildId=((Number)guilds.getFirst().get("guild_id")).longValue();
            members=jdbc.queryForObject("""
                SELECT COUNT(DISTINCT user_id)
                  FROM community_network_member_status
                 WHERE community_id=? AND network_type='DISCORD'
                   AND external_network_id=? AND is_present=1
                """,Long.class,officialCommunityId,guildId);
        }
        var audience=reach.read();
        Long validReach=audience.reconciledServers()>0?audience.uniqueMembers():null;
        Long activeGuilds=jdbc.queryForObject(
            "SELECT COUNT(*) FROM community_discord WHERE active=1",Long.class);
        jdbc.update("""
            INSERT INTO stats_admin_daily_snapshots
                (community_id,metric_date,brafurries_members,brafurries_reconciled,
                 reach_unique_members,reach_reconciled_guilds,reach_active_guilds,sampled_at)
            VALUES (?,?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE
                brafurries_members=IF(VALUES(sampled_at)>=sampled_at,
                    VALUES(brafurries_members),brafurries_members),
                brafurries_reconciled=IF(VALUES(sampled_at)>=sampled_at,
                    VALUES(brafurries_reconciled),brafurries_reconciled),
                reach_unique_members=IF(VALUES(sampled_at)>=sampled_at,
                    VALUES(reach_unique_members),reach_unique_members),
                reach_reconciled_guilds=IF(VALUES(sampled_at)>=sampled_at,
                    VALUES(reach_reconciled_guilds),reach_reconciled_guilds),
                reach_active_guilds=IF(VALUES(sampled_at)>=sampled_at,
                    VALUES(reach_active_guilds),reach_active_guilds),
                sampled_at=GREATEST(sampled_at,VALUES(sampled_at))
            """,officialCommunityId,java.sql.Date.valueOf(date),members,healthy?1:0,
            validReach,audience.reconciledServers(),activeGuilds==null?0:activeGuilds,now);
    }
}
