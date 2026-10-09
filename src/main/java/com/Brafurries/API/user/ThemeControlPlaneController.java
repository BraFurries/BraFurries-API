package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.ThemeDtos.*;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/user/me/guilds/{guildId}/themes")
public class ThemeControlPlaneController {
    private final ThemeControlPlaneService service;

    public ThemeControlPlaneController(ThemeControlPlaneService service) {
        this.service = service;
    }

    @GetMapping
    public ThemesResponse list(
        Authentication authentication,
        @PathVariable String guildId
    ) {
        return service.list(authentication, guildId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ThemeResponse create(
        Authentication authentication,
        @PathVariable String guildId,
        @Valid @RequestBody ThemeSaveRequest request
    ) {
        return service.create(authentication, guildId, request);
    }

    @GetMapping("/resources")
    public ThemeResourcesResponse resources(
        Authentication authentication,
        @PathVariable String guildId
    ) {
        return service.resources(authentication, guildId);
    }

    @GetMapping("/{themeId}")
    public ThemeResponse get(
        Authentication authentication,
        @PathVariable String guildId,
        @PathVariable long themeId
    ) {
        return service.get(authentication, guildId, themeId);
    }

    @PutMapping("/{themeId}")
    public ThemeResponse update(
        Authentication authentication,
        @PathVariable String guildId,
        @PathVariable long themeId,
        @Valid @RequestBody ThemeSaveRequest request
    ) {
        return service.update(authentication, guildId, themeId, request);
    }

    @DeleteMapping("/{themeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
        Authentication authentication,
        @PathVariable String guildId,
        @PathVariable long themeId
    ) {
        service.delete(authentication, guildId, themeId);
    }

    @PutMapping(
        value = "/{themeId}/assets/{assetType}",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ThemeResponse uploadAsset(
        Authentication authentication,
        @PathVariable String guildId,
        @PathVariable long themeId,
        @PathVariable String assetType,
        @RequestPart("file") MultipartFile file
    ) {
        return service.uploadAsset(
            authentication,
            guildId,
            themeId,
            assetType,
            file
        );
    }

    @PostMapping("/{themeId}/preview")
    public ThemePreviewResponse preview(
        Authentication authentication,
        @PathVariable String guildId,
        @PathVariable long themeId
    ) {
        return service.preview(authentication, guildId, themeId);
    }

    @PostMapping("/{themeId}/apply")
    public ThemeOperationResponse apply(
        Authentication authentication,
        @PathVariable String guildId,
        @PathVariable long themeId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody ThemeApplyRequest request
    ) {
        return service.apply(
            authentication,
            guildId,
            themeId,
            request,
            idempotencyKey
        );
    }

    @GetMapping("/applications")
    public ThemeApplicationsResponse applications(
        Authentication authentication,
        @PathVariable String guildId
    ) {
        return service.applications(authentication, guildId);
    }

    @GetMapping("/applications/active")
    public ThemeApplicationResponse activeApplication(
        Authentication authentication,
        @PathVariable String guildId
    ) {
        return service.activeApplication(authentication, guildId);
    }

    @GetMapping("/applications/{applicationId}")
    public ThemeApplicationResponse application(
        Authentication authentication,
        @PathVariable String guildId,
        @PathVariable long applicationId
    ) {
        return service.application(authentication, guildId, applicationId);
    }

    @PostMapping("/applications/{applicationId}/restore")
    public ThemeOperationResponse restore(
        Authentication authentication,
        @PathVariable String guildId,
        @PathVariable long applicationId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody ThemeRestoreRequest request
    ) {
        return service.restore(
            authentication,
            guildId,
            applicationId,
            request,
            idempotencyKey
        );
    }
}
