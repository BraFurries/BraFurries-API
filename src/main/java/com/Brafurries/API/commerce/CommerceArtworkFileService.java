package com.Brafurries.API.commerce;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Private BraFurries commerce; only ARTWORKS_MANAGE can see original design
 * files. Knowing an artwork/file ID never grants a download capability.
 */
@Service
public class CommerceArtworkFileService {
    private static final Logger LOG=LoggerFactory.getLogger(CommerceArtworkFileService.class);
    private static final Set<String> KINDS=Set.of(
        "SOURCE","PRINT_READY","EXPORT","REFERENCE");
    private final PlatformAuthorizationService authorization;
    private final CommerceArtworkFileStorage storage;
    private final CommerceFinanceSupport support;
    private final JdbcTemplate jdbc;

    public CommerceArtworkFileService(PlatformAuthorizationService authorization,
                                      CommerceArtworkFileStorage storage,
                                      CommerceFinanceSupport support,JdbcTemplate jdbc) {
        this.authorization=authorization;
        this.storage=storage;
        this.support=support;
        this.jdbc=jdbc;
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(Authentication auth,long artworkId) {
        authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE);
        requireArtwork(artworkId,false);
        // Never return storage keys, credentials or object URLs.
        return jdbc.queryForList("""
            SELECT id,artwork_id AS artworkId,kind,
                   original_name AS originalName,extension,
                   media_type AS mediaType,description,sha256,
                   size_bytes AS sizeBytes,status,
                   uploaded_by_user_id AS uploadedByUserId,
                   created_at AS createdAt,archived_at AS archivedAt
              FROM commerce_artwork_files
             WHERE artwork_id=? ORDER BY id DESC LIMIT 500
            """,artworkId);
    }

    @Transactional
    public CommerceCatalogDtos.Created upload(Authentication auth,long artworkId,
                                               String kind,String description,
                                               MultipartFile file) {
        int actor=authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE)
            .user().getId();
        // Foreign key enforces existence at insert; avoid holding a row lock during S3 upload.
        requireArtwork(artworkId,false);
        if(!KINDS.contains(kind)) throw invalid("Tipo de arquivo inválido");
        String normalized=description==null||description.isBlank()?null:description.trim();
        if(normalized!=null&&normalized.length()>500) {
            throw invalid("Descrição ultrapassa 500 caracteres");
        }
        var stored=storage.upload(artworkId,file);
        boolean rollbackHookRegistered=false;
        try {
            // R2 is not transactional: an INSERT may succeed but the outer
            // transaction can still fail during audit or COMMIT. Register
            // compensation on transaction completion, not only SQL exceptions.
            if(TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override public void afterCompletion(int completionStatus) {
                            if(completionStatus==STATUS_ROLLED_BACK) {
                                try {
                                    storage.deleteUnregistered(artworkId,stored.key());
                                } catch(RuntimeException cleanupFailure) {
                                    // Never leak private object keys or credentials in logs.
                                    LOG.error("Falha ao compensar arte {} no R2 após rollback: {}",
                                        artworkId,cleanupFailure.getClass().getSimpleName());
                                }
                            } else if(completionStatus==STATUS_UNKNOWN) {
                                // If DB commit might have succeeded, deleting would
                                // leave a valid metadata record pointing to no file.
                                // Preserve the object for explicit reconciliation.
                                LOG.warn("Commit inconclusivo ao registrar arquivo da arte {}; "
                                    +"objeto R2 retido para reconciliação",artworkId);
                            }
                        }
                    });
                rollbackHookRegistered=true;
            }
            long id=support.insert("""
                INSERT INTO commerce_artwork_files
                    (artwork_id,kind,original_name,extension,media_type,description,
                     storage_key,sha256,size_bytes,uploaded_by_user_id)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,artworkId,kind,stored.name(),stored.extension(),stored.mediaType(),
                normalized,stored.key(),stored.sha256(),stored.sizeBytes(),actor);
            audit(actor,id,"UPLOADED");
            return new CommerceCatalogDtos.Created(id);
        } catch(RuntimeException ex) {
            // Fallback for non-transactional direct invocations, e.g. focused
            // unit tests. Under Spring transaction, afterCompletion owns cleanup.
            if(!rollbackHookRegistered) {
                try {
                    storage.deleteUnregistered(artworkId,stored.key());
                } catch(RuntimeException cleanupFailure) {
                    ex.addSuppressed(cleanupFailure);
                }
            }
            throw ex;
        }
    }

    public record Downloaded(String name,byte[] bytes) {}

    @Transactional(readOnly=true)
    public Downloaded download(Authentication auth,long artworkId,long fileId) {
        authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE);
        if(artworkId<1||fileId<1) throw notFound();
        var rows=jdbc.queryForList("""
            SELECT original_name AS originalName,storage_key AS storageKey,sha256
              FROM commerce_artwork_files
             WHERE artwork_id=? AND id=?
            """,artworkId,fileId);
        if(rows.size()!=1) throw notFound();
        var file=rows.get(0);
        return new Downloaded((String)file.get("originalName"),
            storage.read(artworkId,(String)file.get("storageKey"),(String)file.get("sha256")));
    }

    @Transactional
    public void archive(Authentication auth,long artworkId,long fileId) {
        int actor=authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE)
            .user().getId();
        if(artworkId<1||fileId<1) throw notFound();
        var rows=jdbc.queryForList("""
            SELECT status FROM commerce_artwork_files
             WHERE artwork_id=? AND id=? FOR UPDATE
            """,artworkId,fileId);
        if(rows.size()!=1) throw notFound();
        if(!"ACTIVE".equals(rows.get(0).get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Arquivo já arquivado");
        }
        jdbc.update("""
            UPDATE commerce_artwork_files
               SET status='ARCHIVED',archived_by_user_id=?,
                   archived_at=CURRENT_TIMESTAMP(6)
             WHERE artwork_id=? AND id=? AND status='ACTIVE'
            """,actor,artworkId,fileId);
        audit(actor,fileId,"ARCHIVED");
    }

    private void requireArtwork(long id,boolean lock) {
        if(id<1) throw notFound();
        String sql="SELECT id FROM commerce_artworks WHERE id=?"+(lock?" FOR UPDATE":"");
        if(jdbc.queryForList(sql,Long.class,id).size()!=1) throw notFound();
    }
    private void audit(int actor,long id,String action) {
        jdbc.update("""
            INSERT INTO commerce_catalog_audit (actor_user_id,entity_type,entity_id,action)
            VALUES (?,'ARTWORK_FILE',?,?)
            """,actor,id,action);
    }
    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Arquivo ou arte não encontrado");
    }
}
