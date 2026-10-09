package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.BackupDtos.*;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/guilds/{guildId}/backups")
public class BackupControlPlaneController {
    private final BackupControlPlaneService service;
    private final BackupLiveEventHub events;

    public BackupControlPlaneController(
        BackupControlPlaneService service, BackupLiveEventHub events
    ) {
        this.service = service;
        this.events = events;
    }

    @GetMapping
    public BackupsResponse list(
        Authentication authentication,
        @PathVariable String guildId
    ) {
        return service.list(authentication, guildId);
    }

    @GetMapping("/settings")
    public BackupSettingsResponse settings(
        Authentication authentication,
        @PathVariable String guildId
    ) {
        return service.settings(authentication, guildId);
    }

    @PutMapping("/settings")
    public BackupSettingsResponse updateSettings(
        Authentication authentication,
        @PathVariable String guildId,
        @RequestBody @Valid BackupSettingsUpdateRequest request
    ) {
        return service.updateSettings(authentication, guildId, request);
    }

    @PostMapping
    public SnapshotOperationResponse create(
        Authentication authentication,
        @PathVariable String guildId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestBody @Valid CreateBackupRequest request
    ) {
        return service.create(
            authentication,
            guildId,
            request,
            idempotencyKey
        );
    }

    @DeleteMapping("/{backupId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(
        Authentication authentication,
        @PathVariable String guildId,
        @PathVariable int backupId
    ) {
        service.delete(authentication, guildId, backupId);
    }

    @GetMapping("/snapshot-operations")
    public SnapshotOperationsResponse snapshotOperations(
        Authentication authentication,
        @PathVariable String guildId
    ) {
        return service.snapshotOperations(authentication, guildId);
    }

    @GetMapping("/snapshot-operations/{operationId}")
    public SnapshotOperationResponse snapshotOperation(
        Authentication authentication,
        @PathVariable String guildId,
        @PathVariable long operationId
    ) {
        return service.snapshotOperation(authentication, guildId, operationId);
    }

    @PostMapping("/{backupId}/restore-preview")
    public BackupRestorePreviewResponse restorePreview(
        Authentication authentication,
        @PathVariable String guildId,
        @PathVariable int backupId,
        @RequestBody @Valid RestorePreviewRequest request
    ) {
        return service.restorePreview(
            authentication,
            guildId,
            backupId,
            request
        );
    }

    @PostMapping("/{backupId}/restore")
    public RestoreOperationResponse startRestore(
        Authentication authentication,
        @PathVariable String guildId,
        @PathVariable int backupId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @RequestBody @Valid RestoreStartRequest request
    ) {
        return service.startRestore(
            authentication,
            guildId,
            backupId,
            request,
            idempotencyKey
        );
    }

    @GetMapping(value = "/live/{kind}/{operationId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> liveProgress(
        Authentication authentication,
        @PathVariable String guildId,
        @PathVariable String kind,
        @PathVariable long operationId
    ) {
        SseEmitter emitter = events.subscribe(authentication, guildId, kind, operationId);
        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .header("Cache-Control", "no-cache, no-transform")
            .header("X-Accel-Buffering", "no")
            .body(emitter);
    }

    @GetMapping("/restore-operations")
    public RestoreOperationsResponse restoreOperations(
        Authentication authentication,
        @PathVariable String guildId
    ) {
        return service.restoreOperations(authentication, guildId);
    }

    @GetMapping("/restore-operations/{operationId}")
    public RestoreOperationResponse restoreOperation(
        Authentication authentication,
        @PathVariable String guildId,
        @PathVariable long operationId
    ) {
        return service.restoreOperation(
            authentication,
            guildId,
            operationId
        );
    }
}
