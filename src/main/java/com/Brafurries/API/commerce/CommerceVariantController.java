package com.Brafurries.API.commerce;

import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/brafurries-commerce/catalog/products/{productId}/variants")
public class CommerceVariantController {
    private final CommerceVariantService variants;
    public CommerceVariantController(CommerceVariantService variants) { this.variants=variants; }

    @GetMapping
    public List<Map<String,Object>> list(Authentication auth,@PathVariable long productId) {
        return variants.list(auth,productId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommerceCatalogDtos.Created create(Authentication auth,@PathVariable long productId,
        @Valid @RequestBody CommerceVariantService.Create input) {
        return variants.create(auth,productId,input);
    }

    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    public CommerceVariantService.BatchCreated createBatch(Authentication auth,@PathVariable long productId,
        @Valid @RequestBody CommerceVariantService.BatchCreate input) {
        return variants.createBatch(auth,productId,input);
    }

    @PutMapping("/{variantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(Authentication auth,@PathVariable long productId,@PathVariable long variantId,
        @Valid @RequestBody CommerceVariantService.Update input) {
        variants.update(auth,productId,variantId,input);
    }

    @GetMapping("/statistics")
    public List<Map<String,Object>> statistics(Authentication auth,@PathVariable long productId,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to) {
        return variants.statistics(auth,productId,from,to);
    }
}
