package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AdminCanonicalMetricsServiceTest {
    @Mock JdbcTemplate jdbc;
    @Mock PlatformAuthorizationService platform;
    AdminCanonicalMetricsService service;

    @BeforeEach void setup() {
        service=new AdminCanonicalMetricsService(jdbc,platform);
    }

    @Test void invalidPeriodFailsBeforeDatabaseOrTenantLookups() {
        var ex=assertThrows(ResponseStatusException.class,()->service.read("nonsense"));
        assertEquals(HttpStatus.BAD_REQUEST,ex.getStatusCode());
        verifyNoInteractions(jdbc,platform);
    }

    @Test void unknownHistoryIsReportedAsMissingNotFabricatedZeroOrGrowth() {
        when(platform.officialCommunityId()).thenReturn(7);
        var report=service.read("7d");
        assertEquals("7d",report.period());
        assertEquals(7,report.dailySnapshots().size());
        assertEquals(7,report.commandUsage().size());
        assertNull(report.summary().brafurriesMembers());
        assertNull(report.summary().brafurriesDelta());
        assertNull(report.summary().coddyReach());
        assertNull(report.summary().commandsExecuted());
        assertNull(report.summary().commandsGrowthPercent());
        assertNull(report.commandTrackingSince());
        assertTrue(report.topCommands().isEmpty());
        assertTrue(report.dailySnapshots().stream().allMatch(p -> p.brafurriesMembers()==null));
        assertTrue(report.commandUsage().stream().allMatch(p -> p.executions()==null));
    }

    @Test void countsExactlyImmutableCompletionsWhenTelemetryStarted() {
        when(platform.officialCommunityId()).thenReturn(7);
        when(jdbc.queryForObject(contains("SELECT MIN(completed_at)"),eq(Timestamp.class)))
            .thenReturn(Timestamp.valueOf(LocalDateTime.now().minusDays(3)));
        when(jdbc.queryForObject(contains("SELECT COUNT(*) FROM stats_coddy_command_executions"),
            eq(Long.class),any(LocalDateTime.class),any(LocalDateTime.class)))
            .thenReturn(15L);
        var report=service.read("7d");
        assertEquals(15L,report.summary().commandsExecuted());
        assertFalse(report.summary().commandCoverageComplete());
        assertNull(report.summary().commandsGrowthPercent());
        assertNotNull(report.commandTrackingSince());
    }
}
