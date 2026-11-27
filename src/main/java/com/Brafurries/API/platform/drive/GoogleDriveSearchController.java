package com.Brafurries.API.platform.drive;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/platform-drive/browser/search")
public class GoogleDriveSearchController {
    private final GoogleDriveSearchService service;
    public GoogleDriveSearchController(GoogleDriveSearchService service){this.service=service;}
    @GetMapping
    public ResponseEntity<GoogleDriveSearchService.Page> search(Authentication auth,@RequestParam String q,
            @RequestParam(required=false) String cursor){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.search(auth,q,cursor));
    }
}
