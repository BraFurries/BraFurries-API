package com.Brafurries.API.storage;

import java.util.Objects;
import org.springframework.http.HttpStatus;
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
    public AdminStorageService.PageResult list(
        Authentication auth,
        @RequestParam AdminStorageService.Scope bucket,
        @RequestParam(required=false) String prefix,
        @RequestParam(required=false) String cursor,
        @RequestParam(required=false) Integer limit
    ) {
        return service.list(auth,bucket,prefix,cursor,limit);
    }

    @GetMapping("/objects/inspect")
    public AdminStorageService.ObjectView inspect(
        Authentication auth,
        @RequestParam AdminStorageService.Scope bucket,
        @RequestParam String key
    ) {
        return service.inspect(auth,bucket,key);
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
