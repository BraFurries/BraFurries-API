package com.Brafurries.API.user;

import com.Brafurries.API.storage.R2ImageStorageService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class R2ProfileImageStorageService implements ProfileImageStorageService {

    private final R2ImageStorageService storage;

    public R2ProfileImageStorageService(R2ImageStorageService storage) {
        this.storage = storage;
    }

    @Override
    public StoredProfileImage uploadProfileImage(Integer userId, MultipartFile file) {
        var stored = storage.upload("users/%d/profile/%s.webp".formatted(userId, UUID.randomUUID()), file, "foto de perfil");
        return new StoredProfileImage(stored.key(), stored.url(), stored.contentType());
    }

    @Override
    public void deleteProfileImage(String key) {
        storage.deleteByKey(key, "foto de perfil");
    }
}
