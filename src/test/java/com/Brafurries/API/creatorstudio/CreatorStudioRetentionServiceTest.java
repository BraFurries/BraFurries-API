package com.Brafurries.API.creatorstudio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class CreatorStudioRetentionServiceTest {
    @Mock CreatorStudioAccessService access;
    @Mock JdbcTemplate jdbc;
    @Mock Authentication auth;
    CreatorStudioRetentionService service;

    @BeforeEach void init() {
        service = new CreatorStudioRetentionService(access,jdbc);
    }

    @Test
    void eligibilityOnlyDependsOnLastCompletionAndNeverTouchesActiveCommissions() {
        when(access.actorId(auth)).thenReturn(31);
        var result = service.myUpcomingRetention(auth);
        assertEquals("12_MONTHS_AFTER_LAST_COMPLETION",result.policy());
        assertEquals(30,result.advanceNoticeDays());
        assertEquals(7,result.finalNoticeDays());
        assertFalse(result.automaticDeletionEnabled());
        verify(jdbc).query(argThat(sql ->
            sql.contains("owner_user_id=?")
            && sql.contains("status='COMPLETED'")
            && sql.contains("completed_at IS NOT NULL")
            && sql.contains("INTERVAL 1 YEAR")
            && sql.contains("INTERVAL 30 DAY")
            && sql.contains("LIMIT 100")), any(RowMapper.class), eq(31));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test
    void noPlanCheckOrForeignOwnerIdIsEverUsedForRetainedPrivateData() {
        when(access.actorId(auth)).thenReturn(9);
        service.myUpcomingRetention(auth);
        verify(access).actorId(auth);
        verify(access,never()).requirePro(any());
        verify(jdbc).query(anyString(),any(RowMapper.class),eq(9));
    }
}
