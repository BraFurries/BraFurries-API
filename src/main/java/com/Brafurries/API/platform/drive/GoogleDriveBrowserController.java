package com.Brafurries.API.platform.drive;

import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/platform-drive/browser")
public class GoogleDriveBrowserController {
    private final GoogleDriveBrowserService service;
    public GoogleDriveBrowserController(GoogleDriveBrowserService service) { this.service=service; }
    @GetMapping("/mounts")
    public ResponseEntity<List<GoogleDriveBrowserService.Mount>> mounts(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.mounts(auth));
    }
    @GetMapping("/mounts/{anchor}/folders/{folder}")
    public ResponseEntity<GoogleDriveBrowserService.Page> list(Authentication auth,
        @PathVariable String anchor,@PathVariable String folder,@RequestParam(required=false) String pageToken) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(auth,anchor,folder,pageToken));
    }
}
