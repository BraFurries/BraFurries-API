package com.Brafurries.API.platform.drive;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/platform-drive/folder-operations")
public class GoogleDriveFolderCreationController {
    private final GoogleDriveFolderCreationService service;
    public GoogleDriveFolderCreationController(GoogleDriveFolderCreationService service){this.service=service;}
    public record Capability(boolean enabled) {}
    public record Create(@NotBlank @Size(max=200) String anchor,@NotBlank @Size(max=200) String parent,
                         @NotBlank @Size(max=510) String name,@NotBlank @Size(max=36) String operationId) {}
    @GetMapping("/capability")
    public ResponseEntity<Capability> capability(){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Capability(service.enabled()));
    }
    @PostMapping
    public ResponseEntity<GoogleDriveFolderCreationService.Result> create(Authentication auth,@Valid @RequestBody Create request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
            service.create(auth,request.anchor(),request.parent(),request.name(),request.operationId()));
    }
}
