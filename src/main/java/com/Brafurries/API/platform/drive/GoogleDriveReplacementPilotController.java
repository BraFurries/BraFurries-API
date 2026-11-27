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

@RestController
@RequestMapping("/user/me/platform-drive/replacements")
public class GoogleDriveReplacementPilotController {
    private final PlatformAuthorizationService authorization;
    private final GoogleDriveRevisionService revisions;
    private final GoogleDriveReplacementService replacement;
    private final GoogleDriveUploadLedger uploads;
    private final GoogleDriveReplacementLedger ledger;
    private final GoogleDriveFolderAccessService acl;
    private final boolean enabled;
    private final long maxBytes;

    public GoogleDriveReplacementPilotController(PlatformAuthorizationService authorization,
            GoogleDriveRevisionService revisions,GoogleDriveReplacementService replacement,
            GoogleDriveUploadLedger uploads,GoogleDriveReplacementLedger ledger,GoogleDriveFolderAccessService acl,
            @Value("${GOOGLE_DRIVE_REPLACEMENT_COMMIT_ENABLED:false}") boolean commit,
            @Value("${GOOGLE_DRIVE_UPLOAD_PILOT_ENABLED:false}") boolean pilot,
            @Value("${GOOGLE_DRIVE_UPLOAD_CLEANUP_ENABLED:false}") boolean cleanup,
            @Value("${GOOGLE_DRIVE_UPLOAD_STAGING_FOLDER_ID:}") String staging,
            @Value("${GOOGLE_DRIVE_REPLACEMENT_MAX_BYTES:268435456}") long replacementMax,
            @Value("${GOOGLE_DRIVE_UPLOAD_MAX_BYTES:5368709120}") long uploadMax){
        this.authorization=authorization;this.revisions=revisions;this.replacement=replacement;
        this.uploads=uploads;this.ledger=ledger;this.acl=acl;
        this.maxBytes=Math.min(replacementMax,uploadMax);
        this.enabled=commit&&pilot&&cleanup&&maxBytes>0&&GoogleDriveConnectionService.validFolderId(staging.trim());
    }

    public record Begin(@NotBlank @Size(max=200) String anchor,@NotBlank @Size(max=200) String destination,
        @NotBlank @Size(max=200) String file,@NotBlank @Size(max=32) String version,
        @NotBlank @Size(max=255) String mime,@Positive long size){}
    public record Status(String sessionId,String status,boolean expired,boolean resumeAllowed){}

    @GetMapping("/pilot")
    public ResponseEntity<GoogleDriveUploadPilotController.Capability> capability(Authentication auth){
        boolean allowed=enabled&&authorization.resolve(auth).access().owner();
        return response(new GoogleDriveUploadPilotController.Capability(allowed,allowed?maxBytes:0));
    }

    @PostMapping
    public ResponseEntity<GoogleDriveUploadPilotController.Started> begin(Authentication auth,@Valid @RequestBody Begin r){
        requireEnabled(auth);
        if(r.size()>maxBytes)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Arquivo acima do limite do piloto");
        var result=revisions.prepareReplacement(auth,r.anchor(),r.destination(),r.file(),r.version(),r.mime(),r.size());
        return response(new GoogleDriveUploadPilotController.Started(result.sessionId(),result.uploadUri().toString()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Status> status(Authentication auth,@PathVariable String id){
        return response(readStatus(auth,id));
    }

    @PostMapping("/{id}/publish")
    public ResponseEntity<Status> publish(Authentication auth,@PathVariable String id){
        requireEnabled(auth);
        var current=readStatus(auth,id);
        if("PUBLISHED".equals(current.status()))return response(current);
        if(!"OPEN".equals(current.status())||current.expired())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Substituição indisponível");
        replacement.apply(auth,id);
        return response(readStatus(auth,id));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Status> cancel(Authentication auth,@PathVariable String id){
        var session=owned(auth,id);
        if(ledger.find(id)==null)uploads.cancel(session.reservation());
        return response(readStatus(auth,id));
    }

    private GoogleDriveUploadLedger.Session owned(Authentication auth,String id){
        int actor=authorization.requireOwner(auth).getId();
        if(!GoogleDriveUploadPolicy.validSessionId(id))throw GoogleDriveBrowserService.inaccessible();
        var session=uploads.owned(id,actor);
        if(!acl.browsePolicy(auth).scope().equals(session.reservation().scope()))throw GoogleDriveBrowserService.inaccessible();
        ledger.intent(session.reservation());
        return session;
    }
    private Status readStatus(Authentication auth,String id){
        var session=owned(auth,id);
        var commit=ledger.find(id);
        String state=session.status();
        if(commit!=null)state=switch(commit.status()){
            case "APPLIED" -> "PUBLISHED";
            case "ABORTED" -> "CANCELED";
            case "APPLYING" -> "RECONCILE_REQUIRED".equals(session.status())?"RECONCILE_REQUIRED":"PUBLISHING";
            default -> "RECONCILE_REQUIRED";
        };
        return new Status(id,state,session.expired(),enabled&&!session.expired()&&"OPEN".equals(state));
    }
    private void requireEnabled(Authentication auth){
        authorization.requireOwner(auth);
        if(!enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Piloto de substituição indisponível");
    }
    private static <T> ResponseEntity<T> response(T body){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma","no-cache")
            .header("X-Content-Type-Options","nosniff").body(body);
    }
}
