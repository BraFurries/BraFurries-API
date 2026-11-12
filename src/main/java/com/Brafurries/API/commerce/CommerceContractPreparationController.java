package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceContractPreparationDtos.*;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Authenticated staff only. The preparation PDF is unsigned and never becomes
 * an active contract until a separate, human-reviewed signed upload succeeds.
 */
@RestController
@RequestMapping("/user/me/brafurries-commerce/catalog/artworks/{artworkId}/contract-preparations")
public class CommerceContractPreparationController {
    private final CommerceContractPreparationService service;

    public CommerceContractPreparationController(CommerceContractPreparationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Map<String,Object>> list(Authentication auth,@PathVariable long artworkId) {
        return service.list(auth,artworkId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Created prepare(Authentication auth,@PathVariable long artworkId,
                           @Valid @RequestBody PrepareContract request) {
        return service.prepare(auth,artworkId,request);
    }

    @GetMapping(path="/{preparationId}/download",produces=MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> download(Authentication auth,@PathVariable long artworkId,
                                          @PathVariable long preparationId) {
        byte[] bytes=service.download(auth,artworkId,preparationId);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options","nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=contrato-para-assinatura-" + preparationId + ".pdf")
            .body(bytes);
    }

    @PostMapping(path="/{preparationId}/signed",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CommerceCatalogDtos.Created signed(Authentication auth,
            @PathVariable long artworkId,@PathVariable long preparationId,
            @RequestParam(defaultValue="false") boolean reviewed,
            @RequestPart("file") MultipartFile file) {
        return service.uploadSigned(auth,artworkId,preparationId,reviewed,file);
    }

    @PostMapping("/{preparationId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(Authentication auth,@PathVariable long artworkId,
                       @PathVariable long preparationId) {
        service.cancel(auth,artworkId,preparationId);
    }
}
