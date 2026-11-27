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
    private final GoogleDriveContentService content;
    public GoogleDriveBrowserController(GoogleDriveBrowserService service,GoogleDriveContentService content) { this.service=service;this.content=content; }
    @GetMapping("/mounts")
    public ResponseEntity<List<GoogleDriveBrowserService.Mount>> mounts(Authentication auth) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.mounts(auth));
    }
    @GetMapping("/mounts/{anchor}/folders/{folder}")
    public ResponseEntity<GoogleDriveBrowserService.Page> list(Authentication auth,
        @PathVariable String anchor,@PathVariable String folder,@RequestParam(required=false) String pageToken) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.list(auth,anchor,folder,pageToken));
    }

    @GetMapping("/mounts/{anchor}/folders/{folder}/files/{file}/preview")
    public ResponseEntity<byte[]> preview(Authentication auth,@PathVariable String anchor,
            @PathVariable String folder,@PathVariable String file) {
        return response(content.read(auth,anchor,folder,file,true));
    }
    @GetMapping("/mounts/{anchor}/folders/{folder}/files/{file}/download")
    public ResponseEntity<byte[]> download(Authentication auth,@PathVariable String anchor,
            @PathVariable String folder,@PathVariable String file) {
        return response(content.read(auth,anchor,folder,file,false));
    }
    private static ResponseEntity<byte[]> response(GoogleDriveContentService.Content data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options","nosniff")
            .header("Content-Security-Policy","default-src 'none'; sandbox")
            .header("Content-Disposition",org.springframework.http.ContentDisposition.attachment()
                .filename(data.name(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
            .contentType(org.springframework.http.MediaType.parseMediaType(data.mime()))
            .contentLength(data.bytes().length).body(data.bytes());
    }
}
