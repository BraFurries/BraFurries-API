package com.Brafurries.API.internal;

import com.Brafurries.API.user.ThemeAssetStorageService;
import com.Brafurries.API.user.ThemeControlPlaneStore;
import com.Brafurries.API.user.ThemeControlPlaneStore.ApplicationStored;
import com.Brafurries.API.user.dto.ThemeDtos.ManagedThemeAssetResponse;
import com.Brafurries.API.user.dto.ThemeDtos.RollbackAssetUploadRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class InternalThemeAssetService {
    private final ThemeControlPlaneStore store;
    private final ThemeAssetStorageService assets;
    private final ObjectMapper objectMapper;

    public InternalThemeAssetService(
        ThemeControlPlaneStore store,
        ThemeAssetStorageService assets,
        ObjectMapper objectMapper
    ) {
        this.store = store;
        this.assets = assets;
        this.objectMapper = objectMapper;
    }

    public ManagedThemeAssetResponse uploadRollback(
        long guildId,
        long applicationId,
        RollbackAssetUploadRequest request
    ) {
        ApplicationStored application = requireApplication(guildId, applicationId);
        if (!"APPLYING".equals(application.status())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Rollback asset só pode ser capturado durante APPLYING"
            );
        }
        String assetType = assets.normalizeAssetType(request.assetType());
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(request.base64Data());
        } catch (IllegalArgumentException invalid) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Asset de rollback possui base64 inválido"
            );
        }
        String actualHash = sha256(bytes);
        if (!MessageDigest.isEqual(
            actualHash.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            request.sha256().toLowerCase(Locale.ROOT)
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII)
        )) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Hash do asset de rollback não confere"
            );
        }

        var stored = assets.uploadRollback(
            guildId,
            applicationId,
            assetType,
            bytes,
            request.contentType()
        );
        return new ManagedThemeAssetResponse(
            stored.key(),
            stored.contentType(),
            stored.sha256(),
            stored.sizeBytes()
        );
    }

    public StoredAssetBytes desired(
        long guildId,
        long applicationId,
        String assetType
    ) {
        ApplicationStored application = requireApplication(guildId, applicationId);
        String normalized = assets.normalizeAssetType(assetType);
        JsonNode frozen = readJson(application.frozenDefinitionJson());
        JsonNode asset = frozen.path(
            "ICON".equals(normalized) ? "icon" : "banner"
        );
        if (!"SET".equals(asset.path("action").asText())) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Application não possui esse asset desejado"
            );
        }
        String key = asset.path("assetKey").asText(null);
        String expectedHash = asset.path("sha256").asText(null);
        if (key == null || expectedHash == null) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Definição congelada do asset está incompleta"
            );
        }
        var stored = assets.readDefinition(
            guildId,
            application.themeId(),
            key
        );
        if (!expectedHash.equalsIgnoreCase(stored.sha256())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Asset desejado não corresponde à definição congelada"
            );
        }
        return new StoredAssetBytes(
            stored.bytes(),
            stored.contentType(),
            stored.sha256()
        );
    }

    public StoredAssetBytes rollback(
        long guildId,
        long applicationId,
        String assetType
    ) {
        requireApplication(guildId, applicationId);
        String normalized = assets.normalizeAssetType(assetType);
        String resourceType = "ICON".equals(normalized)
            ? "GUILD_ICON"
            : "GUILD_BANNER";
        var snapshot = store.findRollbackAsset(
            guildId,
            applicationId,
            resourceType
        );
        if (snapshot == null) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Application não possui asset de rollback para este campo"
            );
        }
        var stored = assets.readRollback(
            guildId,
            applicationId,
            snapshot.key()
        );
        if (snapshot.sha256() != null
            && !snapshot.sha256().equalsIgnoreCase(stored.sha256())) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Asset de rollback não corresponde ao snapshot persistido"
            );
        }
        return new StoredAssetBytes(
            stored.bytes(),
            stored.contentType(),
            stored.sha256()
        );
    }

    private ApplicationStored requireApplication(long guildId, long applicationId) {
        ApplicationStored application = store.findApplication(guildId, applicationId);
        if (application == null) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Application de Theme não encontrada nesta guild"
            );
        }
        return application;
    }

    private JsonNode readJson(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException error) {
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Definição congelada do Theme está inválida",
                error
            );
        }
    }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponível", impossible);
        }
    }

    public record StoredAssetBytes(
        byte[] bytes,
        String contentType,
        String sha256
    ) {}
}
