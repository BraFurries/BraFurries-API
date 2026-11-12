package com.Brafurries.API.storage;

import java.net.URI;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * R2 infrastructure for the owner-only console. Credentials and physical
 * bucket names never cross the API boundary.
 */
@Component
public class AdminStorageR2Client {
    private final String endpoint;
    private final String publicBucket,privateBucket;
    private final String publicAccessKey,publicSecretKey,privateAccessKey,privateSecretKey;

    public AdminStorageR2Client(
        @Value("${app.storage.r2.endpoint:}") String endpoint,
        @Value("${app.storage.r2.bucket:}") String publicBucket,
        @Value("${app.storage.r2.access-key:}") String publicAccessKey,
        @Value("${app.storage.r2.secret-key:}") String publicSecretKey,
        @Value("${app.storage.r2.private.bucket:}") String privateBucket,
        @Value("${app.storage.r2.private.access-key:}") String privateAccessKey,
        @Value("${app.storage.r2.private.secret-key:}") String privateSecretKey
    ) {
        this.endpoint=endpoint;
        this.publicBucket=publicBucket;
        this.publicAccessKey=publicAccessKey;
        this.publicSecretKey=publicSecretKey;
        this.privateBucket=privateBucket;
        this.privateAccessKey=privateAccessKey;
        this.privateSecretKey=privateSecretKey;
    }

    public record Page(List<S3Object> objects,List<String> folders,String nextCursor,boolean truncated) {}

    public Page list(AdminStorageService.Scope scope,String prefix,String cursor,int limit) {
        String bucket=bucket(scope);
        try (S3Client client=client(scope)) {
            // R2 uses a flat namespace: the S3 delimiter produces virtual
            // folders without changing or moving any existing object keys.
            var request=ListObjectsV2Request.builder().bucket(bucket).prefix(prefix)
                .delimiter("/").continuationToken(cursor).maxKeys(limit).build();
            var response=client.listObjectsV2(request);
            boolean truncated=Boolean.TRUE.equals(response.isTruncated());
            List<String> folders=response.commonPrefixes().stream()
                .map(p -> p.prefix()).toList();
            return new Page(response.contents(),folders,
                truncated?response.nextContinuationToken():null,truncated);
        } catch (S3Exception | SdkClientException ex) {
            throw failure("Não foi possível listar o R2",ex);
        }
    }

    public HeadObjectResponse head(AdminStorageService.Scope scope,String key) {
        String bucket=bucket(scope);
        try (S3Client client=client(scope)) {
            return client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        } catch(S3Exception ex) {
            if(ex.statusCode()==404) throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,"Objeto não encontrado no R2");
            throw failure("Não foi possível consultar o objeto no R2",ex);
        } catch(SdkClientException ex) {
            throw failure("Não foi possível consultar o objeto no R2",ex);
        }
    }

    /** Caller has verified owner, exact ETag, age, and absence of DB links. */
    public void deleteVerifiedOrphan(AdminStorageService.Scope scope,String key) {
        String bucket=bucket(scope);
        try (S3Client client=client(scope)) {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            try {
                client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "O R2 ainda apresenta o objeto após a exclusão");
            } catch(S3Exception ex) {
                if(ex.statusCode()!=404) throw ex;
            }
        } catch(S3Exception | SdkClientException ex) {
            throw failure("Não foi possível confirmar a exclusão no R2",ex);
        }
    }

    private String bucket(AdminStorageService.Scope scope) {
        if(scope==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Selecione um bucket válido");
        String selected=scope==AdminStorageService.Scope.PRIVATE?privateBucket:publicBucket;
        String key=scope==AdminStorageService.Scope.PRIVATE?privateAccessKey:publicAccessKey;
        String secret=scope==AdminStorageService.Scope.PRIVATE?privateSecretKey:publicSecretKey;
        if(blank(endpoint)||blank(selected)||blank(key)||blank(secret)||
            (scope==AdminStorageService.Scope.PRIVATE && selected.equals(publicBucket))) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Armazenamento não configurado para este bucket");
        }
        return selected;
    }

    private S3Client client(AdminStorageService.Scope scope) {
        bucket(scope);
        String key=scope==AdminStorageService.Scope.PRIVATE?privateAccessKey:publicAccessKey;
        String secret=scope==AdminStorageService.Scope.PRIVATE?privateSecretKey:publicSecretKey;
        return S3Client.builder().endpointOverride(URI.create(endpoint.trim()))
            .region(Region.of("auto"))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(key,secret)))
            .forcePathStyle(true).build();
    }

    private boolean blank(String value) { return value==null||value.isBlank(); }
    private ResponseStatusException failure(String message,Exception ex) {
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY,message,ex);
    }
}
