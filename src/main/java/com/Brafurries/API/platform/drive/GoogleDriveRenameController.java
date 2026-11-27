package com.Brafurries.API.platform.drive;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/platform-drive/rename-operations")
public class GoogleDriveRenameController {
    private final GoogleDriveRenameService service;
    public GoogleDriveRenameController(GoogleDriveRenameService service){this.service=service;}
    public record Request(@NotBlank @Size(max=200) String anchor,@NotBlank @Size(max=200) String parent,
        @NotBlank @Size(max=200) String file,@NotBlank @Size(max=32) String version,
        @NotBlank @Size(max=510) String name,@NotBlank @Size(max=36) String operationId){}
    @PostMapping
    public ResponseEntity<GoogleDriveRenameService.Result> rename(Authentication auth,@Valid @RequestBody Request r){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.rename(auth,r.anchor(),r.parent(),r.file(),r.version(),r.name(),r.operationId()));
    }
}
