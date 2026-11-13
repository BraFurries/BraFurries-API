package com.Brafurries.API.admin.dto;

import java.util.List;

/** Stable analytical contract. Null means unknown/incomparable, NEVER zero. */
public final class AdminCanonicalMetricsDtos {
    private AdminCanonicalMetricsDtos() {}

    public record DailySnapshot(
        String date,
        Long brafurriesMembers,
        Long coddyReach,
        Long reconciledServers,
        Long activeServers,
        String sampledAt
    ) {}

    public record CommandDay(String date, Long executions) {}
    public record CommandRanking(String command, long executions) {}

    public record Summary(
        Long brafurriesMembers,
        Long brafurriesDelta,
        Long coddyReach,
        Long coddyReachDelta,
        long reconciledServers,
        long activeServers,
        Long commandsExecuted,
        boolean commandCoverageComplete,
        Long previousCommandsExecuted,
        Double commandsGrowthPercent
    ) {}

    public record Report(
        String period,
        String startDate,
        String endDate,
        String commandTrackingSince,
        String snapshotTrackingSince,
        String lastSnapshotAt,
        Summary summary,
        List<DailySnapshot> dailySnapshots,
        List<CommandDay> commandUsage,
        List<CommandRanking> topCommands
    ) {}
}
