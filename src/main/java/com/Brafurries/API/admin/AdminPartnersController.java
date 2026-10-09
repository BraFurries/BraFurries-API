package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminDtos.AdminPartnerItem;
import com.Brafurries.API.admin.dto.AdminDtos.AdminPartnersResponse;
import com.Brafurries.API.admin.dto.AdminDtos.CreatePartnerRequest;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerImageResponse;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerLinkCandidate;
import com.Brafurries.API.admin.dto.AdminDtos.UpdatePartnerRequest;
import com.Brafurries.API.admin.dto.AdminDtos.UpdatePartnerStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/admin/partners")
public class AdminPartnersController {

    private final AdminPartnersService adminPartnersService;

    public AdminPartnersController(AdminPartnersService adminPartnersService) {
        this.adminPartnersService = adminPartnersService;
    }

    @Operation(summary = "Lista parcerias administrativas")
    @GetMapping
    public AdminPartnersResponse listPartners(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String category
    ) {
        return adminPartnersService.listPartners(status, category);
    }

    @Operation(summary = "Detalha parceria administrativa")
    @GetMapping("/{id}")
    public AdminPartnerItem getPartner(@PathVariable Integer id) {
        return adminPartnersService.get(id);
    }

    @Operation(summary = "Pesquisa recursos vinculaveis a parcerias")
    @GetMapping("/linkable-resources")
    public List<PartnerLinkCandidate> listLinkableResources(
        @RequestParam String category,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "20") Integer limit
    ) {
        return adminPartnersService.listLinkableResources(category, search, limit);
    }

    @Operation(summary = "Cria parceria")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminPartnerItem createPartner(@Valid @RequestBody CreatePartnerRequest request) {
        return adminPartnersService.create(request);
    }

    @Operation(summary = "Atualiza parceria")
    @PatchMapping("/{id}")
    public AdminPartnerItem updatePartner(
        @PathVariable Integer id,
        @RequestBody UpdatePartnerRequest request
    ) {
        return adminPartnersService.update(id, request);
    }

    @Operation(summary = "Envia imagem da parceria")
    @PostMapping(path = "/{id}/image", consumes = "multipart/form-data")
    public PartnerImageResponse uploadPartnerImage(@PathVariable Integer id, @RequestPart("file") MultipartFile file) {
        return adminPartnersService.uploadImage(id, file);
    }

    @Operation(summary = "Remove imagem da parceria")
    @DeleteMapping("/{id}/image")
    public PartnerImageResponse deletePartnerImage(@PathVariable Integer id) {
        return adminPartnersService.deleteImage(id);
    }

    @Operation(summary = "Atualiza status da parceria")
    @PatchMapping("/{id}/status")
    public AdminPartnerItem updatePartnerStatus(
        @PathVariable Integer id,
        @Valid @RequestBody UpdatePartnerStatusRequest request
    ) {
        return adminPartnersService.updateStatus(id, request);
    }

    @Operation(summary = "Remove parceria")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePartner(@PathVariable Integer id) {
        adminPartnersService.delete(id);
    }
}
