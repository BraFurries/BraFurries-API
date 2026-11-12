package com.Brafurries.API.commerce;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Permanent owner-only removal; never exposed for signed agreements. */
@RestController
@RequestMapping("/user/me/brafurries-commerce/catalog/artworks/{artworkId}/contract-preparations")
public class CommerceUnsignedPreparationDeletionController {
    private final CommerceUnsignedPreparationDeletionService deletion;

    public CommerceUnsignedPreparationDeletionController(CommerceUnsignedPreparationDeletionService deletion) {
        this.deletion=deletion;
    }

    @DeleteMapping("/{preparationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication auth,@PathVariable long artworkId,
                       @PathVariable long preparationId) {
        deletion.delete(auth,artworkId,preparationId,null);
    }
}
