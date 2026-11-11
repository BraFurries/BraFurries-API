package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminCoddyWebDtos.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** No tenant-admin fallback: these endpoints control the bot's global identity. */
@RestController
@RequestMapping("/admin/bot/operations")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCoddyWebController {
    private final AdminCoddyWebService service;

    public AdminCoddyWebController(AdminCoddyWebService service) {
        this.service = service;
    }

    @GetMapping("/presence")
    public PresenceView presence() {
        return service.presence();
    }

    @PutMapping("/presence")
    public PresenceView updatePresence(Authentication auth, @Valid @RequestBody PresenceUpdateRequest request) {
        return service.updatePresence(auth, request);
    }

    @GetMapping("/message-targets")
    public Targets targets() {
        return service.targets();
    }

    @PostMapping("/messages")
    public SendResult send(Authentication auth, @Valid @RequestBody SendRequest request) {
        return service.send(auth, request);
    }
}
