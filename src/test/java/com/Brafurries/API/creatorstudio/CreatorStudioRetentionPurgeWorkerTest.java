package com.Brafurries.API.creatorstudio;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class CreatorStudioRetentionPurgeWorkerTest {
    @Mock JdbcTemplate jdbc;
    CreatorStudioRetentionPurgeWorker worker;

    @BeforeEach void init() { worker = new CreatorStudioRetentionPurgeWorker(jdbc); }

    private void existingParent() {
        when(jdbc.query(contains("SELECT owner_user_id FROM creator_studio_commissions"),
            any(RowMapper.class),eq(101L))).thenReturn(List.of(31));
    }

    private void eligibleAfterParentLock() {
        existingParent();
        when(jdbc.query(contains("SELECT id FROM creator_studio_commissions"),
            any(RowMapper.class),eq(101L),eq(31))).thenReturn(List.of(101L));
    }

    @Test void missingParentNeverTouchesNoticesOrChildren() {
        assertFalse(worker.purgeEligibleCommission(101L));
        verify(jdbc).query(argThat((String sql) ->
            sql.contains("SELECT owner_user_id FROM creator_studio_commissions")
            && sql.contains("WHERE id=? FOR UPDATE")),
            any(RowMapper.class),eq(101L));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void lockedParentMustPassFreshAgeHoldsAndBothProviderEvidenceChecks() {
        existingParent();
        assertFalse(worker.purgeEligibleCommission(101L));
        var ordered = inOrder(jdbc);
        ordered.verify(jdbc).query(contains("SELECT owner_user_id FROM creator_studio_commissions"),
            any(RowMapper.class),eq(101L));
        ordered.verify(jdbc).query(argThat((String sql) ->
            sql.contains("FOR UPDATE")
            && sql.contains("WHERE id=? AND owner_user_id=?")
            && sql.contains("status='COMPLETED'")
            && sql.contains("completed_at IS NOT NULL")
            && sql.contains("DATE_ADD(completed_at,INTERVAL 1 YEAR)<=UTC_TIMESTAMP(6)")
            && sql.contains("creator_studio_retention_holds")
            && sql.contains("n.notice_kind='D30' AND n.status='SENT'")
            && sql.contains("n.notice_kind='D7' AND n.status='SENT'")
            && sql.contains("n.delivery_state='SERVER_ACCEPTED'")
            && sql.contains("n.delivery_evidence_at IS NOT NULL")
            && sql.contains("n.delivery_provider IS NOT NULL")
            && sql.contains("n.delivery_event_ref IS NOT NULL")
            && sql.contains("n.completed_at=creator_studio_commissions.completed_at")),
            any(RowMapper.class),eq(101L),eq(31));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void expiredCommissionDeletesOnlyOwnChildrenAfterParentLockAndRecheck() {
        eligibleAfterParentLock();
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("DELETE FROM creator_studio_commissions") ? 1 : 0;
        }).when(jdbc).update(anyString(),any(Object[].class));
        assertTrue(worker.purgeEligibleCommission(101L));

        var ordered = inOrder(jdbc);
        ordered.verify(jdbc).query(contains("SELECT owner_user_id FROM creator_studio_commissions"),
            any(RowMapper.class),eq(101L));
        ordered.verify(jdbc).query(contains("SELECT id FROM creator_studio_commissions"),
            any(RowMapper.class),eq(101L),eq(31));
        ordered.verify(jdbc).update(contains("DELETE FROM creator_studio_activity"),
            eq(31),eq(101L));
        ordered.verify(jdbc).update(contains("DELETE FROM creator_studio_reference_links"),
            eq(31),eq(101L));
        ordered.verify(jdbc).update(contains("DELETE FROM creator_studio_commission_steps"),
            eq(31),eq(101L));
        ordered.verify(jdbc).update(contains("DELETE FROM creator_studio_retention_notices"),
            eq(31),eq(101L));
        ordered.verify(jdbc).update(contains("DELETE FROM creator_studio_retention_holds"),
            eq(31),eq(101L));
        ordered.verify(jdbc).update(argThat((String sql) ->
            sql.contains("DELETE FROM creator_studio_commissions")
            && sql.contains("owner_user_id=? AND id=?")
            && sql.contains("status='COMPLETED'")
            && sql.contains("INTERVAL 1 YEAR")),eq(31),eq(101L));
    }

    @Test void rollbackIsSignaledWhenLockedParentFailsFinalEligibilityRecheck() {
        eligibleAfterParentLock();
        assertThrows(IllegalStateException.class,()->worker.purgeEligibleCommission(101L));
    }
}
