package com.Brafurries.API.platform.drive;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleDriveUploadLedgerTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final GoogleDriveUploadLedger ledger=new GoogleDriveUploadLedger(jdbc);
    private final GoogleDriveUploadLedger.Reservation reservation=new GoogleDriveUploadLedger.Reservation(
        "d15410a1-af3b-4d6f-9941-f8ade811b110",8,
        new GoogleDriveConnectionService.FolderScope("subject","root_folder"),
        "anchor_folder","child_folder","staging_folder","file_123","arte.png","image/png",5368709120L);

    @Test void unresolvedSessionsBlockAdditionalReservationsEvenAfterLocalExpiration() {
        when(jdbc.queryForList(contains("status NOT IN ('PUBLISHED','CLEANED')"),eq(String.class),eq(8)))
            .thenReturn(List.of("one","two","three"));
        assertEquals(429,assertThrows(ResponseStatusException.class,()->ledger.reserve(reservation))
            .getStatusCode().value());
        verify(jdbc).queryForObject(contains("FOR UPDATE"),eq(Integer.class),eq(8));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void dailyQuotaBlocksRepeatedNewSessions() {
        when(jdbc.queryForList(contains("status NOT IN"),eq(String.class),eq(8))).thenReturn(List.of());
        when(jdbc.queryForList(contains("INTERVAL 1 DAY"),eq(String.class),eq(8)))
            .thenReturn(java.util.Collections.nCopies(20,"session"));
        assertEquals(429,assertThrows(ResponseStatusException.class,()->ledger.reserve(reservation))
            .getStatusCode().value());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }

    @Test void expiredOrAlreadyTransitionedReservationCannotBeOpened() {
        when(jdbc.update(contains("status='OPEN'"),eq(reservation.id()),eq(8))).thenReturn(0);
        assertFalse(ledger.markOpen(reservation));
        verify(jdbc,never()).update(contains("INSERT INTO platform_google_drive_upload_audit"),
            eq(reservation.id()),eq(8),eq("OPENED"));
    }
}
