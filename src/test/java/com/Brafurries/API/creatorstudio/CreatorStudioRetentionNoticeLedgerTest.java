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

@ExtendWith(MockitoExtension.class)
class CreatorStudioRetentionNoticeLedgerTest {
    @Mock JdbcTemplate jdbc;
    CreatorStudioRetentionNoticeLedger ledger;
    @BeforeEach void setUp() { ledger=new CreatorStudioRetentionNoticeLedger(jdbc); }

    @Test void disallowsUnsupportedReminderKinds() {
        assertThrows(IllegalArgumentException.class, () -> ledger.enqueue("UNKNOWN"));
        verifyNoInteractions(jdbc);
    }

    @Test void queueDeduplicatesPerCompletionEpochAndRequiresVerifiedEmail() {
        ledger.enqueue("D30");
        verify(jdbc).update(argThat(sql ->
            sql.contains("ON DUPLICATE KEY UPDATE id=id") &&
            sql.contains("email_verified_at IS NOT NULL") &&
            sql.contains("u.merged_into_user_id IS NULL") &&
            sql.contains("c.status='COMPLETED'") &&
            sql.contains("c.completed_at IS NOT NULL") &&
            sql.contains("h.active=1") &&
            sql.contains("DATE_ADD(c.completed_at,INTERVAL 1 YEAR)") &&
            sql.contains("INTERVAL 30 DAY") &&
            sql.contains("INTERVAL 7 DAY")),
            eq("D30"),eq("D30"),eq("D30"));
    }

    @Test void onlyOneWorkerCanClaimPendingNoticeForUnchangedCommission() {
        doReturn(1).when(jdbc).update(contains("UPDATE creator_studio_retention_notices n"),
            eq(42L));
        when(jdbc.query(contains("SELECT n.id,n.owner_user_id"),
            any(RowMapper.class),eq(42L))).thenReturn(List.of(
                new CreatorStudioRetentionNoticeLedger.ClaimedNotice(
                    42L,31,88L,"D7","artist@example.invalid",
                    LocalDateTime.of(2025,11,17,12,0))));
        var claimed=ledger.claim(42L);
        assertNotNull(claimed);
        assertEquals(31,claimed.ownerUserId());
        verify(jdbc).update(argThat(sql ->
                sql.contains("n.status='PENDING'") &&
                sql.contains("n.recipient_email=u.email") &&
                sql.contains("SET n.status='SENDING'") &&
                sql.contains("c.completed_at=n.completed_at") &&
                sql.contains("u.email_verified_at IS NOT NULL") &&
                sql.contains("h.commission_id IS NULL") &&
                sql.contains("c.status='COMPLETED'")),
            eq(42L));
    }

    @Test void changedOrAlreadyClaimedNoticesAreNeverRetried() {
        assertNull(ledger.claim(42L));
        verify(jdbc,never()).query(anyString(),any(RowMapper.class),any(Object[].class));
    }

    @Test void unknownSmtpOutcomeIsDistinctFromAcceptedSmtp() {
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("status='SENT'") ? 1 : 0;
        }).when(jdbc).update(anyString(),any(Object[].class));
        ledger.markDeliveryUnknown(44L);
        verify(jdbc).update(argThat(sql ->
            sql.contains("status='UNKNOWN'") &&
            sql.contains("status='SENDING'") &&
            sql.contains("SMTP_OUTCOME_UNKNOWN")),eq(44L));
        ledger.markSmtpAccepted(45L);
        verify(jdbc).update(argThat(sql ->
            sql.contains("status='SENT'") &&
            sql.contains("sent_at=UTC_TIMESTAMP(6)") &&
            sql.contains("status='SENDING'")),eq(45L));
    }
}
