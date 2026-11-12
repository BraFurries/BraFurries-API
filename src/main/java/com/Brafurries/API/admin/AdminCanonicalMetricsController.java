package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminCanonicalMetricsDtos.Report;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Cross-Community report, exclusively available to global platform admins. */
@RestController
@RequestMapping("/admin/metrics/canonical")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCanonicalMetricsController {
    private final AdminCanonicalMetricsService service;

    public AdminCanonicalMetricsController(AdminCanonicalMetricsService service) {
        this.service=service;
    }

    @GetMapping
    public ResponseEntity<Report> report(@RequestParam(defaultValue="7d") String period) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.read(period));
    }
}
