package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminCoddyPresenceDtos.Response;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Global directory: no community-scoped or delegated-partner access. */
@RestController
@RequestMapping("/admin/coddy-presence")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class AdminCoddyPresenceController {
    private final AdminCoddyPresenceService service;

    public AdminCoddyPresenceController(AdminCoddyPresenceService service) {
        this.service = service;
    }

    @Operation(summary = "Presenca do Coddy em servidores Discord (somente administrador global)")
    @GetMapping
    public Response list(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 100) String search,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) @Min(1) Integer communityId
    ) {
        return service.list(page, pageSize, search, status, communityId);
    }
}
