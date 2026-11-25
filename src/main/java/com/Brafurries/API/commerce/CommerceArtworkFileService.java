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
    private static final Set<String> PREVIEW_TYPES=Set.of("image/png","image/jpeg","image/webp");
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
                   created_at AS createdAt,archived_at AS archivedAt,
                   preview_generated_at AS previewGeneratedAt
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

    private static final String CLEAR_PREVIEW = "is_preview=0,preview_storage_key=NULL,preview_sha256=NULL,"
        +"preview_size_bytes=NULL,preview_width=NULL,preview_height=NULL,preview_generated_at=NULL";

    @Transactional
    public void setPreview(Authentication auth,long artworkId,long fileId,boolean selected) {
        int actor=authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE).user().getId();
        requireArtwork(artworkId,true);
        var rows=jdbc.queryForList("""
            SELECT status,extension,media_type AS mediaType,size_bytes AS sizeBytes,
                   storage_key AS storageKey,sha256,is_preview AS isPreview,preview_storage_key AS previewKey
              FROM commerce_artwork_files WHERE artwork_id=? AND id=? FOR UPDATE
            """,artworkId,fileId);
        if(rows.size()!=1) throw notFound();
        var file=rows.get(0);
        String currentKey=(String)file.get("previewKey");
        if(!selected) {
            if(jdbc.update("UPDATE commerce_artwork_files SET "+CLEAR_PREVIEW+
                " WHERE artwork_id=? AND id=? AND is_preview=1",artworkId,fileId)>0) {
                audit(actor,fileId,"PREVIEW_REMOVED");
                cleanupAfter(artworkId,fileId,currentKey,TransactionSynchronization.STATUS_COMMITTED);
            }
            return;
        }
        if(!"ACTIVE".equals(file.get("status"))
            || !Set.of("png","jpg","jpeg","webp").contains(file.get("extension"))
            || !PREVIEW_TYPES.contains(file.get("mediaType"))
            || ((Number)file.get("sizeBytes")).longValue()>CommerceArtworkFileStorage.MAX_BYTES) {
            throw invalid("Escolha uma imagem ativa PNG, JPG ou WebP de até 25 MiB");
        }
        // Repeated selection is idempotent; legacy selections without a derivative
        // may be selected again to generate their persistent thumbnail.
        if(currentKey!=null) return;
        var previous=jdbc.queryForList("""
            SELECT id,preview_storage_key AS previewKey FROM commerce_artwork_files
             WHERE artwork_id=? AND is_preview=1 AND id<>? FOR UPDATE
            """,artworkId,fileId);
        byte[] original=storage.read(artworkId,(String)file.get("storageKey"),(String)file.get("sha256"));
        var thumb=storage.uploadThumbnail(artworkId,fileId,original);
        boolean synchronizedTx=TransactionSynchronizationManager.isSynchronizationActive();
        try {
            cleanupAfter(artworkId,fileId,thumb.key(),TransactionSynchronization.STATUS_ROLLED_BACK);
            jdbc.update("UPDATE commerce_artwork_files SET "+CLEAR_PREVIEW+
                " WHERE artwork_id=? AND is_preview=1 AND id<>?",artworkId,fileId);
            jdbc.update("""
                UPDATE commerce_artwork_files SET is_preview=1,preview_storage_key=?,preview_sha256=?,
                    preview_size_bytes=?,preview_width=?,preview_height=?,preview_generated_at=CURRENT_TIMESTAMP(6)
                 WHERE artwork_id=? AND id=?
                """,thumb.key(),thumb.sha256(),thumb.sizeBytes(),thumb.width(),thumb.height(),artworkId,fileId);
            audit(actor,fileId,"PREVIEW_SELECTED");
            for(var old:previous) {
                cleanupAfter(artworkId,((Number)old.get("id")).longValue(),(String)old.get("previewKey"),
                    TransactionSynchronization.STATUS_COMMITTED);
            }
        } catch(RuntimeException ex) {
            if(!synchronizedTx) safeDeleteThumbnail(artworkId,fileId,thumb.key());
            throw ex;
        }
    }

    private void cleanupAfter(long artworkId,long fileId,String key,int expectedStatus) {
        if(key==null) return;
        if(TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    if(status==expectedStatus) safeDeleteThumbnail(artworkId,fileId,key);
                }
            });
        } else if(expectedStatus==TransactionSynchronization.STATUS_COMMITTED) {
            safeDeleteThumbnail(artworkId,fileId,key);
        }
    }

    private void safeDeleteThumbnail(long artworkId,long fileId,String key) {
        try { storage.deleteThumbnail(artworkId,fileId,key); }
        catch(RuntimeException ex) {
            // Keep an orphan for the existing private storage reconciliation.
            LOG.warn("Falha ao limpar miniatura da arte {}: {}",artworkId,ex.getClass().getSimpleName());
        }
    }

    public record Preview(String mediaType,byte[] bytes) {}

    @Transactional(readOnly=true)
    public Preview preview(Authentication auth,long artworkId,long fileId) {
        authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE);
        var rows=jdbc.queryForList("""
            SELECT media_type AS mediaType,storage_key AS storageKey,sha256,
                   preview_storage_key AS previewKey,preview_sha256 AS previewHash
              FROM commerce_artwork_files
             WHERE artwork_id=? AND id=? AND status='ACTIVE' AND is_preview=1
            """,artworkId,fileId);
        if(rows.size()!=1) throw notFound();
        var file=rows.get(0);
        if(!PREVIEW_TYPES.contains(file.get("mediaType"))) throw notFound();
        String key=(String)file.get("previewKey");
        if(key!=null) return new Preview("image/webp",
            storage.readThumbnail(artworkId,fileId,key,(String)file.get("previewHash")));
        // Preserve old selections without exposing originals; explicit regeneration
        // persists a derivative. GET remains read-only.
        byte[] original=storage.read(artworkId,(String)file.get("storageKey"),(String)file.get("sha256"));
        return new Preview("image/webp",storage.thumbnail(original).bytes());
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
            SELECT status,preview_storage_key AS previewKey FROM commerce_artwork_files
             WHERE artwork_id=? AND id=? FOR UPDATE
            """,artworkId,fileId);
        if(rows.size()!=1) throw notFound();
        if(!"ACTIVE".equals(rows.get(0).get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Arquivo já arquivado");
        }
        jdbc.update("UPDATE commerce_artwork_files SET status='ARCHIVED',"+CLEAR_PREVIEW+
            ",archived_by_user_id=?,archived_at=CURRENT_TIMESTAMP(6)"+
            " WHERE artwork_id=? AND id=? AND status='ACTIVE'",actor,artworkId,fileId);
        audit(actor,fileId,"ARCHIVED");
        cleanupAfter(artworkId,fileId,(String)rows.get(0).get("previewKey"),
            TransactionSynchronization.STATUS_COMMITTED);
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
