package com.Brafurries.API.admin;

import com.Brafurries.API.admin.AdminRecordBlacklistService.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Global Coddy record policy. No Community-level permission fallback. */
@RestController
@RequestMapping("/admin/records")
@PreAuthorize("hasRole('ADMIN')")
public class AdminRecordBlacklistController {
    private final AdminRecordBlacklistService service;
    public AdminRecordBlacklistController(AdminRecordBlacklistService service) {
        this.service = service;
    }

    @GetMapping("/blacklist")
    public ListView list() { return service.list(); }

    @PostMapping("/blacklist")
    public ListView add(Authentication actor, @RequestBody CreateRequest request) {
        return service.add(actor, request);
    }

    @DeleteMapping("/blacklist/{entryId}")
    public ListView remove(Authentication actor, @PathVariable int entryId) {
        return service.remove(actor, entryId);
    }
}
