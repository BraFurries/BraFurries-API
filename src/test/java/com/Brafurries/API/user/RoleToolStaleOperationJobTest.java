package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RoleToolStaleOperationJobTest {
    @Test void runningMutationsAreReportedPartialEvenWhenCheckpointShowsZero() {
        assertEquals("PARTIAL", RoleToolStaleOperationJob.terminalStatus("RUNNING", "BULK_ADD", 0));
        assertEquals("PARTIAL", RoleToolStaleOperationJob.terminalStatus("RUNNING", "RECOVERY_APPLY", 0));
        assertEquals("PARTIAL", RoleToolStaleOperationJob.terminalStatus("RUNNING", "ROLE_MIGRATE", 0));
    }

    @Test void aScanOrNeverStartedRequestHasNoDiscordMutations() {
        assertEquals("FAILED", RoleToolStaleOperationJob.terminalStatus("RUNNING", "RECOVERY_SCAN", 0));
        assertEquals("FAILED", RoleToolStaleOperationJob.terminalStatus("PENDING", "BULK_ADD", 0));
        assertEquals("PARTIAL", RoleToolStaleOperationJob.terminalStatus("RUNNING", "RECOVERY_APPLY", 2));
    }
}
