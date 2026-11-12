package com.Brafurries.API.storage;

import com.Brafurries.API.commerce.CommerceUnsignedPreparationDeletionService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Storage console never deletes a linked object directly. It invokes the same
 * owner-only business operation as the contract catalog after fresh inspection.
 */
@RestController
@RequestMapping("/user/me/administration/storage")
public class AdminUnsignedPreparationDeletionController {
    public record DeleteRequest(
        AdminStorageService.Scope bucket,String key,String etag,
        long artworkId,long preparationId
    ) {}

    private final AdminStorageService storage;
    private final CommerceUnsignedPreparationDeletionService deletion;

    public AdminUnsignedPreparationDeletionController(
        AdminStorageService storage,CommerceUnsignedPreparationDeletionService deletion
    ) {
        this.storage=storage;
        this.deletion=deletion;
    }

    @PostMapping("/objects/delete-unsigned-preparation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth,@RequestBody DeleteRequest request) {
        if(request==null || request.bucket()!=AdminStorageService.Scope.PRIVATE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Somente PDFs privados de minutas não assinadas podem ser excluídos");
        }
        if(request.key()==null || request.etag()==null || request.etag().isBlank()
            || request.artworkId()<=0 || request.preparationId()<=0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Inspecione novamente o arquivo e informe a minuta");
        }
        // Inspect requires owner and re-reads HEAD/DB without trusting the browser.
        var inspected=storage.inspect(auth,request.bucket(),request.key());
        if(inspected.category()!=AdminStorageService.Category.CONTRACT_DOCUMENT ||
            !request.etag().equals(inspected.etag())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Arquivo alterado ou não é uma minuta privada; inspecione novamente");
        }
        if(inspected.references().size()!=1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "O documento não possui um vínculo único de minuta");
        }
        var link=inspected.references().get(0);
        if(!"PREPARATION".equals(link.source()) ||
            link.artworkId()!=request.artworkId() ||
            link.recordId()!=request.preparationId() ||
            !("PENDING".equals(link.status()) || "CANCELLED".equals(link.status()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Somente minutas não assinadas podem ser excluídas");
        }
        // Transactional service rechecks status, row lock, key and all DB references.
        deletion.delete(auth,request.artworkId(),request.preparationId(),request.key());
    }
}
