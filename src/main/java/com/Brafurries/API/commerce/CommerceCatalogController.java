package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceCatalogDtos.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Commercial catalog; deliberately outside /communities/{id}. */
@RestController
@RequestMapping("/user/me/brafurries-commerce/catalog")
public class CommerceCatalogController {
    private final CommerceCatalogService catalog;

    public CommerceCatalogController(CommerceCatalogService catalog) {
        this.catalog=catalog;
    }

    @GetMapping("/contract-templates")
    public List<Map<String,Object>> templates(Authentication auth) {
        return catalog.templates(auth);
    }

    @PostMapping("/contract-templates")
    @ResponseStatus(HttpStatus.CREATED)
    public Created createTemplate(Authentication auth,@Valid @RequestBody CreateTemplate request) {
        return catalog.createTemplate(auth,request);
    }

    @GetMapping("/artworks")
    public List<Map<String,Object>> artworks(Authentication auth) {
        return catalog.artworks(auth);
    }

    @GetMapping("/artists/search")
    public List<Map<String,Object>> searchArtists(Authentication auth,@RequestParam String search) {
        return catalog.searchArtists(auth,search);
    }

    @PostMapping("/artworks")
    @ResponseStatus(HttpStatus.CREATED)
    public Created createArtwork(Authentication auth,@Valid @RequestBody CreateArtwork request) {
        return catalog.createArtwork(auth,request);
    }

    @GetMapping("/suppliers")
    public List<Map<String,Object>> suppliers(Authentication auth) {
        return catalog.suppliers(auth);
    }

    @PostMapping("/suppliers")
    @ResponseStatus(HttpStatus.CREATED)
    public Created createSupplier(Authentication auth,@Valid @RequestBody CreateSupplier request) {
        return catalog.createSupplier(auth,request);
    }

    @GetMapping("/suppliers/choices")
    public List<Map<String,Object>> supplierChoices(Authentication auth) {
        return catalog.supplierChoices(auth);
    }

    @GetMapping("/products")
    public List<Map<String,Object>> products(Authentication auth) {
        return catalog.products(auth);
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public Created createProduct(Authentication auth,@Valid @RequestBody CreateProduct request) {
        return catalog.createProduct(auth,request);
    }

    @GetMapping("/products/{productId}/artworks")
    public List<Map<String,Object>> productArtworks(Authentication auth,@PathVariable long productId) {
        return catalog.productArtworks(auth,productId);
    }

    @PutMapping("/products/{productId}/artworks/{artworkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void linkArtwork(Authentication auth,@PathVariable long productId,
                            @PathVariable long artworkId,@Valid @RequestBody LinkArtwork request) {
        catalog.linkArtwork(auth,productId,artworkId,request);
    }

    @GetMapping("/production-batches")
    public List<Map<String,Object>> productionBatches(Authentication auth) {
        return catalog.productionBatches(auth);
    }

    @PostMapping("/production-batches")
    @ResponseStatus(HttpStatus.CREATED)
    public Created createProductionBatch(Authentication auth,
                                         @Valid @RequestBody CreateProductionBatch request) {
        return catalog.createProductionBatch(auth,request);
    }

    @GetMapping("/artworks/{artworkId}/contracts")
    public List<Map<String,Object>> contracts(Authentication auth,@PathVariable long artworkId) {
        return catalog.contracts(auth,artworkId);
    }

    @GetMapping(path="/artworks/{artworkId}/contracts/{contractId}/download",
                produces=MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> download(Authentication auth,@PathVariable long artworkId,
                                           @PathVariable long contractId) {
        byte[] bytes=catalog.downloadContract(auth,artworkId,contractId);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options","nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=contrato-" + contractId + ".pdf")
            .body(bytes);
    }

    public record OrphanedContractPdfInput(String key) {}

    @PostMapping("/contract-documents/cleanup-orphan")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOrphanedContractPdf(Authentication auth,
                                          @RequestBody OrphanedContractPdfInput input) {
        catalog.deleteOrphanedContractPdf(auth,input==null?null:input.key());
    }

    @DeleteMapping("/artworks/{artworkId}/contracts/{contractId}/unused-test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discardUnusedTestContract(Authentication auth,@PathVariable long artworkId,
                                           @PathVariable long contractId) {
        catalog.discardUnusedTestContract(auth,artworkId,contractId);
    }

    @PostMapping("/artworks/{artworkId}/contracts/{contractId}/terminate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void terminate(Authentication auth,@PathVariable long artworkId,
                          @PathVariable long contractId) {
        catalog.terminateContract(auth,artworkId,contractId);
    }
}
