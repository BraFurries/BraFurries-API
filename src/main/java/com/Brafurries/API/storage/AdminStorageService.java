package com.Brafurries.API.storage;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Platform owner-only R2 inventory; no Community-scoped authorization is
 * inherited. No arbitrary key deletion or automatic orphan sweeps.
 *
 * References are authoritative only for the two supported private Commerce
 * namespaces. All other objects are protected, even when no reference is
 * known in the current schema (Discord/public URLs/legacy references).
 */
@Service
public class AdminStorageService {
    public enum Scope { PRIVATE, PUBLIC }
    public enum ReferenceStatus { LINKED, ORPHAN, RECENT, UNVERIFIED }
    public enum Category { CONTRACT_DOCUMENT, ARTWORK_FILE, OTHER_PRIVATE, PUBLIC_MEDIA }

    public record ReferenceView(String source,long recordId,long artworkId,String status) {}
    public record ObjectView(
        String key,long sizeBytes,Instant lastModified,String contentType,
        String etag,Category category,ReferenceStatus referenceStatus,
        String referenceLabel,boolean deletable,List<ReferenceView> references
    ) {}
    public record PageResult(
        Scope bucket,String prefix,List<String> folders,List<ObjectView> objects,
        String nextCursor,boolean truncated,long pageBytes
    ) {}
    public record DeleteRequest(Scope bucket,String key,String etag) {}

    private static final Pattern CONTRACT=Pattern.compile(
        "^commerce/contracts/[1-9][0-9]*/[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}\\.pdf$");
    private static final Pattern ARTWORK=Pattern.compile(
        "^commerce/artworks/[1-9][0-9]*/[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}\\.(?:png|jpg|jpeg|webp|pdf|svg|psd|ai|eps|zip)$");
    // Uploads may put objects before a DB transaction commits. Do not classify
    // very recent unreferenced uploads as safe to delete.
    private static final long MIN_ORPHAN_AGE_MINUTES=10L;

    private final PlatformAuthorizationService authorization;
    private final JdbcTemplate jdbc;
    private final AdminStorageR2Client r2;

    public AdminStorageService(PlatformAuthorizationService authorization,
                               JdbcTemplate jdbc,AdminStorageR2Client r2) {
        this.authorization=authorization;
        this.jdbc=jdbc;
        this.r2=r2;
    }

    @Transactional(readOnly=true)
    public PageResult list(Authentication auth,Scope scope,String prefix,String cursor,Integer requestedLimit) {
        authorization.requireOwner(auth);
        requireScope(scope);
        String normalizedPrefix=validatePrefix(prefix);
        if(cursor!=null && (cursor.isBlank()||cursor.length()>2048)) {
            throw invalid("Cursor de paginação inválido");
        }
        int limit=requestedLimit==null?50:requestedLimit;
        if(limit<1||limit>100) throw invalid("Limite deve estar entre 1 e 100");
        var page=r2.list(scope,normalizedPrefix,cursor,limit);
        var items=page.objects()==null?List.<S3Object>of():page.objects();
        // S3 zero-byte "folder objects" are only markers; use commonPrefixes
        // for navigation, never render them as duplicate files.
        Set<String> folders=new LinkedHashSet<>(
            page.folders()==null?List.of():page.folders());
        List<S3Object> realFiles=new ArrayList<>();
        for(S3Object item:items) {
            if(item.key().endsWith("/") && Long.valueOf(0L).equals(item.size())) {
                if(!item.key().equals(normalizedPrefix)) folders.add(item.key());
            } else realFiles.add(item);
        }
        var referenced=referencesFor(scope,realFiles.stream().map(S3Object::key).toList());
        List<ObjectView> views=new ArrayList<>();
        long bytes=0;
        for(S3Object item:realFiles) {
            views.add(view(scope,item.key(),item.size()==null?0:item.size(),
                item.lastModified(),null,item.eTag(),referenced));
            bytes+=item.size()==null?0:item.size();
        }
        return new PageResult(scope,normalizedPrefix,folders.stream().sorted().toList(),
            views,page.nextCursor(),page.truncated(),bytes);
    }

    @Transactional(readOnly=true)
    public ObjectView inspect(Authentication auth,Scope scope,String key) {
        authorization.requireOwner(auth);
        requireScope(scope);
        validKey(key);
        HeadObjectResponse head=r2.head(scope,key);
        Set<String> references=referencesFor(scope,List.of(key));
        ObjectView object=view(scope,key,head.contentLength()==null?0:head.contentLength(),
            head.lastModified(),head.contentType(),head.eTag(),references);
        if(object.referenceStatus()!=ReferenceStatus.LINKED) return object;
        return new ObjectView(object.key(),object.sizeBytes(),object.lastModified(),
            object.contentType(),object.etag(),object.category(),
            object.referenceStatus(),object.referenceLabel(),object.deletable(),
            referenceDetails(key));
    }

