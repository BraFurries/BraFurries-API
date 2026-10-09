package com.Brafurries.API.partner;

import org.springframework.web.multipart.MultipartFile;

public interface PartnerImageStorageService {

    StoredPartnerImage uploadPartnerImage(Integer partnerId, MultipartFile file);

    void deletePartnerImageByUrl(Integer partnerId, String url);

    void deletePartnerImageByKey(Integer partnerId, String key);

    record StoredPartnerImage(String key, String url, String contentType) {}
}
