package com.Brafurries.API.storage;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
public class R2ImageStorageService {

    private static final long MAX_IMAGE_BYTES = 5L * 1024L * 1024L;
    private static final long MAX_ORIGINAL_IMAGE_BYTES = 10L * 1024L * 1024L;
    private static final String STORED_CONTENT_TYPE = "image/webp";
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Set<String> ALLOWED_ORIGINAL_CONTENT_TYPES =
        Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final String endpoint;
    private final String bucket;
    private final String publicBaseUrl;
    private final String accessKey;
    private final String secretKey;

    public R2ImageStorageService(
        @Value("${app.storage.r2.endpoint:}") String endpoint,
        @Value("${app.storage.r2.bucket:}") String bucket,
        @Value("${app.storage.r2.public-base-url:}") String publicBaseUrl,
        @Value("${app.storage.r2.access-key:}") String accessKey,
        @Value("${app.storage.r2.secret-key:}") String secretKey
    ) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    public StoredImage upload(String key, MultipartFile file, String subject) {
        validateFile(file, subject);
        byte[] webpImage = convertToWebp(file, subject);
        validateStorageConfiguration();
        try (S3Client client = createClient()) {
            client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(STORED_CONTENT_TYPE)
                .contentLength((long) webpImage.length).build(), RequestBody.fromBytes(webpImage));
        } catch (S3Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Falha ao salvar " + subject + " no R2", ex);
        }
        return new StoredImage(key, buildPublicUrl(key), STORED_CONTENT_TYPE);
    }

    public StoredOriginalImage uploadOriginal(
        String key,
        byte[] bytes,
        String contentType,
        String subject
    ) {
        byte[] safeBytes = validateOriginalBytes(bytes, contentType, subject);
        String normalizedType = normalizeContentType(contentType);
        validateStorageConfiguration();
        try (S3Client client = createClient()) {
            client.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(normalizedType)
                    .contentLength((long) safeBytes.length)
                    .build(),
                RequestBody.fromBytes(safeBytes)
            );
        } catch (S3Exception ex) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Falha ao salvar " + subject + " no R2",
                ex
            );
        }
        return new StoredOriginalImage(
            key,
            buildPublicUrl(key),
            normalizedType,
            sha256(safeBytes),
            safeBytes.length
        );
    }

    public StoredOriginalImage uploadOriginal(
        String key,
        MultipartFile file,
        String subject
    ) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Arquivo de " + subject + " é obrigatório"
            );
        }
        if (file.getSize() > MAX_ORIGINAL_IMAGE_BYTES) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                subject + " deve ter no máximo 10 MB"
            );
        }
        try {
            return uploadOriginal(key, file.getBytes(), file.getContentType(), subject);
        } catch (IOException ex) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Falha ao ler " + subject,
                ex
            );
        }
    }

    public StoredOriginalBytes readOriginal(
        String key,
        String requiredKeyPrefix,
        String subject
    ) {
        if (isBlank(key) || isBlank(requiredKeyPrefix) || !key.startsWith(requiredKeyPrefix)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, subject + " não encontrado");
        }
        validateStorageConfiguration();
        try (S3Client client = createClient()) {
            var head = client.headObject(
                HeadObjectRequest.builder().bucket(bucket).key(key).build()
            );
            byte[] bytes = client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build()
            ).asByteArray();
            return new StoredOriginalBytes(
                bytes,
                normalizeContentType(head.contentType()),
                sha256(bytes)
            );
        } catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, subject + " não encontrado", ex);
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, subject + " não encontrado", ex);
            }
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Falha ao ler " + subject + " no R2",
                ex
            );
        }
    }

    public String publicUrlForManagedKey(String key, String requiredKeyPrefix) {
        if (isBlank(key) || isBlank(requiredKeyPrefix) || !key.startsWith(requiredKeyPrefix)) {
            return null;
        }
        return buildPublicUrl(key);
    }

    public void deleteByKey(String key, String subject) {
        if (isBlank(key)) return;
        validateStorageConfiguration();
        try (S3Client client = createClient()) {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Falha ao remover " + subject + " do R2", ex);
        }
    }

    public void deleteByPublicUrl(String url, String subject) {
        String key = resolveKeyFromPublicUrl(url);
        if (key != null) deleteByKey(key, subject);
    }

    public void deleteByPublicUrl(String url, String requiredKeyPrefix, String subject) {
        String key = resolveManagedKey(url, requiredKeyPrefix);
        if (key != null) deleteByKey(key, subject);
    }

    public void deleteByKey(String key, String requiredKeyPrefix, String subject) {
        if (key != null && key.startsWith(requiredKeyPrefix)) deleteByKey(key, subject);
    }

    byte[] convertToWebp(MultipartFile file, String subject) {
        try {
            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Arquivo enviado não é uma imagem válida");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "webp", output)) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Conversor WebP não disponível");
            }
            return output.toByteArray();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falha ao converter " + subject + " para WebP", ex);
        }
    }

    private byte[] validateOriginalBytes(byte[] bytes, String contentType, String subject) {
        if (bytes == null || bytes.length == 0) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Arquivo de " + subject + " é obrigatório"
            );
        }
        if (bytes.length > MAX_ORIGINAL_IMAGE_BYTES) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                subject + " deve ter no máximo 10 MB"
            );
        }
        String normalizedType = normalizeContentType(contentType);
        if (!ALLOWED_ORIGINAL_CONTENT_TYPES.contains(normalizedType)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                subject + " deve ser JPEG, PNG, WebP ou GIF"
            );
        }
        try {
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (image == null) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Arquivo enviado não é uma imagem válida"
                );
            }
        } catch (IOException ex) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Falha ao validar " + subject,
                ex
            );
        }
        return bytes;
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 indisponível", impossible);
        }
    }

    private void validateFile(MultipartFile file, String subject) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Arquivo de " + subject + " é obrigatório");
        if (file.getSize() > MAX_IMAGE_BYTES) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, subject + " deve ter no máximo 5 MB");
        if (!ALLOWED_CONTENT_TYPES.contains(normalizeContentType(file.getContentType()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, subject + " deve ser JPEG, PNG ou WebP");
        }
    }

    private void validateStorageConfiguration() {
        if (isBlank(endpoint) || isBlank(bucket) || isBlank(publicBaseUrl) || isBlank(accessKey) || isBlank(secretKey)) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Storage R2 não configurado");
        }
    }

    private S3Client createClient() {
        return S3Client.builder().endpointOverride(URI.create(endpoint.trim())).region(Region.of("auto"))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .forcePathStyle(true).build();
    }

    private String buildPublicUrl(String key) { return publicBaseUrl.replaceAll("/+$", "") + "/" + key; }

    private String resolveKeyFromPublicUrl(String url) {
        if (isBlank(url) || isBlank(publicBaseUrl)) return null;
        String base = publicBaseUrl.replaceAll("/+$", "") + "/";
        if (!url.trim().startsWith(base)) return null;
        String key = url.trim().substring(base.length());
        return key.isBlank() ? null : key;
    }

    String resolveManagedKey(String url, String requiredKeyPrefix) {
        String key = resolveKeyFromPublicUrl(url);
        return key != null && key.startsWith(requiredKeyPrefix) ? key : null;
    }

    private String normalizeContentType(String contentType) { return contentType == null ? "" : contentType.trim().toLowerCase(); }
    private boolean isBlank(String value) { return value == null || value.isBlank(); }

    public record StoredImage(String key, String url, String contentType) {}
    public record StoredOriginalImage(
        String key,
        String url,
        String contentType,
        String sha256,
        long sizeBytes
    ) {}
    public record StoredOriginalBytes(byte[] bytes, String contentType, String sha256) {}
}
