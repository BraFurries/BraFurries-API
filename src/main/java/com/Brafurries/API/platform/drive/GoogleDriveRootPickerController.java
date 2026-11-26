package com.Brafurries.API.platform.drive;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Only browser-public OAuth parameters and the verified institutional identity. */
@RestController
@RequestMapping("/user/me/platform-drive/connection")
public class GoogleDriveRootPickerController {
    private final GoogleDriveConnectionService service;
    private final String apiKey;
    private final String projectNumber;

    public GoogleDriveRootPickerController(GoogleDriveConnectionService service,
        @Value("${GOOGLE_DRIVE_PICKER_API_KEY:}") String apiKey,
        @Value("${GOOGLE_DRIVE_PROJECT_NUMBER:}") String projectNumber) {
        this.service = service;
        this.apiKey = apiKey.trim();
        this.projectNumber = projectNumber.trim();
    }

    public record Config(String clientId, String apiKey, String projectNumber,
                         String folderId, String googleSubject, String googleEmail) {}

    @GetMapping("/root-picker")
    public ResponseEntity<Config> config(Authentication auth) {
        var identity = service.rootPickerIdentity(auth);
        if (!apiKey.matches("[A-Za-z0-9_-]{20,150}") || !projectNumber.matches("[0-9]{1,30}"))
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google Picker não configurado");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(new Config(identity.clientId(), apiKey, projectNumber,
                identity.folderId(), identity.googleSubject(), identity.googleEmail()));
    }
}
