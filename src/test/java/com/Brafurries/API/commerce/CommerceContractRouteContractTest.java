package com.Brafurries.API.commerce;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class CommerceContractRouteContractTest {
    @Test
    void legacyDirectSignedUploadEndpointIsNotRegistered() {
        boolean legacyRegistered = Arrays.stream(CommerceCatalogController.class.getDeclaredMethods())
            .map(method -> method.getAnnotation(PostMapping.class))
            .filter(annotation -> annotation != null)
            .flatMap(annotation -> Arrays.stream(annotation.value()))
            .anyMatch("/artworks/{artworkId}/contracts"::equals);
        assertFalse(legacyRegistered,
            "Signed contracts must be uploaded through a frozen preparation, never directly");
    }

    @Test
    void preparationApiRetainsExplicitDownloadAndSignedUploadRoutes() {
        RequestMapping mapping = CommerceContractPreparationController.class
            .getAnnotation(RequestMapping.class);
        assertNotNull(mapping);
        assertArrayEquals(new String[]{
            "/user/me/brafurries-commerce/catalog/artworks/{artworkId}/contract-preparations"
        },mapping.value());
        boolean signedEndpoint = Arrays.stream(CommerceContractPreparationController.class.getDeclaredMethods())
            .map(method -> method.getAnnotation(PostMapping.class))
            .filter(annotation -> annotation != null)
            .flatMap(annotation -> Arrays.stream(annotation.path().length > 0
                ? annotation.path() : annotation.value()))
            .anyMatch("/{preparationId}/signed"::equals);
        assertTrue(signedEndpoint);
    }
}
