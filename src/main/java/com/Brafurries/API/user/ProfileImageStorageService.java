package com.Brafurries.API.user;

import org.springframework.web.multipart.MultipartFile;

public interface ProfileImageStorageService {

    StoredProfileImage uploadProfileImage(Integer userId, MultipartFile file);

    void deleteProfileImage(String key);

    record StoredProfileImage(
        String key,
        String url,
        String contentType
    ) {
    }
}
