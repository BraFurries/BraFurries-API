package com.Brafurries.API.auth.social;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SparseAccountOwnershipGuardTest {
    @Mock JdbcTemplate jdbc;

    private SparseAccountOwnershipGuard guard() {
        return new SparseAccountOwnershipGuard(jdbc);
    }

    private Map<String, Object> column(String table, String name, int fk) {
        return Map.of("table_name", table, "column_name", name, "has_user_fk", fk);
    }

    @Test
    void refusesNonSerializableTransactionBeforeScanningOrMutating() {
        when(jdbc.queryForObject("SELECT @@tx_isolation", String.class))
            .thenReturn("REPEATABLE-READ");
        assertThrows(ResponseStatusException.class,
            () -> guard().assertOnlyTransferableReferences(10));
        verify(jdbc, never()).queryForList(anyString());
    }

    @Test
    void emptyLegacyReferenceWithoutForeignKeyIsProtectedBySerializableLockingRead() {
        when(jdbc.queryForObject("SELECT @@tx_isolation", String.class))
            .thenReturn("SERIALIZABLE");
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
            column("community_member_notes", "user_id", 0),
            column("user_social_identities", "user_id", 1),
            column("user_discord_link_challenges", "requesting_user_id", 1)));
        when(jdbc.queryForObject(
            "SELECT COUNT(*) FROM `community_member_notes` WHERE `user_id`=? FOR UPDATE",
            Long.class, 10)).thenReturn(0L);

        assertDoesNotThrow(() -> guard().assertOnlyTransferableReferences(10));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void anyUnreconciledOwnershipBlocksMergeEvenWhenFkExists() {
        when(jdbc.queryForObject("SELECT @@tx_isolation", String.class))
            .thenReturn("SERIALIZABLE");
        when(jdbc.queryForList(anyString())).thenReturn(
            List.of(column("community_member_notes", "user_id", 1)));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(10))).thenReturn(1L);

        var error = assertThrows(ResponseStatusException.class,
            () -> guard().assertOnlyTransferableReferences(10));

        assertEquals("ACCOUNT_DATA_RECONCILIATION_REQUIRED", error.getReason());
    }

    @Test
    void transferWhitelistRequiresRealUsersForeignKeyToPreventPhantoms() {
        when(jdbc.queryForObject("SELECT @@tx_isolation", String.class))
            .thenReturn("SERIALIZABLE");
        when(jdbc.queryForList(anyString())).thenReturn(
            List.of(column("user_social_identities", "user_id", 0)));

        var error = assertThrows(ResponseStatusException.class,
            () -> guard().assertOnlyTransferableReferences(10));

        assertEquals("ACCOUNT_DATA_RECONCILIATION_REQUIRED", error.getReason());
        verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), anyInt());
    }
}