    // Deliberately not @Transactional: in MariaDB REPEATABLE_READ a long-lived
    // read snapshot opened before R2 HEAD could miss newly committed links.
    // Each reference query must use a fresh database view before DELETE.
    public void deleteOrphan(Authentication auth,DeleteRequest input) {
        authorization.requireOwner(auth);
        if(input==null||input.bucket()!=Scope.PRIVATE) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Exclusão disponível somente para órfãos privados verificados");
        }
        validKey(input.key());
        if(input.etag()==null||input.etag().isBlank()||input.etag().length()>180) {
            throw invalid("Confirmação do objeto é obrigatória; consulte-o novamente");
        }
        // Check HEAD and exact ETag again at deletion time. Nothing in a public
        // bucket or unknown legacy prefix can be deleted from this console.
        HeadObjectResponse head=r2.head(Scope.PRIVATE,input.key());
        if(head.eTag()==null||!head.eTag().equals(input.etag())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Objeto modificado; consulte novamente antes de excluir");
        }
        var linked=referencesFor(Scope.PRIVATE,List.of(input.key()));
        ObjectView object=view(Scope.PRIVATE,input.key(),
            head.contentLength()==null?0:head.contentLength(),head.lastModified(),
            head.contentType(),head.eTag(),linked);
        if(!object.deletable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Objeto ainda vinculado, recente ou sem cobertura segura de vínculos");
        }
        r2.deleteVerifiedOrphan(Scope.PRIVATE,input.key());
    }

    private ObjectView view(Scope scope,String key,long size,Instant changed,
                            String contentType,String etag,Set<String> linked) {
        Category category=category(scope,key);
        boolean covered=category==Category.CONTRACT_DOCUMENT||category==Category.ARTWORK_FILE;
        ReferenceStatus status;
        if(!covered) status=ReferenceStatus.UNVERIFIED;
        else if(linked.contains(key)) status=ReferenceStatus.LINKED;
        else if(changed==null||!changed.isBefore(
            Instant.now().minus(MIN_ORPHAN_AGE_MINUTES,ChronoUnit.MINUTES))) {
            status=ReferenceStatus.RECENT;
        } else status=ReferenceStatus.ORPHAN;
        String label=switch(category) {
            case CONTRACT_DOCUMENT -> "Contrato assinado ou minuta";
            case ARTWORK_FILE -> "Arquivo privado de arte";
            case OTHER_PRIVATE -> "Origem privada ainda não mapeada";
            case PUBLIC_MEDIA -> "Possíveis referências externas ou legadas";
        };
        return new ObjectView(key,size,changed,contentType,etag,category,status,
            label,status==ReferenceStatus.ORPHAN&&etag!=null&&!etag.isBlank(),List.of());
    }

    private Set<String> referencesFor(Scope scope,List<String> keys) {
        if(scope!=Scope.PRIVATE) return Set.of();
        List<String> supported=keys.stream().filter(key ->
            category(scope,key)==Category.CONTRACT_DOCUMENT||
            category(scope,key)==Category.ARTWORK_FILE).distinct().toList();
        if(supported.isEmpty()) return Set.of();
        String placeholders=String.join(",",java.util.Collections.nCopies(supported.size(),"?"));
        Object[] args=supported.toArray();
        Set<String> linked=new HashSet<>();
        for(String columnAndTable:List.of(
            "signed_storage_key FROM commerce_artwork_contracts",
            "generated_storage_key FROM commerce_contract_preparations",
            "storage_key FROM commerce_artwork_files")) {
            String column=columnAndTable.substring(0,columnAndTable.indexOf(" FROM "));
            linked.addAll(jdbc.queryForList(
                "SELECT "+columnAndTable+" WHERE "+column+" IN ("+placeholders+")",
                String.class,args));
        }
        return linked;
    }

    /** Explain exactly which rows block a destructive operation. These are
     * platform-global commerce identifiers only; no user/artist PII returned.
     */
    private List<ReferenceView> referenceDetails(String key) {
        List<ReferenceView> found=new ArrayList<>();
        for(String[] source:new String[][] {
            {"CONTRACT", "commerce_artwork_contracts", "signed_storage_key"},
            {"PREPARATION", "commerce_contract_preparations", "generated_storage_key"},
            {"ARTWORK_FILE", "commerce_artwork_files", "storage_key"}
        }) {
            // All SQL identifiers above are constants from this class, never
            // supplied by a browser or a dynamic request parameter.
            List<Map<String,Object>> rows=jdbc.queryForList(
                "SELECT id,artwork_id,status FROM "+source[1]+
                " WHERE "+source[2]+"=? ORDER BY id",key);
            for(Map<String,Object> row:rows) {
                found.add(new ReferenceView(source[0],
                    ((Number)row.get("id")).longValue(),
                    ((Number)row.get("artwork_id")).longValue(),
                    String.valueOf(row.get("status"))));
            }
        }
        return found;
    }

    private Category category(Scope scope,String key) {
        if(scope==Scope.PUBLIC) return Category.PUBLIC_MEDIA;
        if(CONTRACT.matcher(key).matches()) return Category.CONTRACT_DOCUMENT;
        if(ARTWORK.matcher(key).matches()) return Category.ARTWORK_FILE;
        return Category.OTHER_PRIVATE;
    }

    private void requireScope(Scope scope) {
        if(scope==null) throw invalid("Bucket inválido");
    }
    private String validatePrefix(String prefix) {
        String value=prefix==null?"":prefix.trim();
        if(value.length()>256||value.contains("..")||value.startsWith("/")||
            value.contains("\\")||value.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Prefixo inválido");
        }
        return value;
    }
    private void validKey(String key) {
        if(key==null||key.isBlank()||key.length()>512||key.startsWith("/")||
            key.contains("..")||key.contains("\\")||
            key.chars().anyMatch(Character::isISOControl)) {
            throw invalid("Chave do objeto inválida");
        }
    }
    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
}
