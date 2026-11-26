package com.Brafurries.API.commerce;

import jakarta.validation.Valid;
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

    @PutMapping("/{variantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void update(Authentication auth,@PathVariable long productId,@PathVariable long variantId,
        @Valid @RequestBody CommerceVariantService.Update input) {
        variants.update(auth,productId,variantId,input);
    }

    @GetMapping("/statistics")
    public List<Map<String,Object>> statistics(Authentication auth,@PathVariable long productId) {
        return variants.statistics(auth,productId);
    }
}
