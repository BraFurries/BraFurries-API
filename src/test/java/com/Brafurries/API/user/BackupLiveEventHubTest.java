package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class BackupLiveEventHubTest {
    private final BackupControlPlaneService controlPlane =
        mock(BackupControlPlaneService.class);
    private final BackupControlPlaneStore store = mock(BackupControlPlaneStore.class);
    private final BackupLiveEventHub hub = new BackupLiveEventHub(controlPlane, store);
    private final Authentication user = mock(Authentication.class);

    @Test
    void rejectsUnauthorizedOperationBeforeOpeningStream() {
        when(controlPlane.restoreOperation(user, "77", 9L))
            .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

        assertThrows(ResponseStatusException.class,
            () -> hub.subscribe(user, "77", "restore", 9));
        verifyNoInteractions(store);
    }

    @Test
    void cannotSubscribeToOperationFromAnotherGuild() {
        when(controlPlane.restoreOperation(user, "78", 9L))
            .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        assertThrows(ResponseStatusException.class,
            () -> hub.subscribe(user, "78", "RESTORE", 9));
    }

    @Test
    void webhookRejectsUnpersistedOrCrossGuildStep() {
        assertEquals(HttpStatus.NOT_FOUND,
            assertThrows(ResponseStatusException.class,
                () -> hub.publish(77, "RESTORE", 9, 111)).getStatusCode());
        verify(store).existsOperationStep(77, "RESTORE", 9, 111);
        verifyNoMoreInteractions(controlPlane);
    }

    @Test
    void deliveryScopedByGuildAndOperation() {
        when(controlPlane.restoreOperation(user, "77", 9L))
            .thenReturn(new com.Brafurries.API.user.dto.BackupDtos.RestoreOperationResponse(
                9L, 3, "roles", "RUNNING", 1, 4, "ROLE",
                null, null, null, null, null, java.util.List.of()));
        SseEmitter stream = hub.subscribe(user, "77", "RESTORE", 9);
        try {
            when(store.existsOperationStep(78, "RESTORE", 9, 111)).thenReturn(true);
            hub.publish(78, "RESTORE", 9, 111);
            verify(controlPlane, never()).trustedOperationState(78L, "RESTORE", 9);

            when(store.existsOperationStep(77, "RESTORE", 9, 112)).thenReturn(true);
            when(controlPlane.trustedOperationState(77L, "RESTORE", 9))
                .thenReturn(Map.of("id", 9, "status", "RUNNING"));
            hub.publish(77, "RESTORE", 9, 112);
            verify(controlPlane).trustedOperationState(77L, "RESTORE", 9);
        } finally {
            stream.complete();
        }
    }

    @Test
    void rejectsNinthSubscriberForSameGuildOperation() {
        when(controlPlane.restoreOperation(user, "77", 9L))
            .thenReturn(new com.Brafurries.API.user.dto.BackupDtos.RestoreOperationResponse(
                9L, 3, "roles", "RUNNING", 1, 4, "ROLE",
                null, null, null, null, null, java.util.List.of()));
        var connected = new java.util.ArrayList<SseEmitter>();
        for (int i = 0; i < 8; i++) {
            connected.add(hub.subscribe(user, "77", "RESTORE", 9));
        }
        try {
            assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                assertThrows(ResponseStatusException.class,
                    () -> hub.subscribe(user, "77", "RESTORE", 9)).getStatusCode());
        } finally {
            connected.forEach(SseEmitter::complete);
        }
    }

    @Test
    void malformedEventFailsWithoutSubscriber() {
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                () -> hub.publish(77, "NOT_A_KIND", 9, 4)).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            assertThrows(ResponseStatusException.class,
                () -> hub.publish(77, "SNAPSHOT", 0, 4)).getStatusCode());
    }
}
