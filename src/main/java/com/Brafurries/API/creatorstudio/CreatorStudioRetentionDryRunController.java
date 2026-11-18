package com.Brafurries.API.creatorstudio;

import com.Brafurries.API.creatorstudio.CreatorStudioRetentionDryRunService.DryRun;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aggregated operator report only: no commission details, SMTP sends or DELETE.
 * Platform ownership is checked in the service, not inferred from the UI.
 */
@RestController
@RequestMapping("/user/me/creator-studio/admin/retention")
public class CreatorStudioRetentionDryRunController {
    private final CreatorStudioRetentionDryRunService service;

    public CreatorStudioRetentionDryRunController(CreatorStudioRetentionDryRunService service) {
        this.service = service;
    }

    @GetMapping("/dry-run")
    public ResponseEntity<DryRun> inspect(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.inspect(auth));
    }
}
