package com.Brafurries.API.internal;

import com.Brafurries.API.internal.InternalThemeAssetService.StoredAssetBytes;
import com.Brafurries.API.user.dto.ThemeDtos.ManagedThemeAssetResponse;
import com.Brafurries.API.user.dto.ThemeDtos.RollbackAssetUploadRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/themes/{guildId}/applications/{applicationId}")
public class InternalThemeAssetController {
    private final InternalThemeAssetService service;
    private final InternalServiceTokenAuthenticator authenticator;

    public InternalThemeAssetController(
        InternalThemeAssetService service,
        InternalServiceTokenAuthenticator authenticator
    ) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @PostMapping("/rollback-assets")
    public ManagedThemeAssetResponse uploadRollback(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @PathVariable long guildId,
        @PathVariable long applicationId,
        @Valid @RequestBody RollbackAssetUploadRequest request
    ) {
        authenticator.authenticate(authorization);
        return service.uploadRollback(guildId, applicationId, request);
    }

    @GetMapping("/desired-assets/{assetType}")
    public ResponseEntity<byte[]> desired(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @PathVariable long guildId,
        @PathVariable long applicationId,
        @PathVariable String assetType
    ) {
        authenticator.authenticate(authorization);
        return response(service.desired(guildId, applicationId, assetType));
    }

    @GetMapping("/rollback-assets/{assetType}")
    public ResponseEntity<byte[]> rollback(
        @RequestHeader(name = "Authorization", required = false) String authorization,
        @PathVariable long guildId,
        @PathVariable long applicationId,
        @PathVariable String assetType
    ) {
        authenticator.authenticate(authorization);
        return response(service.rollback(guildId, applicationId, assetType));
    }

    private ResponseEntity<byte[]> response(StoredAssetBytes asset) {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(asset.contentType()))
            .header("X-Content-SHA256", asset.sha256())
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .body(asset.bytes());
    }
}
