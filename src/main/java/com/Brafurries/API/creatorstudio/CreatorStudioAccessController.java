package com.Brafurries.API.creatorstudio;

import com.Brafurries.API.creatorstudio.CreatorStudioAccessService.AccessStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Only authenticated users can inspect their own Studio eligibility.
 * Grant/revoke endpoints are restricted by requireOwner() in the service.
 */
@RestController
@RequestMapping("/user/me/creator-studio")
public class CreatorStudioAccessController {
    private final CreatorStudioAccessService access;

    public CreatorStudioAccessController(CreatorStudioAccessService access) {
        this.access = access;
    }

    @GetMapping("/access")
    public ResponseEntity<AccessStatus> access(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(access.myAccess(authentication));
    }

    @PostMapping("/admin/invitations/free/{userId}")
    public ResponseEntity<AccessStatus> inviteFree(Authentication authentication,
                                                   @PathVariable int userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(access.grantFree(authentication, userId));
    }

    @PostMapping("/admin/invitations/pilot-pro-lifetime/{userId}")
    public ResponseEntity<AccessStatus> grantLifetimePro(Authentication authentication,
                                                         @PathVariable int userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(access.grantPilotLifetimePro(authentication, userId));
    }

    @DeleteMapping("/admin/invitations/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Authentication authentication, @PathVariable int userId) {
        access.revokeGrant(authentication, userId);
    }
}
