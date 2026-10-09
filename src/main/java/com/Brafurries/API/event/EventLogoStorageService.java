package com.Brafurries.API.event;

import org.springframework.web.multipart.MultipartFile;

public interface EventLogoStorageService {

    StoredEventLogo uploadEventLogo(Integer eventId, MultipartFile file);

    void deleteEventLogoByUrl(String url);

    record StoredEventLogo(
        String key,
        String url,
        String contentType
    ) {
    }
}
