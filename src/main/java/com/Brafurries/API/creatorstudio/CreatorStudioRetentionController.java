package com.Brafurries.API.creatorstudio;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Retention preview and data portability remain available even after Pro
 * entitlement is revoked. The API authenticates the canonical owner first.
 */
@RestController
@RequestMapping("/user/me/creator-studio")
public class CreatorStudioRetentionController {
    private final CreatorStudioRetentionService retention;
    private final CreatorStudioDataExportService exports;

    public CreatorStudioRetentionController(CreatorStudioRetentionService retention,
                                           CreatorStudioDataExportService exports) {
        this.retention = retention;
        this.exports = exports;
    }

    @GetMapping("/retention")
    public ResponseEntity<CreatorStudioRetentionService.RetentionOverview> retention(
        Authentication authentication
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(retention.myUpcomingRetention(authentication));
    }

    @GetMapping("/export")
    public ResponseEntity<StreamingResponseBody> export(Authentication authentication) {
        StreamingResponseBody body = exports.export(authentication);
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"creator-studio-export.json\"")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }
}
