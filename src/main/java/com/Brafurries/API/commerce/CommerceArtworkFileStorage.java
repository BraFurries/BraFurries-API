package com.Brafurries.API.commerce;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
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
 * Private artwork originals and exports; never serves public URLs. Download
 * endpoints must enforce authorization, attachment and nosniff.
 */
@Service
public class CommerceArtworkFileStorage {
    static final long MAX_BYTES = 25L * 1024L * 1024L;
    private static final Map<String,String> MEDIA_TYPES = Map.of(
        "png","image/png", "jpg","image/jpeg", "jpeg","image/jpeg",
        "webp","image/webp", "pdf","application/pdf", "svg","image/svg+xml",
        "psd","application/octet-stream", "ai","application/octet-stream",
        "eps","application/postscript", "zip","application/zip"
    );
    private final String endpoint,bucket,publicBucket,accessKey,secretKey;

    public CommerceArtworkFileStorage(
        @Value("${app.storage.r2.endpoint:}") String endpoint,
        @Value("${app.storage.r2.private.bucket:}") String bucket,
        @Value("${app.storage.r2.bucket:}") String publicBucket,
        @Value("${app.storage.r2.private.access-key:}") String accessKey,
        @Value("${app.storage.r2.private.secret-key:}") String secretKey
    ) {
        this.endpoint=endpoint;
        this.bucket=bucket;
        this.publicBucket=publicBucket;
        this.accessKey=accessKey;
        this.secretKey=secretKey;
    }

    public record CheckedFile(String name,String extension,String mediaType,
                              byte[] bytes,String sha256) {}
    public record StoredFile(String key,String name,String extension,
                             String mediaType,String sha256,long sizeBytes) {}

    CheckedFile inspect(MultipartFile file) {
        if(file==null||file.isEmpty()||file.getSize()>MAX_BYTES) {
            throw invalid("Envie arquivo de arte com até 25 MiB");
        }
        String name=file.getOriginalFilename();
        if(name==null||name.isBlank()||name.length()>255||
           name.contains("/")||name.contains("\\")||
           name.chars().anyMatch(c->Character.isISOControl(c))) {
            throw invalid("Nome do arquivo de arte inválido");
        }
        int lastDot=name.lastIndexOf('.');
        if(lastDot<1||lastDot==name.length()-1) throw invalid("Formato não suportado");
        String ext=name.substring(lastDot+1).toLowerCase(Locale.ROOT);
        if(!MEDIA_TYPES.containsKey(ext)) throw invalid("Formato não suportado");
        try {
            byte[] bytes=file.getBytes();
            if(bytes.length==0||bytes.length>MAX_BYTES||!matchesFormat(ext,bytes)) {
                throw invalid("Extensão e conteúdo do arquivo não correspondem");
            }
            return new CheckedFile(name,ext,MEDIA_TYPES.get(ext),bytes,hash(bytes));
        } catch(IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Não foi possível ler arquivo de arte",ex);
        }
    }

    public StoredFile upload(long artworkId,MultipartFile file) {
        if(artworkId<1) throw invalid("Arte inválida");
        CheckedFile checked=inspect(file);
        requirePrivateConfiguration();
        String key="commerce/artworks/"+artworkId+"/"+UUID.randomUUID()+"."+checked.extension();
        try(S3Client client=createClient()) {
            client.putObject(PutObjectRequest.builder()
                .bucket(bucket).key(key).contentType("application/octet-stream")
                .contentLength((long)checked.bytes().length).build(),
                RequestBody.fromBytes(checked.bytes()));
        } catch(S3Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Não foi possível armazenar arte privada",ex);
        }
        return new StoredFile(key,checked.name(),checked.extension(),
            checked.mediaType(),checked.sha256(),checked.bytes().length);
    }

