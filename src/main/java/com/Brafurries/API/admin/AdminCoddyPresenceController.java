package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminCoddyPresenceDtos.Response;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Global directory: no community-scoped or delegated-partner access. */
@RestController
@RequestMapping("/admin/coddy-presence")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCoddyPresenceController {
    private final AdminCoddyPresenceService service;

    public AdminCoddyPresenceController(AdminCoddyPresenceService service) {
        this.service = service;
    }

    @Operation(summary = "Presenca do Coddy em servidores Discord (somente administrador global)")
    @GetMapping
    public Response list(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) Integer communityId
    ) {
        return service.list(page, pageSize, search, status, communityId);
    }
}
