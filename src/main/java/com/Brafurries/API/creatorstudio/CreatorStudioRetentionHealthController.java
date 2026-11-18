package com.Brafurries.API.creatorstudio;

import com.Brafurries.API.creatorstudio.CreatorStudioRetentionHealthService.Health;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Owner-only aggregate; must never be cached or exposed to artists. */
@RestController
@RequestMapping("/user/me/creator-studio/admin/retention")
public class CreatorStudioRetentionHealthController {
    private final CreatorStudioRetentionHealthService service;

    public CreatorStudioRetentionHealthController(CreatorStudioRetentionHealthService service) {
        this.service = service;
    }

    @GetMapping("/health")
    public ResponseEntity<Health> inspect(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.inspect(auth));
    }
}
