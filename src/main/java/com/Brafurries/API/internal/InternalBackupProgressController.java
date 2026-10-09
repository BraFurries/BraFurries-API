package com.Brafurries.API.internal;

import com.Brafurries.API.user.BackupLiveEventHub;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Authenticated internal notification only. The API reads authoritative
 * operation state from MariaDB and never trusts event payload content.
 */
@RestController
@RequestMapping("/internal/backup-progress")
public class InternalBackupProgressController {
    private final InternalServiceTokenAuthenticator authenticator;
    private final BackupLiveEventHub events;

    public InternalBackupProgressController(
        InternalServiceTokenAuthenticator authenticator,
        BackupLiveEventHub events
    ) {
        this.authenticator = authenticator;
        this.events = events;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changed(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @RequestBody ProgressNotification notification
    ) {
        authenticator.authenticate(authorization);
        if (notification == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Evento de progresso obrigatório"
            );
        }
        events.publish(
            notification.guildId(),
            notification.operationKind(),
            notification.operationId(),
            notification.stepId()
        );
    }

    public record ProgressNotification(
        long guildId,
        String operationKind,
        long operationId,
        long stepId
    ) {}
}
