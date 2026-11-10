package com.Brafurries.API.partner;

import com.Brafurries.API.storage.R2ImageStorageService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class R2PartnerImageStorageServiceTest {

    @Test
    void readsOnlyManagedImagesWithTheOwningPartnerPrefix() {
        R2ImageStorageService storage = mock(R2ImageStorageService.class);
        var bytes = new byte[] {1, 2, 3};
        when(storage.readManagedImageByPublicUrl(
            "https://cdn.example/partners/10/image/a.webp",
            "partners/10/image/", "imagem da parceria"
        )).thenReturn(new R2ImageStorageService.StoredOriginalBytes(bytes, "image/webp", "digest"));
        var result = new R2PartnerImageStorageService(storage)
            .readManagedPartnerImage(10, "https://cdn.example/partners/10/image/a.webp");
        assertEquals("image/webp", result.contentType());
        assertEquals("digest", result.sha256());
        assertEquals(3, result.bytes().length);
        assertNull(new R2PartnerImageStorageService(storage)
            .readManagedPartnerImage(10, "https://other.example/image.webp"));
    }

    @Test
    void delegatesDeletesWithTheOwningPartnerPrefix() {
        R2ImageStorageService storage = mock(R2ImageStorageService.class);
        R2PartnerImageStorageService service = new R2PartnerImageStorageService(storage);

        service.deletePartnerImageByUrl(10, "https://cdn.example/partners/10/image/a.webp");
        service.deletePartnerImageByKey(10, "partners/10/image/a.webp");

        verify(storage).deleteByPublicUrl("https://cdn.example/partners/10/image/a.webp", "partners/10/image/", "imagem da parceria");
        verify(storage).deleteByKey("partners/10/image/a.webp", "partners/10/image/", "imagem da parceria");
    }
}
