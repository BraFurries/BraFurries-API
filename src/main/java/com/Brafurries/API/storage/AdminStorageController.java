package com.Brafurries.API.storage;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** BraFurries platform-owner storage inventory; no Community-scoped endpoint. */
@RestController
@RequestMapping("/user/me/administration/storage")
public class AdminStorageController {
    private final AdminStorageService service;

    public AdminStorageController(AdminStorageService service) {
        this.service=service;
    }

    @GetMapping("/objects")
    public ResponseEntity<AdminStorageService.PageResult> list(
        Authentication auth,
        @RequestParam AdminStorageService.Scope bucket,
        @RequestParam(required=false) String prefix,
        @RequestParam(required=false) String cursor,
        @RequestParam(required=false) Integer limit
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.list(auth,bucket,prefix,cursor,limit));
    }

    public record InspectRequest(AdminStorageService.Scope bucket,String key) {}

    // Keep exact private object keys out of query strings, proxy access logs,
    // referrer headers and browser navigation history.
    @PostMapping("/objects/inspect")
    public ResponseEntity<AdminStorageService.ObjectView> inspect(
        Authentication auth,@RequestBody InspectRequest input
    ) {
        if(input==null) throw new org.springframework.web.server.ResponseStatusException(
            HttpStatus.BAD_REQUEST,"Informe o objeto");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service.inspect(auth,input.bucket(),input.key()));
    }

    @PostMapping("/objects/delete-orphan")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOrphan(
        Authentication auth,
        @RequestBody AdminStorageService.DeleteRequest input
    ) {
        service.deleteOrphan(auth,input);
    }
}
