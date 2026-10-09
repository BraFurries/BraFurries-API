package com.Brafurries.API.partner;

import com.Brafurries.API.storage.R2ImageStorageService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class R2PartnerImageStorageServiceTest {

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
