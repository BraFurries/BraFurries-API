package com.Brafurries.API.commerce;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Endpoint only for private commercial artwork files, never the public CDN. */
@RestController
@RequestMapping("/user/me/brafurries-commerce/catalog/artworks/{artworkId}/files")
public class CommerceArtworkFileController {
    private final CommerceArtworkFileService service;
    public CommerceArtworkFileController(CommerceArtworkFileService service) {
        this.service=service;
    }

    @GetMapping
    public List<Map<String,Object>> files(Authentication auth,@PathVariable long artworkId) {
        return service.list(auth,artworkId);
    }

    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CommerceCatalogDtos.Created upload(Authentication auth,@PathVariable long artworkId,
                                               @RequestParam String kind,
                                               @RequestParam(required=false) String description,
                                               @RequestPart("file") MultipartFile file) {
        return service.upload(auth,artworkId,kind,description,file);
    }

    @GetMapping(path="/{fileId}/download",produces=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> download(Authentication auth,@PathVariable long artworkId,
                                           @PathVariable long fileId) {
        var downloaded=service.download(auth,artworkId,fileId);
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options","nosniff")
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment()
                .filename(downloaded.name(),StandardCharsets.UTF_8).build().toString())
            .body(downloaded.bytes());
    }

    @PostMapping("/{fileId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(Authentication auth,@PathVariable long artworkId,
                        @PathVariable long fileId) {
        service.archive(auth,artworkId,fileId);
    }
}
