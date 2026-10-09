package com.Brafurries.API.user;

import com.Brafurries.API.storage.R2ImageStorageService;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ThemeAssetStorageService {
    private final R2ImageStorageService storage;

    public ThemeAssetStorageService(R2ImageStorageService storage) {
        this.storage = storage;
    }

    public R2ImageStorageService.StoredOriginalImage uploadDefinition(
        long guildId,
        long themeId,
        String assetType,
        MultipartFile file
    ) {
        String normalized = normalizeAssetType(assetType);
        return storage.uploadOriginal(
            definitionPrefix(guildId, themeId)
                + normalized.toLowerCase(Locale.ROOT)
                + "/"
                + UUID.randomUUID()
                + extension(file == null ? null : file.getContentType()),
            file,
            "asset de Theme"
        );
    }

    public R2ImageStorageService.StoredOriginalImage uploadRollback(
        long guildId,
        long applicationId,
        String assetType,
        byte[] bytes,
        String contentType
    ) {
        String normalized = normalizeAssetType(assetType);
        return storage.uploadOriginal(
            rollbackPrefix(guildId, applicationId)
                + normalized.toLowerCase(Locale.ROOT)
                + "/"
                + UUID.randomUUID()
                + extension(contentType),
            bytes,
            contentType,
            "rollback de Theme"
        );
    }

    public R2ImageStorageService.StoredOriginalBytes readDefinition(
        long guildId,
        long themeId,
        String key
    ) {
        return storage.readOriginal(
            key,
            definitionPrefix(guildId, themeId),
            "asset do Theme"
        );
    }

    public R2ImageStorageService.StoredOriginalBytes readRollback(
        long guildId,
        long applicationId,
        String key
    ) {
        return storage.readOriginal(
            key,
            rollbackPrefix(guildId, applicationId),
            "asset de rollback do Theme"
        );
    }

    public String publicDefinitionUrl(long guildId, long themeId, String key) {
        return storage.publicUrlForManagedKey(
            key,
            definitionPrefix(guildId, themeId)
        );
    }

    public void deleteDefinition(long guildId, long themeId, String key) {
        storage.deleteByKey(
            key,
            definitionPrefix(guildId, themeId),
            "asset do Theme"
        );
    }

    public void deleteRollback(long guildId, long applicationId, String key) {
        storage.deleteByKey(
            key,
            rollbackPrefix(guildId, applicationId),
            "asset de rollback do Theme"
        );
    }

    public String definitionPrefix(long guildId, long themeId) {
        return "themes/%d/%d/definition/".formatted(guildId, themeId);
    }

    public String rollbackPrefix(long guildId, long applicationId) {
        return "themes/%d/applications/%d/rollback/".formatted(
            guildId,
            applicationId
        );
    }

    public String normalizeAssetType(String assetType) {
        String value = assetType == null
            ? ""
            : assetType.trim().toUpperCase(Locale.ROOT);
        if (!value.equals("ICON") && !value.equals("BANNER")) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "assetType deve ser ICON ou BANNER"
            );
        }
        return value;
    }

    private String extension(String contentType) {
        String normalized = contentType == null
            ? ""
            : contentType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif" -> ".gif";
            default -> ".img";
        };
    }
}
