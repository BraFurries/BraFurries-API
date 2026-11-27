package com.Brafurries.API.platform.drive;

import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/platform-drive/folders/{folderId}/access")
public class GoogleDriveFolderAccessController {
    private final GoogleDriveFolderAccessService service;
    public GoogleDriveFolderAccessController(GoogleDriveFolderAccessService service) {
        this.service=service;
    }

    @GetMapping
    public ResponseEntity<List<GoogleDriveFolderAccessService.Grant>> grants(Authentication auth,@PathVariable String folderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.grants(auth,folderId));
    }

    @GetMapping("/me")
    public ResponseEntity<List<GoogleDriveFolderAccessService.Permission>> effective(Authentication auth,@PathVariable String folderId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.effective(auth,folderId));
    }

    public record ReplaceRequest(List<GoogleDriveFolderAccessService.Permission> expected,
                                 List<GoogleDriveFolderAccessService.Permission> desired) {}

    @PutMapping("/{principal}/{id}")
    public ResponseEntity<Void> replace(Authentication auth,@PathVariable String folderId,
        @PathVariable GoogleDriveFolderAccessService.Principal principal,@PathVariable int id,
        @RequestBody ReplaceRequest request) {
        service.replace(auth,folderId,principal,id,request.expected(),request.desired());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PutMapping("/{principal}/{id}/{permission}")
    public ResponseEntity<Void> grant(Authentication auth,@PathVariable String folderId,
        @PathVariable GoogleDriveFolderAccessService.Principal principal,@PathVariable int id,
        @PathVariable GoogleDriveFolderAccessService.Permission permission) {
        service.change(auth,folderId,principal,id,permission,true);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @DeleteMapping("/{principal}/{id}/{permission}")
    public ResponseEntity<Void> revoke(Authentication auth,@PathVariable String folderId,
        @PathVariable GoogleDriveFolderAccessService.Principal principal,@PathVariable int id,
        @PathVariable GoogleDriveFolderAccessService.Permission permission) {
        service.change(auth,folderId,principal,id,permission,false);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
