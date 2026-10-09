package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminDtos.AnnouncementConfig;
import com.Brafurries.API.admin.dto.AdminDtos.CreateAnnouncementRequest;
import com.Brafurries.API.admin.dto.AdminDtos.CreateAnnouncementResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/admin/announcements")
public class AdminAnnouncementsController {

    private final AdminAnnouncementsService adminAnnouncementsService;

    public AdminAnnouncementsController(AdminAnnouncementsService adminAnnouncementsService) {
        this.adminAnnouncementsService = adminAnnouncementsService;
    }

    @Operation(summary = "Configuracao de envio de avisos")
    @GetMapping("/config")
    public AnnouncementConfig getConfig() {
        return adminAnnouncementsService.getConfig();
    }

    @Operation(summary = "Envia aviso para destinos configurados")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public CreateAnnouncementResponse sendAnnouncementJson(
        @Valid @RequestBody CreateAnnouncementRequest request
    ) {
        return adminAnnouncementsService.send(request.destinations(), request.title(), request.message(), null);
    }

    @Operation(summary = "Envia aviso com imagem para destinos configurados")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CreateAnnouncementResponse sendAnnouncementMultipart(
        @RequestParam List<String> destinations,
        @RequestParam String title,
        @RequestParam String message,
        @RequestPart(required = false) MultipartFile image
    ) {
        return adminAnnouncementsService.send(destinations, title, message, image);
    }
}
