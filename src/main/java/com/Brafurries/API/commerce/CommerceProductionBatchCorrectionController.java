package com.Brafurries.API.commerce;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.Brafurries.API.commerce.CommerceCatalogDtos.CreateProductionBatch;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/me/brafurries-commerce/catalog/production-batches")
public class CommerceProductionBatchCorrectionController {
    public record CorrectBatch(
        @Valid @NotNull CreateProductionBatch data,
        @NotBlank @Size(min=3,max=500) String reason
    ) {}
    private final CommerceProductionBatchCorrectionService service;
    public CommerceProductionBatchCorrectionController(
        CommerceProductionBatchCorrectionService service) {
        this.service=service;
    }
    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void correct(Authentication auth,@PathVariable long id,
                        @Valid @RequestBody CorrectBatch input) {
        service.correct(auth,id,new CommerceProductionBatchCorrectionService.Correction(
            input.data(),input.reason()));
    }
    @GetMapping("/{id}/revisions")
    public List<Map<String,Object>> history(Authentication auth,@PathVariable long id) {
        return service.revisions(auth,id);
    }
}
