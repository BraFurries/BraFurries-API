package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminMetricsDtos.GeneralBotMetricsResponse;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/metrics")
public class AdminMetricsController {

    private final AdminMetricsService adminMetricsService;

    public AdminMetricsController(AdminMetricsService adminMetricsService) {
        this.adminMetricsService = adminMetricsService;
    }

    @Operation(summary = "Métricas gerais administrativas do bot")
    @GetMapping("/general")
    public GeneralBotMetricsResponse getGeneralMetrics() {
        return adminMetricsService.getGeneralMetrics();
    }
}
