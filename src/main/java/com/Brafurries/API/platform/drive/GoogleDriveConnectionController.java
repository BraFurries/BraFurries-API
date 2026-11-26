package com.Brafurries.API.platform.drive;

import com.Brafurries.API.auth.social.SocialFrontendOriginPolicy;
import java.net.URI;
import java.util.Map;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/user/me/platform-drive")
public class GoogleDriveConnectionController {
    private final GoogleDriveConnectionService service;
    private final SocialFrontendOriginPolicy frontend;

    public GoogleDriveConnectionController(GoogleDriveConnectionService service, SocialFrontendOriginPolicy frontend) {
        this.service=service;
        this.frontend=frontend;
    }

    public record CompleteRequest(@NotBlank @Size(max=43) String state,
                                  @NotBlank @Size(max=2048) String code) {}

    @GetMapping("/connection")
    public ResponseEntity<GoogleDriveConnectionService.Status> status(Authentication auth) {
        return noStore(service.status(auth));
    }

    @PostMapping("/oauth/start")
    public ResponseEntity<GoogleDriveConnectionService.Start> start(Authentication auth) {
        return noStore(service.start(auth));
    }

    /**
     * The provider redirect cannot carry our bearer token. It only forwards a
     * short-lived authorization code to the configured first-party UI, in a
     * fragment (not transmitted to the hosting server). No grant is persisted
     * here. Completion requires the original owner's authenticated API request,
     * stored state hash and PKCE verifier.
     */
    @GetMapping("/oauth/callback")
    public ResponseEntity<Void> callback(@RequestParam(required=false) String state,
                                         @RequestParam(required=false) String code,
                                         @RequestParam(required=false) String error) {
        String fragment = error == null && GoogleDriveConnectionService.validState(state)
            && GoogleDriveConnectionService.validCode(code)
            ? GoogleDriveOAuthClient.form(Map.of("drive_state",state,"drive_code",code))
            : "drive_error=authorization_failed";
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
            .cacheControl(CacheControl.noStore()).header("Referrer-Policy","no-referrer")
            .location(URI.create(frontend.defaultOrigin()+"/painel/admin/google-drive#"+fragment))
            .build();
    }

    @PostMapping("/oauth/complete")
    public ResponseEntity<GoogleDriveConnectionService.Status> complete(
        Authentication auth, @Valid @RequestBody CompleteRequest request) {
        return noStore(service.complete(auth,request.state(),request.code()));
    }

    @PostMapping("/connection/check-root")
    public ResponseEntity<GoogleDriveConnectionService.RootCheck> checkRoot(Authentication auth) {
        return noStore(service.checkRoot(auth));
    }

    public record RegisterFolderRequest(@NotBlank @Size(max=200) String folderId) {}

    @GetMapping("/folders")
    public ResponseEntity<List<GoogleDriveConnectionService.ManagedFolder>> managedFolders(Authentication auth) {
        return noStore(service.managedFolders(auth));
    }

    @PostMapping("/folders")
    public ResponseEntity<GoogleDriveConnectionService.ManagedFolder> registerFolder(
        Authentication auth, @Valid @RequestBody RegisterFolderRequest request) {
        return noStore(service.registerFolder(auth,request.folderId()));
    }

    @DeleteMapping("/connection")
    public ResponseEntity<Void> disconnect(Authentication auth) {
        service.disconnect(auth);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
