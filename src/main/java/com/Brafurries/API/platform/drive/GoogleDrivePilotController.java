package com.Brafurries.API.platform.drive;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.repository.user.UserSocialIdentityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Owner-only pilot: supplies browser OAuth configuration, never an institutional
 * access token. Google remains the authority for actual file permissions.
 * No sharing mutations or personal Drive data are proxied by this controller.
 */
@RestController
@RequestMapping("/user/me/platform-drive")
public class GoogleDrivePilotController {
    private final PlatformAuthorizationService authorization;
    private final UserSocialIdentityRepository identities;
    private final boolean enabled;
    private final String clientId, apiKey, projectNumber, folderId;

    public GoogleDrivePilotController(
        PlatformAuthorizationService authorization,
        UserSocialIdentityRepository identities,
        @Value("${app.drive.pilot.enabled:false}") boolean enabled,
        @Value("${app.drive.pilot.client-id:}") String clientId,
        @Value("${app.drive.pilot.api-key:}") String apiKey,
        @Value("${app.drive.pilot.project-number:}") String projectNumber,
        @Value("${app.drive.pilot.folder-id:}") String folderId
    ) {
        this.authorization=authorization;
        this.identities=identities;
        this.enabled=enabled;
        this.clientId=clientId.trim();
        this.apiKey=apiKey.trim();
        this.projectNumber=projectNumber.trim();
        this.folderId=folderId.trim();
    }

    public record Config(boolean enabled,boolean linked,String clientId,String apiKey,
                         String projectNumber,String folderId,String googleSubject,String googleEmail) {}

    @GetMapping("/pilot")
    public ResponseEntity<Config> config(Authentication auth) {
        var owner=authorization.requireOwner(auth);
        boolean configured=enabled && clientId.matches("[A-Za-z0-9._-]+\\.apps\\.googleusercontent\\.com")
            && apiKey.matches("[A-Za-z0-9_-]{20,150}") && projectNumber.matches("[0-9]{1,30}")
            && folderId.matches("[A-Za-z0-9_-]{5,200}");
        if(!configured) return result(new Config(false,false,null,null,null,null,null,null));

        var linked=identities.findByUserIdAndProvider(owner.getId(),"google");
        if(linked.size()!=1 || linked.get(0).getProviderSubject()==null ||
            linked.get(0).getProviderSubject().isBlank() || linked.get(0).getVerifiedEmail()==null ||
            linked.get(0).getVerifiedEmail().isBlank()) {
            return result(new Config(true,false,null,null,null,null,null,null));
        }
        var identity=linked.get(0);
        return result(new Config(true,true,clientId,apiKey,projectNumber,folderId,
            identity.getProviderSubject(),identity.getVerifiedEmail()));
    }

    private ResponseEntity<Config> result(Config config) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(config);
    }
}
