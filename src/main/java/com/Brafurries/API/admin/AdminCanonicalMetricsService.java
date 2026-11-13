package com.Brafurries.API.admin;

import static com.Brafurries.API.admin.dto.AdminCanonicalMetricsDtos.*;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only, global ADMIN-only analytics.
 * Legacy config_command_uses is intentionally NOT consulted: its one row per
 * (user, command) and last-used timestamp cannot count executions.
 * No historical membership or audience data is inferred from current totals.
 */
@Service
public class AdminCanonicalMetricsService {
    private final JdbcTemplate jdbc;
    private final PlatformAuthorizationService platform;

    public AdminCanonicalMetricsService(JdbcTemplate jdbc,PlatformAuthorizationService platform) {
        this.jdbc=jdbc;
        this.platform=platform;
    }

    @Transactional(readOnly=true)
    public Report read(String requestedPeriod) {
        String period=requestedPeriod==null?"7d":requestedPeriod.trim().toLowerCase(Locale.ROOT);
        LocalDate today=LocalDate.now(ZoneOffset.UTC);
        LocalDate start=switch(period) {
            case "7d" -> today.minusDays(6);
            case "30d" -> today.minusDays(29);
            case "year" -> today.withDayOfYear(1);
            default -> throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,"Período inválido. Use 7d, 30d ou year");
        };
        int communityId=platform.officialCommunityId();
        List<DailySnapshot> observed=jdbc.query("""
            SELECT metric_date,brafurries_members,reach_unique_members,
                   reach_reconciled_guilds,reach_active_guilds,sampled_at
              FROM stats_admin_daily_snapshots
             WHERE community_id=? AND metric_date BETWEEN ? AND ?
             ORDER BY metric_date
            """,(rs,i)->new DailySnapshot(
                rs.getDate("metric_date").toLocalDate().toString(),
                (Long)rs.getObject("brafurries_members",Long.class),
                (Long)rs.getObject("reach_unique_members",Long.class),
                rs.getLong("reach_reconciled_guilds"),
                rs.getLong("reach_active_guilds"),
                rs.getTimestamp("sampled_at").toLocalDateTime()+"Z"
            ),communityId,Date.valueOf(start),Date.valueOf(today));

        Map<LocalDate,DailySnapshot> daily=new HashMap<>();
        for(DailySnapshot point:observed) daily.put(LocalDate.parse(point.date()),point);
        List<DailySnapshot> timeline=new ArrayList<>();
        for(LocalDate date=start;!date.isAfter(today);date=date.plusDays(1)) {
            DailySnapshot point=daily.get(date);
            timeline.add(point==null
                ? new DailySnapshot(date.toString(),null,null,null,null,null)
                : point);
        }
        DailySnapshot last=observed.isEmpty()?null:observed.getLast();
        List<DailySnapshot> baseline=jdbc.query("""
            SELECT metric_date,brafurries_members,reach_unique_members,
                   reach_reconciled_guilds,reach_active_guilds,sampled_at
              FROM stats_admin_daily_snapshots
             WHERE community_id=? AND metric_date=?
            """,(rs,i)->new DailySnapshot(
                rs.getDate("metric_date").toLocalDate().toString(),
                rs.getObject("brafurries_members",Long.class),
                rs.getObject("reach_unique_members",Long.class),
                rs.getLong("reach_reconciled_guilds"),
                rs.getLong("reach_active_guilds"),
                rs.getTimestamp("sampled_at").toLocalDateTime()+"Z"
            ),communityId,Date.valueOf(start.minusDays(1)));
        DailySnapshot before=baseline.isEmpty()?null:baseline.getFirst();

        Timestamp recordedFirst=jdbc.queryForObject(
            "SELECT MIN(completed_at) FROM stats_coddy_command_executions",Timestamp.class);
        LocalDateTime first=recordedFirst==null?null:recordedFirst.toLocalDateTime();
        LocalDate firstDay=first==null?null:first.toLocalDate();
        LocalDate previousStart=start.minusDays(java.time.temporal.ChronoUnit.DAYS.between(start,today)+1);
        boolean complete=first!=null && first.toLocalDate().isBefore(start);
        Long executions=first==null?null:countCommands(start,today.plusDays(1));
        Long previous=null;
        Double growth=null;
        if(first!=null && first.toLocalDate().isBefore(previousStart)) {
            previous=countCommands(previousStart,start);
            if(previous>0) growth=Math.round(((executions-previous)*10000.0)/previous)/100.0;
        }

        List<CommandDay> reported=jdbc.query("""
            SELECT DATE(completed_at) AS day, COUNT(*) AS executions
              FROM stats_coddy_command_executions
             WHERE completed_at>=? AND completed_at<?
             GROUP BY DATE(completed_at) ORDER BY day
            """,(rs,i)->new CommandDay(
                rs.getDate("day").toLocalDate().toString(),rs.getLong("executions")
            ),start.atStartOfDay(),today.plusDays(1).atStartOfDay());
        Map<String,Long> commandDays=new HashMap<>();
        for(CommandDay day:reported)commandDays.put(day.date(),day.executions());
        List<CommandDay> usage=new ArrayList<>();
        for(LocalDate date=start;!date.isAfter(today);date=date.plusDays(1)) {
            Long total=firstDay==null||date.isBefore(firstDay)
                ? null : commandDays.getOrDefault(date.toString(),0L);
            usage.add(new CommandDay(date.toString(),total));
        }
        List<CommandRanking> top=first==null?List.of():jdbc.query("""
            SELECT command_name,COUNT(*) AS uses
              FROM stats_coddy_command_executions
             WHERE completed_at>=? AND completed_at<?
             GROUP BY command_name
             ORDER BY uses DESC,command_name ASC LIMIT 10
            """,(rs,i)->new CommandRanking(rs.getString("command_name"),rs.getLong("uses")),
            start.atStartOfDay(),today.plusDays(1).atStartOfDay());

        Summary summary=new Summary(
            last==null?null:last.brafurriesMembers(),
            delta(last==null?null:last.brafurriesMembers(),
                  before==null?null:before.brafurriesMembers()),
            last==null?null:last.coddyReach(),
            delta(last==null?null:last.coddyReach(),before==null?null:before.coddyReach()),
            last==null||last.reconciledServers()==null?0:last.reconciledServers(),
            last==null||last.activeServers()==null?0:last.activeServers(),
            executions,complete,previous,growth
        );

        Date firstSnapshot=jdbc.queryForObject("""
            SELECT MIN(metric_date) FROM stats_admin_daily_snapshots WHERE community_id=?
            """,Date.class,communityId);
        return new Report(
            period,start.toString(),today.toString(),
            first==null?null:first+"Z",
            firstSnapshot==null?null:firstSnapshot.toString(),
            last==null?null:last.sampledAt(),
            summary,timeline,usage,top);
    }

    private long countCommands(LocalDate from,LocalDate toExclusive) {
        Long count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM stats_coddy_command_executions
             WHERE completed_at>=? AND completed_at<?
            """,Long.class,from.atStartOfDay(),toExclusive.atStartOfDay());
        return count==null?0:count;
    }

    private static Long delta(Long latest,Long baseline) {
        return latest==null||baseline==null?null:latest-baseline;
    }
}
