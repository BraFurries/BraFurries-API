package com.Brafurries.API.platform.drive;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/platform-drive/transfer-operations")
public class GoogleDriveTransferController {
    private final GoogleDriveTransferService service;
    public GoogleDriveTransferController(GoogleDriveTransferService service){this.service=service;}
    public record Request(@NotBlank @Size(max=200) String sourceAnchor,@NotBlank @Size(max=200) String parent,
        @NotBlank @Size(max=200) String file,@NotBlank @Size(max=32) String version,
        @NotBlank @Size(max=200) String destinationAnchor,@NotBlank @Size(max=200) String destination,
        @NotNull GoogleDriveTransferLedger.Action action,@NotBlank @Size(max=36) String operationId){}
    @PostMapping
    public ResponseEntity<GoogleDriveTransferService.Result> transfer(Authentication auth,@Valid @RequestBody Request r){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.transfer(auth,r.sourceAnchor(),r.parent(),
            r.file(),r.version(),r.destinationAnchor(),r.destination(),r.action(),r.operationId()));
    }
}