    public byte[] read(long artworkId,String key,String expectedHash) {
        if(!validKey(artworkId,key)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        requirePrivateConfiguration();
        try(S3Client client=createClient()) {
            var head=client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            if(head.contentLength()==null||head.contentLength()<1||head.contentLength()>MAX_BYTES) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Tamanho do arquivo privado inválido");
            }
            byte[] bytes=client.getObjectAsBytes(
                GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
            if(bytes.length!=head.contentLength()||!hash(bytes).equalsIgnoreCase(expectedHash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Integridade do arquivo privado não confere");
            }
            return bytes;
        } catch(S3Exception ex) {
            if(ex.statusCode()==404) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Arquivo privado não encontrado",ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Falha ao recuperar arquivo privado",ex);
        }
    }

    /** Compensation for an upload whose DB/audit insert fails. */
    public void deleteUnregistered(long artworkId,String key) {
        if(!validKey(artworkId,key)) return;
        requirePrivateConfiguration();
        try(S3Client client=createClient()) {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch(S3Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Falha ao limpar objeto não registrado",ex);
        }
    }

    private boolean matchesFormat(String ext,byte[] b) {
        return switch(ext) {
            case "png" -> starts(b,new byte[]{(byte)137,80,78,71,13,10,26,10});
            case "jpg","jpeg" -> starts(b,new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff});
            case "webp" -> b.length>12&&ascii(b,0,"RIFF")&&ascii(b,8,"WEBP");
            case "pdf" -> ascii(b,0,"%PDF-")&&tailContains(b,"%%EOF");
            case "psd" -> ascii(b,0,"8BPS")&&b.length>26&&b[4]==0&&b[5]==1;
            case "zip" -> starts(b,new byte[]{0x50,0x4b,0x03,0x04});
            case "eps" -> ascii(b,0,"%!PS-Adobe-")||
                starts(b,new byte[]{(byte)0xc5,(byte)0xd0,(byte)0xd3,(byte)0xc6});
            case "ai" -> (ascii(b,0,"%PDF-")&&tailContains(b,"%%EOF"))||
                ascii(b,0,"%!PS-Adobe-");
            case "svg" -> isSvg(b);
            default -> false;
        };
    }
    private boolean isSvg(byte[] b) {
        if(b.length<5||b.length>MAX_BYTES) return false;
        String start=new String(b,0,Math.min(4096,b.length),StandardCharsets.UTF_8)
            .stripLeading();
        if(start.startsWith("<?xml")) {
            int end=start.indexOf("?>");
            if(end>=0) start=start.substring(end+2).stripLeading();
        }
        return start.startsWith("<svg")||(start.startsWith("<!--")&&start.contains("<svg"));
    }
    private boolean starts(byte[] bytes,byte[] sig) {
        if(bytes.length<sig.length) return false;
        for(int i=0;i<sig.length;i++) if(bytes[i]!=sig[i]) return false;
        return true;
    }
    private boolean ascii(byte[] bytes,int offset,String expected) {
        if(bytes.length<offset+expected.length()) return false;
        for(int i=0;i<expected.length();i++)
            if(bytes[offset+i]!=(byte)expected.charAt(i)) return false;
        return true;
    }
    private boolean tailContains(byte[] bytes,String expected) {
        return new String(bytes,Math.max(0,bytes.length-2048),Math.min(2048,bytes.length),
            StandardCharsets.US_ASCII).contains(expected);
    }
    boolean validKey(long artworkId,String key) {
        return artworkId>0&&key!=null&&key.matches("commerce/artworks/"+artworkId+
            "/[0-9a-fA-F-]{36}\\.(png|jpg|jpeg|webp|pdf|svg|psd|ai|eps|zip)");
    }
    private void requirePrivateConfiguration() {
        if(blank(endpoint)||blank(bucket)||blank(accessKey)||blank(secretKey)||
           bucket.equals(publicBucket)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Armazenamento privado de artes não configurado");
        }
    }
    private boolean blank(String v){return v==null||v.isBlank();}
    private S3Client createClient() {
        return S3Client.builder().endpointOverride(URI.create(endpoint.trim()))
            .region(Region.of("auto"))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey,secretKey)))
            .forcePathStyle(true).build();
    }
    private String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch(NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponível",ex);
        }
    }
    private ResponseStatusException invalid(String text) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,text);
    }
}
