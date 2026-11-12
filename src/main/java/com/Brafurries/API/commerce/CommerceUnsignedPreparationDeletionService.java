package com.Brafurries.API.commerce;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Owner-only permanent removal of UNSIGNED generated drafts.
 * DB deletion and audit commit before R2 cleanup: a failed R2 operation may
 * leave an orphan, but can never leave a live DB reference to a missing PDF.
 * Both catalog and storage console must use this exact business operation.
 */
@Service
public class CommerceUnsignedPreparationDeletionService {
    private final PlatformAuthorizationService authorization;
    private final JdbcTemplate jdbc;
    private final CommercePrivateDocumentStorage documents;
    private final TransactionTemplate transactions;

    public CommerceUnsignedPreparationDeletionService(
        PlatformAuthorizationService authorization, JdbcTemplate jdbc,
        CommercePrivateDocumentStorage documents, PlatformTransactionManager transactionManager
    ) {
        this.authorization=authorization;
        this.jdbc=jdbc;
        this.documents=documents;
        this.transactions=new TransactionTemplate(transactionManager);
    }

    public void delete(Authentication auth,long artworkId,long preparationId,String expectedKey) {
        User actor=authorization.requireOwner(auth);
        if(artworkId<=0||preparationId<=0) throw invalid("Identificador de minuta inválido");
        String key=transactions.execute(transaction -> {
            // Match the existing prepare/sign lock order to serialize with upload.
            if(jdbc.queryForList("SELECT id FROM commerce_artworks WHERE id=? FOR UPDATE",artworkId)
                .isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Arte não encontrada");
            }
            List<Map<String,Object>> matches=jdbc.queryForList("""
                SELECT status,signed_contract_id,generated_storage_key
                  FROM commerce_contract_preparations
                 WHERE id=? AND artwork_id=? FOR UPDATE
                """,preparationId,artworkId);
            if(matches.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Minuta não encontrada");
            }
            Map<String,Object> row=matches.get(0);
            if(!("PENDING".equals(row.get("status")) ||
                 "CANCELLED".equals(row.get("status"))) ||
                row.get("signed_contract_id")!=null) {
                throw conflict("Documentos já assinados não podem ser excluídos");
            }
            String storageKey=(String)row.get("generated_storage_key");
            if(storageKey==null || !storageKey.matches(
                "commerce/contracts/"+artworkId+
                "/[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}\\.pdf")) {
                throw conflict("Chave privada da minuta inválida; exclusão bloqueada");
            }
            if(expectedKey!=null&&!expectedKey.equals(storageKey)) {
                throw conflict("O vínculo do arquivo foi alterado; inspecione novamente");
            }
            // Never delete an object also referenced by a signed contract or art file.
            if(!jdbc.queryForList("""
                SELECT id FROM commerce_artwork_contracts
                 WHERE signed_storage_key=? LIMIT 1
                """,storageKey).isEmpty()
                || !jdbc.queryForList("""
                SELECT id FROM commerce_artwork_files
                 WHERE storage_key=? LIMIT 1
                """,storageKey).isEmpty()) {
                throw conflict("O documento possui outro vínculo e não pode ser removido");
            }
            int removed=jdbc.update("""
                DELETE FROM commerce_contract_preparations
                 WHERE id=? AND artwork_id=? AND status IN ('PENDING','CANCELLED')
                   AND signed_contract_id IS NULL
                """,preparationId,artworkId);
            if(removed!=1) throw conflict("Minuta alterada; atualize a página");
            // Audit does not contain personal legal details or private storage keys.
            jdbc.update("""
                INSERT INTO commerce_catalog_audit(actor_user_id,entity_type,entity_id,action)
                VALUES (?, 'CONTRACT_PREPARATION', ?, 'DELETED_UNSIGNED')
                """,actor.getId(),preparationId);
            return storageKey;
        });
        if(key==null) throw new IllegalStateException("Exclusão da minuta não confirmada");
        try {
            documents.deleteConfirmedGenerated(artworkId,key);
        } catch(RuntimeException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "Minuta removida do cadastro, mas o PDF não pôde ser excluído do R2. "
                  +"Localize o arquivo órfão em Administração > Armazenamento para limpeza.",ex);
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
}
