package com.Brafurries.API.event;

import com.Brafurries.API.storage.R2ImageStorageService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class R2EventLogoStorageService implements EventLogoStorageService {

    private final R2ImageStorageService storage;

    public R2EventLogoStorageService(R2ImageStorageService storage) {
        this.storage = storage;
    }

    @Override
    public StoredEventLogo uploadEventLogo(Integer eventId, MultipartFile file) {
        var stored = storage.upload("events/%d/logo/%s.webp".formatted(eventId, UUID.randomUUID()), file, "logo do evento");
        return new StoredEventLogo(stored.key(), stored.url(), stored.contentType());
    }

    @Override
    public void deleteEventLogoByUrl(String url) {
        storage.deleteByPublicUrl(url, "logo do evento");
    }
}
