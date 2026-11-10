package com.Brafurries.API.partner;

import org.springframework.web.multipart.MultipartFile;

public interface PartnerImageStorageService {

    StoredPartnerImage uploadPartnerImage(Integer partnerId, MultipartFile file);

    void deletePartnerImageByUrl(Integer partnerId, String url);

    void deletePartnerImageByKey(Integer partnerId, String key);

    /** Returns null for externally hosted URLs; only reads the partner's own R2 key. */
    ManagedPartnerImage readManagedPartnerImage(Integer partnerId, String publicUrl);

    record ManagedPartnerImage(byte[] bytes, String contentType, String sha256) {}

    record StoredPartnerImage(String key, String url, String contentType) {}
}
