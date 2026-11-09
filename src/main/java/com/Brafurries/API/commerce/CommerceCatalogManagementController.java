package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceCatalogDtos.*;
import static com.Brafurries.API.commerce.CommerceArtistDtos.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Mutations only: each method also checks authorization in its service. */
@RestController
@RequestMapping("/user/me/brafurries-commerce")
public class CommerceCatalogManagementController {
    public record ActiveChange(@NotNull Boolean active) {}
    public record ArtworkArchiveChange(@NotNull Boolean archived) {}
    private final CommerceCatalogManagementService management;
    public CommerceCatalogManagementController(CommerceCatalogManagementService management) {
        this.management=management;
    }

    @PutMapping("/catalog/artworks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void artwork(Authentication auth,@PathVariable long id,
                        @Valid @RequestBody CreateArtwork input) {
        management.updateArtwork(auth,id,input);
    }

    @PostMapping("/catalog/artworks/{id}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archiveArtwork(Authentication auth,@PathVariable long id,
                               @Valid @RequestBody ArtworkArchiveChange input) {
        management.setArtworkArchived(auth,id,input.archived());
    }

    @DeleteMapping("/catalog/artworks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteArtwork(Authentication auth,@PathVariable long id) {
        management.deleteArtwork(auth,id);
    }

    @PutMapping("/catalog/suppliers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supplier(Authentication auth,@PathVariable long id,
                         @Valid @RequestBody CreateSupplier input) {
        management.updateSupplier(auth,id,input);
    }

    @PostMapping("/catalog/suppliers/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void supplierStatus(Authentication auth,@PathVariable long id,
                               @Valid @RequestBody ActiveChange input) {
        management.setSupplierActive(auth,id,input.active());
    }

    @DeleteMapping("/catalog/suppliers/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSupplier(Authentication auth,@PathVariable long id) {
        management.deleteSupplier(auth,id);
    }

    @PutMapping("/catalog/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void product(Authentication auth,@PathVariable long id,
                        @Valid @RequestBody CreateProduct input) {
        management.updateProduct(auth,id,input);
    }

    @PostMapping("/catalog/products/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void productStatus(Authentication auth,@PathVariable long id,
                              @Valid @RequestBody ActiveChange input) {
        management.setProductActive(auth,id,input.active());
    }

    @DeleteMapping("/catalog/products/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProduct(Authentication auth,@PathVariable long id) {
        management.deleteProduct(auth,id);
    }

    @PutMapping("/catalog/contract-templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void template(Authentication auth,@PathVariable long id,
                         @Valid @RequestBody CreateTemplate input) {
        management.updateTemplate(auth,id,input);
    }

    @PostMapping("/catalog/contract-templates/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void templateStatus(Authentication auth,@PathVariable long id,
                               @Valid @RequestBody ActiveChange input) {
        management.setTemplateActive(auth,id,input.active());
    }

    @DeleteMapping("/catalog/contract-templates/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTemplate(Authentication auth,@PathVariable long id) {
        management.deleteTemplate(auth,id);
    }

    @DeleteMapping("/catalog/products/{productId}/artworks/{artworkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(Authentication auth,@PathVariable long productId,
                       @PathVariable long artworkId) {
        management.unlinkArtwork(auth,productId,artworkId);
    }

    @PutMapping("/artist/profiles/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void artist(Authentication auth,@PathVariable int id,
                       @Valid @RequestBody RegisterExternalArtist input) {
        management.updateArtist(auth,id,input);
    }
}
