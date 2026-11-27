package com.Brafurries.API.platform.drive;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/platform-drive/browser/mounts/{anchor}/folders/{folder}/files/{file}/revisions")
public class GoogleDriveRevisionController {
    private final GoogleDriveRevisionService service;
    public GoogleDriveRevisionController(GoogleDriveRevisionService service){this.service=service;}
    @GetMapping
    public ResponseEntity<GoogleDriveRevisionService.History> history(Authentication auth,
            @PathVariable String anchor,@PathVariable String folder,@PathVariable String file,
            @RequestParam(required=false) String pageToken){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.history(auth,anchor,folder,file,pageToken));
    }
}
