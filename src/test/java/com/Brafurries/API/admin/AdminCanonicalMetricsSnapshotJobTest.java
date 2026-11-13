package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.sql.Date;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class AdminCanonicalMetricsSnapshotJobTest {
    @Mock JdbcTemplate jdbc;
    @Mock PlatformAuthorizationService platform;
    @Mock AdminCoddyReachService reach;

    @Test void samplesOnlyOfficialTenantFromHealthyReconciledDiscordGuild() {
        when(platform.officialCommunityId()).thenReturn(41);
        when(jdbc.queryForList(contains("FROM community_discord WHERE community_id"),eq(41)))
            .thenReturn(List.of(Map.of(
                "guild_id",222L,"active",true,"membership_sync_state","HEALTHY",
                "last_full_reconciliation_at",LocalDateTime.of(2026,11,12,12,0))));
        when(jdbc.queryForObject(contains("COUNT(DISTINCT user_id)"),
            eq(Long.class),eq(41),eq(222L))).thenReturn(4000L);
        when(reach.read()).thenReturn(new AdminCoddyReachService.ReachSnapshot(10000L,5L));
        when(jdbc.queryForObject(contains("WHERE active=1"),eq(Long.class))).thenReturn(7L);

        new AdminCanonicalMetricsSnapshotJob(jdbc,platform,reach).captureOnce();

        verify(jdbc).update(contains("INSERT INTO stats_admin_daily_snapshots"),
            eq(41),any(Date.class),eq(4000L),eq(1),
            eq(10000L),eq(5L),eq(7L),any(LocalDateTime.class));
    }

    @Test void missingOrUnhealthyGuildDoesNotClaimZeroMembers() {
        when(platform.officialCommunityId()).thenReturn(41);
        when(jdbc.queryForList(contains("FROM community_discord WHERE community_id"),eq(41)))
            .thenReturn(List.of());
        when(reach.read()).thenReturn(new AdminCoddyReachService.ReachSnapshot(0L,0L));
        when(jdbc.queryForObject(contains("WHERE active=1"),eq(Long.class))).thenReturn(7L);

        new AdminCanonicalMetricsSnapshotJob(jdbc,platform,reach).captureOnce();

        verify(jdbc,never()).queryForObject(contains("COUNT(DISTINCT user_id)"),
            eq(Long.class),any(),any());
        verify(jdbc).update(contains("INSERT INTO stats_admin_daily_snapshots"),
            eq(41),any(Date.class),isNull(),eq(0),
            isNull(),eq(0L),eq(7L),any(LocalDateTime.class));
    }
}
