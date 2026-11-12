package com.Brafurries.API.commerce;

import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
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

/**
 * Contract PDFs belong to an isolated PRIVATE bucket, never R2's public
 * image bucket. No public URL is returned, persisted or generated.
 * Uses separately scoped R2 credentials for the shared private storage area,
 * never the public media key pair. This adapter restricts keys to commerce/contracts/.
 */
@Service
public class CommercePrivateDocumentStorage {
    static final long MAX_BYTES = 8L * 1024 * 1024;
    private final String endpoint;
    private final String bucket;
    private final String publicBucket;
    private final String accessKey;
    private final String secretKey;

    public CommercePrivateDocumentStorage(
        @Value("${app.storage.r2.endpoint:}") String endpoint,
        @Value("${app.storage.r2.private.bucket:}") String bucket,
        @Value("${app.storage.r2.bucket:}") String publicBucket,
        @Value("${app.storage.r2.private.access-key:}") String accessKey,
        @Value("${app.storage.r2.private.secret-key:}") String secretKey
    ) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.publicBucket = publicBucket;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
    }

    public record StoredDocument(String key, String sha256, long sizeBytes) {}

    public StoredDocument upload(long artworkId, MultipartFile file) {
        return uploadBytes(artworkId, verifiedPdf(file));
    }

    /** Save the exact unsigned PDF bytes, privately, before exposing a download. */
    public StoredDocument uploadGenerated(long artworkId, byte[] bytes) {
        if (!validPdfBytes(bytes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "O contrato gerado não é um PDF válido de até 8 MB");
        }
        return uploadBytes(artworkId, bytes);
    }

    private StoredDocument uploadBytes(long artworkId, byte[] bytes) {
        requireConfiguration();
        String key = "commerce/contracts/" + artworkId + "/" + UUID.randomUUID() + ".pdf";
        try (S3Client client = createClient()) {
            client.putObject(
                PutObjectRequest.builder()
                    .bucket(bucket).key(key).contentType("application/pdf")
                    .contentLength((long) bytes.length).build(),
                RequestBody.fromBytes(bytes)
            );
        } catch (S3Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Não foi possível armazenar o contrato privado", ex);
        }
        return new StoredDocument(key, sha256(bytes), bytes.length);
    }

    public byte[] read(long artworkId, String key, String expectedHash) {
        if (!validKey(artworkId, key)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Contrato não encontrado");
        }
        requireConfiguration();
        try (S3Client client = createClient()) {
            var head = client.headObject(
                HeadObjectRequest.builder().bucket(bucket).key(key).build());
            if (head.contentLength() == null || head.contentLength() > MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Arquivo do contrato ultrapassa o tamanho permitido");
            }
            byte[] bytes = client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
            if (!validPdfBytes(bytes) || !sha256(bytes).equalsIgnoreCase(expectedHash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Integridade do documento assinado não confere");
            }
            return bytes;
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Contrato não encontrado", ex);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Não foi possível recuperar o contrato", ex);
        }
    }

    /**
     * Owner-only orphan cleanup. Check that the exact object exists, remove it,
     * and verify R2 no longer serves it. This does not modify the SQL database.
     */
    public void deleteOrphanedDocument(long artworkId,String key) {
        if (!validKey(artworkId,key)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Chave do PDF privado inválida");
        }
        requireConfiguration();
        try (S3Client client = createClient()) {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            try {
                client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "R2 ainda apresenta o documento após exclusão");
            } catch (S3Exception confirmedMissing) {
                if (confirmedMissing.statusCode()!=404) throw confirmedMissing;
            }
        } catch (S3Exception ex) {
            if (ex.statusCode()==404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "O PDF privado informado já não existe no R2",ex);
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Falha ao verificar ou excluir PDF privado no R2",ex);
        }
    }

    /** Compensating cleanup if the insertion fails after upload. */
    public void delete(long artworkId, String key) {
        if (!validKey(artworkId, key)) return;
        requireConfiguration();
        try (S3Client client = createClient()) {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (S3Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Falha ao limpar documento não registrado", ex);
        }
    }

    byte[] verifiedPdf(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Envie um PDF de contrato com até 8 MB");
        }
        if (!"application/pdf".equalsIgnoreCase(file.getContentType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "O contrato assinado deve estar em formato PDF");
        }
        try {
            byte[] bytes = file.getBytes();
            if (!validPdfBytes(bytes)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "O arquivo enviado não parece ser um PDF válido");
            }
            return bytes;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Não foi possível ler o PDF enviado", ex);
        }
    }

    private boolean validPdfBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 12 || bytes.length > MAX_BYTES) return false;
        if (bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D'
            || bytes[3] != 'F' || bytes[4] != '-') return false;
        String tail = new String(bytes, Math.max(0, bytes.length - 2048),
            Math.min(bytes.length, 2048), java.nio.charset.StandardCharsets.US_ASCII);
        return tail.contains("%%EOF");
    }

    private boolean validKey(long artworkId, String key) {
        return artworkId > 0 && key != null &&
            key.matches("commerce/contracts/" + artworkId +
                "/[0-9a-fA-F-]{36}\\.pdf");
    }

    private void requireConfiguration() {
        if (blank(endpoint) || blank(bucket) || blank(accessKey) || blank(secretKey)
            || bucket.equals(publicBucket)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Armazenamento privado de contratos não configurado");
        }
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private S3Client createClient() {
        return S3Client.builder()
            .endpointOverride(URI.create(endpoint.trim()))
            .region(Region.of("auto"))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey)))
            .forcePathStyle(true).build();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponível", ex);
        }
    }
}
