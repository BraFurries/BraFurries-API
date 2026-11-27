package com.Brafurries.API.platform.drive;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Owner-only pilot. Keep disabled until real Google/CORS checks in a private test folder. */
@RestController
@RequestMapping("/user/me/platform-drive/uploads")
public class GoogleDriveUploadPilotController {
    private final PlatformAuthorizationService authorization;
    private final GoogleDriveUploadSessionService sessions;
    private final GoogleDriveUploadPublicationService publication;
    private final GoogleDriveUploadLedger ledger;
    private final boolean enabled;
    private final long maxBytes;

    public GoogleDriveUploadPilotController(PlatformAuthorizationService authorization,
            GoogleDriveUploadSessionService sessions,GoogleDriveUploadPublicationService publication,
            GoogleDriveUploadLedger ledger,
            @Value("${GOOGLE_DRIVE_UPLOAD_PILOT_ENABLED:false}") boolean pilot,
            @Value("${GOOGLE_DRIVE_UPLOAD_CLEANUP_ENABLED:false}") boolean cleanup,
            @Value("${GOOGLE_DRIVE_UPLOAD_STAGING_FOLDER_ID:}") String staging,
            @Value("${GOOGLE_DRIVE_UPLOAD_MAX_BYTES:5368709120}") long maxBytes) {
        this.authorization=authorization;this.sessions=sessions;this.publication=publication;this.ledger=ledger;
        this.enabled=pilot&&cleanup&&GoogleDriveConnectionService.validFolderId(staging.trim())&&maxBytes>0;
        this.maxBytes=maxBytes;
    }

    public record Capability(boolean enabled,long maxBytes) {}
    public record Begin(@NotBlank @Size(max=200) String anchor,
                        @NotBlank @Size(max=200) String destination,
                        @NotBlank @Size(max=510) String name,
                        @NotBlank @Size(max=255) String mime,
                        @Positive long size) {}
    public record Started(String sessionId,String uploadUri) {
        @Override public String toString(){return "Started[sessionId="+sessionId+",uploadUri=REDACTED]";}
    }
    public record Status(String sessionId,String status,boolean expired) {}

    @GetMapping("/pilot")
    public ResponseEntity<Capability> capability(Authentication auth) {
        boolean allowed=enabled&&authorization.resolve(auth).access().owner();
        return response(new Capability(allowed,allowed?maxBytes:0));
    }

    @PostMapping
    public ResponseEntity<Started> begin(Authentication auth,@Valid @RequestBody Begin request) {
        requireEnabledOwner(auth);
        var result=sessions.begin(auth,request.anchor(),request.destination(),request.name(),request.mime(),request.size());
        return response(new Started(result.sessionId(),result.uploadUri().toString()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Status> status(Authentication auth,@PathVariable String id) {
        int actor=authorization.requireOwner(auth).getId();
        if (!GoogleDriveUploadPolicy.validSessionId(id)) throw GoogleDriveBrowserService.inaccessible();
        var session=ledger.owned(id,actor);
        return response(new Status(id,session.status(),session.expired()));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<GoogleDriveUploadPublicationService.Result> publish(Authentication auth,@PathVariable String id) {
        requireEnabledOwner(auth);
        return response(publication.publish(auth,id));
    }

    // Allow existing pilot sessions to be canceled even after disabling new uploads.
    @PostMapping("/{id}/cancel")
    public ResponseEntity<GoogleDriveUploadPublicationService.Result> cancel(Authentication auth,@PathVariable String id) {
        authorization.requireOwner(auth);
        return response(publication.cancel(auth,id));
    }

    private void requireEnabledOwner(Authentication auth) {
        authorization.requireOwner(auth);
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Piloto de upload indisponível");
    }

    private static <T> ResponseEntity<T> response(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("Pragma","no-cache").header("X-Content-Type-Options","nosniff").body(body);
    }
}
