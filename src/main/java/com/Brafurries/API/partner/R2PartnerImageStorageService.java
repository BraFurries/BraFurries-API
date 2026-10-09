package com.Brafurries.API.partner;

import com.Brafurries.API.storage.R2ImageStorageService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class R2PartnerImageStorageService implements PartnerImageStorageService {

    private final R2ImageStorageService storage;

    public R2PartnerImageStorageService(R2ImageStorageService storage) {
        this.storage = storage;
    }

    @Override
    public StoredPartnerImage uploadPartnerImage(Integer partnerId, MultipartFile file) {
        var stored = storage.upload("partners/%d/image/%s.webp".formatted(partnerId, UUID.randomUUID()), file, "imagem da parceria");
        return new StoredPartnerImage(stored.key(), stored.url(), stored.contentType());
    }

    @Override
    public void deletePartnerImageByUrl(Integer partnerId, String url) {
        storage.deleteByPublicUrl(url, keyPrefix(partnerId), "imagem da parceria");
    }

    @Override
    public void deletePartnerImageByKey(Integer partnerId, String key) {
        storage.deleteByKey(key, keyPrefix(partnerId), "imagem da parceria");
    }

    private String keyPrefix(Integer partnerId) {
        return "partners/%d/image/".formatted(partnerId);
    }
}
