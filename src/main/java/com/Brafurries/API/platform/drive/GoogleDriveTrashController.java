package com.Brafurries.API.platform.drive;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/platform-drive/trash")
public class GoogleDriveTrashController {
    private final GoogleDriveTrashService service;
    public GoogleDriveTrashController(GoogleDriveTrashService service){this.service=service;}
    public record Trash(@NotBlank @Size(max=200) String anchor,@NotBlank @Size(max=200) String parent,
        @NotBlank @Size(max=200) String file,@NotBlank @Size(max=32) String version,@NotBlank @Size(max=36) String operationId){}
    public record Restore(@NotBlank @Size(max=200) String anchor,@NotBlank @Size(max=32) String version){}
    @GetMapping
    public ResponseEntity<GoogleDriveTrashService.Page> list(Authentication auth,@RequestParam String anchor,
            @RequestParam(required=false) String cursor){return response(service.list(auth,anchor,cursor));}
    @PostMapping
    public ResponseEntity<GoogleDriveTrashService.Result> trash(Authentication auth,@Valid @RequestBody Trash r){
        return response(service.trash(auth,r.anchor(),r.parent(),r.file(),r.version(),r.operationId()));
    }
    @PostMapping("/{id}/restore")
    public ResponseEntity<GoogleDriveTrashService.Result> restore(Authentication auth,@PathVariable String id,@Valid @RequestBody Restore r){
        return response(service.restore(auth,r.anchor(),id,r.version()));
    }
    private static <T> ResponseEntity<T> response(T body){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
