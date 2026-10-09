package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminDtos.AdminBotStatus;
import com.Brafurries.API.admin.dto.AdminDtos.AdminBotLogs;
import com.Brafurries.API.admin.dto.AdminDtos.AdminMetrics;
import com.Brafurries.API.admin.dto.AdminDtos.AdminOverview;
import com.Brafurries.API.admin.dto.AdminDtos.AdminSession;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/admin")
public class AdminGeneralController {

    private final AdminDashboardService adminDashboardService;
    private final AdminBotStatusService adminBotStatusService;

    public AdminGeneralController(
        AdminDashboardService adminDashboardService,
        AdminBotStatusService adminBotStatusService
    ) {
        this.adminDashboardService = adminDashboardService;
        this.adminBotStatusService = adminBotStatusService;
    }

    @Operation(summary = "Sessao administrativa atual")
    @GetMapping("/session")
    public AdminSession getSession(Authentication authentication) {
        return adminDashboardService.getSession(authentication);
    }

    @Operation(summary = "Resumo administrativo")
    @GetMapping("/overview")
    public AdminOverview getOverview() {
        return adminDashboardService.getOverview();
    }

    @Operation(summary = "Metricas administrativas por periodo")
    @GetMapping("/metrics")
    public AdminMetrics getMetrics(@RequestParam(defaultValue = "7d") String period) {
        return adminDashboardService.getMetrics(period);
    }

    @Operation(summary = "Status do Coddy Bot")
    @GetMapping("/bot/status")
    public AdminBotStatus getBotStatus() {
        return adminBotStatusService.getStatus();
    }

    @Operation(summary = "Logs recentes do Coddy Bot")
    @GetMapping("/bot/logs")
    public AdminBotLogs getBotLogs(
        @RequestParam(required = false) @Min(1) @Max(1000) Integer limit,
        @RequestParam(required = false) String level,
        @RequestParam(required = false) @Min(0) Long after,
        @RequestParam(required = false) @Min(0) Long before
    ) {
        if (after != null && before != null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "before and after cannot be used together"
            );
        }
        return adminBotStatusService.getLogs(limit, level, after, before);
    }
}
