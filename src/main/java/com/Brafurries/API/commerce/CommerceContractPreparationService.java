package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceContractPreparationDtos.*;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Contract preparation is not a signature and never activates an artwork.
 *
 * Flow: freeze template + legal parties + financial terms -> generate/store
 * immutable printable PDF -> human signatures outside the platform -> staff
 * manually confirms that the signed PDF matches -> save signed document and
 * activate the same financial snapshots.
 *
 * The prepared PDF is private, and the API never claims to cryptographically
 * verify that a paper signature is authentic.
 */
@Service
public class CommerceContractPreparationService {
    private static final ZoneId BRAZIL = ZoneId.of("America/Sao_Paulo");
    private static final BigDecimal MAX_MONEY = new BigDecimal("9999999999999999.99");
    private final PlatformAuthorizationService authorization;
    private final CommercePrivateDocumentStorage documents;
    private final CommerceContractPdfService pdf;
    private final JdbcTemplate jdbc;

    public CommerceContractPreparationService(
        PlatformAuthorizationService authorization,
        CommercePrivateDocumentStorage documents,
        CommerceContractPdfService pdf,
        JdbcTemplate jdbc
    ) {
        this.authorization = authorization;
        this.documents = documents;
        this.pdf = pdf;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> list(Authentication auth, long artworkId) {
        authorization.require(auth, PlatformPermission.CONTRACTS_MANAGE);
        requireArtwork(artworkId, false);
        // Neither legal identifiers nor private R2 keys are returned in lists.
        return jdbc.queryForList("""
            SELECT id,artwork_id AS artworkId,template_id AS templateId,
                   artist_user_id AS artistUserId,template_name_snapshot AS templateName,
                   status,signed_contract_id AS signedContractId,
                   effective_from AS effectiveFrom,effective_until AS effectiveUntil,
                   created_at AS createdAt,completed_at AS completedAt
              FROM commerce_contract_preparations
             WHERE artwork_id=? ORDER BY id DESC LIMIT 150
            """, artworkId);
    }

    @Transactional
    public Created prepare(Authentication auth, long artworkId, PrepareContract input) {
        User actor = authorization.require(auth, PlatformPermission.CONTRACTS_MANAGE).user();
        if (input == null || input.contractor() == null || input.artist() == null
            || input.effectiveFrom() == null || input.templateId() < 1) {
            throw invalid("Informe o modelo, vigência e qualificação completa das partes");
        }
        var art = requireArtwork(artworkId, true);
        if ("ARCHIVED".equals(art.get("status"))) {
            throw conflict("A arte está arquivada");
        }
        requireNoActiveAgreement(artworkId);
        if (!jdbc.queryForList("""
            SELECT id FROM commerce_contract_preparations
             WHERE artwork_id=? AND status='PENDING' LIMIT 1
            """, artworkId).isEmpty()) {
            throw conflict("Já existe um contrato aguardando assinatura. Cancele-o antes de gerar outro.");
        }
        if (input.effectiveUntil() != null &&
            input.effectiveUntil().isBefore(input.effectiveFrom())) {
            throw invalid("O término da vigência não pode anteceder o início");
        }
        BigDecimal oneTime = money(input.oneTimeAmount());
        List<Map<String,Object>> matches = jdbc.queryForList("""
            SELECT name,full_text,modality,royalty_method,
                   royalty_percent,royalty_fixed_amount
              FROM commerce_contract_templates WHERE id=? AND active=1
            """, input.templateId());
        if (matches.isEmpty()) throw conflict("Modelo indisponível ou desativado");
        Map<String,Object> template = matches.get(0);
        if (!(template.get("full_text") instanceof String fullText) ||
            fullText.isBlank() || fullText.length() > 60000) {
            throw conflict("Preencha os termos e concessões do modelo antes de gerar um contrato");
        }
        ContractParty contractor = normalize(input.contractor());
        ContractParty artist = normalize(input.artist());
        int artistId = ((Number) art.get("artist_user_id")).intValue();
        String name = (String) template.get("name");
        String modality = (String) template.get("modality");
        String method = (String) template.get("royalty_method");
        BigDecimal percent = (BigDecimal) template.get("royalty_percent");
        BigDecimal fixed = (BigDecimal) template.get("royalty_fixed_amount");

        byte[] generated = pdf.render(new CommerceContractPdfService.ContractDocument(
            name, (String) art.get("title"), fullText,
            contractor.legalName(), contractor.document(), contractor.address(),
            contractor.representative(), artist.legalName(), artist.document(),
            artist.address(), artist.representative(), modality, method,
            percent, fixed, oneTime, input.effectiveFrom(), input.effectiveUntil(),
            LocalDate.now(BRAZIL)));
        var stored = documents.uploadGenerated(artworkId, generated);
        try {
            long id = insert("""
                INSERT INTO commerce_contract_preparations
        (artwork_id,template_id,artist_user_id,template_name_snapshot,full_text_snapshot,modality_snapshot,royalty_method_snapshot,royalty_percent_snapshot,royalty_fixed_snapshot,one_time_amount,effective_from,effective_until,
         contractor_name,contractor_document,contractor_address,contractor_representative,artist_name,artist_document,artist_address,artist_representative,generated_storage_key,generated_sha256,generated_bytes,created_by_user_id)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, artworkId, input.templateId(), artistId, name, fullText,
                modality, method, percent, fixed, oneTime,
                input.effectiveFrom(), input.effectiveUntil(),
                contractor.legalName(), contractor.document(), contractor.address(),
                contractor.representative(), artist.legalName(), artist.document(),
                artist.address(), artist.representative(), stored.key(), stored.sha256(),
                stored.sizeBytes(), actor.getId());
            audit(actor, id, "PREPARED");
            return new Created(id);
        } catch (RuntimeException ex) {
            cleanup(artworkId, stored.key(), ex);
            if (ex instanceof DataIntegrityViolationException) {
                throw conflict("Já existe uma minuta para esta arte ou os dados não conferem");
            }
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public byte[] download(Authentication auth, long artworkId, long preparationId) {
        authorization.require(auth, PlatformPermission.CONTRACTS_MANAGE);
        Map<String,Object> row = requirePreparation(artworkId, preparationId, false);
        return documents.read(artworkId, (String) row.get("generated_storage_key"),
            (String) row.get("generated_sha256"));
    }

    @Transactional
    public void cancel(Authentication auth, long artworkId, long preparationId) {
        User actor = authorization.require(auth, PlatformPermission.CONTRACTS_MANAGE).user();
        requireArtwork(artworkId, true);
        Map<String,Object> prepared = requirePreparation(artworkId, preparationId, true);
        if ("SIGNED".equals(prepared.get("status"))) {
            throw conflict("O contrato já foi assinado; use encerramento do contrato");
        }
        if ("CANCELLED".equals(prepared.get("status"))) return;
        int updated = jdbc.update("""
            UPDATE commerce_contract_preparations
               SET status='CANCELLED',completed_by_user_id=?,
                   completed_at=CURRENT_TIMESTAMP(6)
             WHERE id=? AND artwork_id=? AND status='PENDING'
            """,actor.getId(),preparationId,artworkId);
        if (updated != 1) throw conflict("Esta minuta não está mais pendente");
        audit(actor,preparationId,"CANCELLED");
    }

    @Transactional
    public CommerceCatalogDtos.Created uploadSigned(
        Authentication auth, long artworkId, long preparationId,
        boolean reviewed, MultipartFile signedPdf
    ) {
        User actor = authorization.require(auth, PlatformPermission.CONTRACTS_MANAGE).user();
        if (!reviewed) {
            throw invalid("Confirme que conferiu as partes, termos e assinaturas no PDF recebido");
        }
        // Verify file format and size before contacting private storage.
        documents.verifiedPdf(signedPdf);
        Map<String,Object> art = requireArtwork(artworkId, true);
        Map<String,Object> prepared = requirePreparation(artworkId,preparationId,true);
        if ("SIGNED".equals(prepared.get("status"))) {
            return new CommerceCatalogDtos.Created(
                ((Number)prepared.get("signed_contract_id")).longValue());
        }
        if (!"PENDING".equals(prepared.get("status"))) {
            throw conflict("Minuta cancelada: gere um novo documento");
        }
        if ("ARCHIVED".equals(art.get("status"))) throw conflict("Arte arquivada");
        if (((Number)art.get("artist_user_id")).intValue() !=
            ((Number)prepared.get("artist_user_id")).intValue()) {
            throw conflict("A titularidade da obra mudou. Gere uma nova minuta.");
        }
        requireNoActiveAgreement(artworkId);
        var stored = documents.upload(artworkId, signedPdf);
        try {
            long contractId = insert("""
                INSERT INTO commerce_artwork_contracts
                    (artwork_id,template_id,artist_user_id,signed_artist_user_id,
                     modality_snapshot,royalty_method_snapshot,royalty_percent_snapshot,
                     royalty_fixed_snapshot,one_time_amount,effective_from,effective_until,
                     signed_storage_key,signed_sha256,signed_bytes,created_by_user_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, artworkId,prepared.get("template_id"),prepared.get("artist_user_id"),
                prepared.get("artist_user_id"),prepared.get("modality_snapshot"),
                prepared.get("royalty_method_snapshot"),prepared.get("royalty_percent_snapshot"),
                prepared.get("royalty_fixed_snapshot"),prepared.get("one_time_amount"),
                prepared.get("effective_from"),prepared.get("effective_until"),
                stored.key(),stored.sha256(),stored.sizeBytes(),actor.getId());
            int updated = jdbc.update("""
                UPDATE commerce_contract_preparations
                   SET status='SIGNED',signed_contract_id=?,
                       completed_by_user_id=?,completed_at=CURRENT_TIMESTAMP(6)
                 WHERE id=? AND artwork_id=? AND status='PENDING'
                """,contractId,actor.getId(),preparationId,artworkId);
            if (updated != 1) throw conflict("Minuta já foi utilizada");
            jdbc.update("""
                UPDATE commerce_artworks SET status='ACTIVE'
                 WHERE id=? AND status='DRAFT'
                """, artworkId);
            audit(actor,preparationId,"SIGNED_UPLOADED");
            jdbc.update("""
                INSERT INTO commerce_catalog_audit (actor_user_id,entity_type,entity_id,action)
                VALUES (?, 'ARTWORK_CONTRACT', ?, 'SIGNED')
                """,actor.getId(),contractId);
            return new CommerceCatalogDtos.Created(contractId);
        } catch (RuntimeException ex) {
            cleanup(artworkId,stored.key(),ex);
            if (ex instanceof DataIntegrityViolationException) {
                throw conflict("A arte já possui contrato ativo ou foi modificada");
            }
            throw ex;
        }
    }

    private Map<String,Object> requireArtwork(long artworkId, boolean lock) {
        List<Map<String,Object>> matches = jdbc.queryForList("""
            SELECT id,title,status,artist_user_id FROM commerce_artworks
             WHERE id=? """ + (lock ? " FOR UPDATE" : ""),artworkId);
        if (matches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Arte não encontrada");
        }
        return matches.get(0);
    }

    private Map<String,Object> requirePreparation(long artworkId,long id,boolean lock) {
        List<Map<String,Object>> matches=jdbc.queryForList("""
            SELECT * FROM commerce_contract_preparations
             WHERE id=? AND artwork_id=? """ + (lock ? " FOR UPDATE" : ""),id,artworkId);
        if (matches.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Minuta não encontrada");
        }
        return matches.get(0);
    }

    private void requireNoActiveAgreement(long artworkId) {
        if (!jdbc.queryForList("""
            SELECT id FROM commerce_artwork_contracts
             WHERE artwork_id=? AND status='ACTIVE' LIMIT 1
            """,artworkId).isEmpty()) {
            throw conflict("Esta arte já possui contrato ativo");
        }
    }

    private static ContractParty normalize(ContractParty party) {
        if (party == null) throw invalid("Informe as partes do contrato");
        return new ContractParty(
            nonBlank(party.legalName(),180,"Nome completo ou razão social"),
            nonBlank(party.document(),32,"Documento de identificação"),
            nonBlank(party.address(),500,"Endereço da parte"),
            nullable(party.representative(),180));
    }

    private static String nonBlank(String value,int max,String label) {
        String str = nullable(value,max);
        if (str==null) throw invalid(label+" é obrigatório");
        return str;
    }

    private static String nullable(String value,int max) {
        if (value==null || value.isBlank()) return null;
        String str=value.trim().replaceAll("\\s+"," ");
        if (str.length()>max) throw invalid("Campo ultrapassa o limite de "+max+" caracteres");
        return str;
    }

    private static BigDecimal money(BigDecimal value) {
        if (value==null || value.signum()<0 || value.scale()>2 ||
            value.compareTo(MAX_MONEY)>0) {
            throw invalid("Informe o valor único em reais com até duas casas decimais");
        }
        return value.setScale(2,RoundingMode.UNNECESSARY);
    }

    private long insert(String sql,Object...values) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(conn -> {
            PreparedStatement ps=conn.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for(int i=0;i<values.length;i++) ps.setObject(i+1,values[i]);
            return ps;
        },key);
        if(key.getKey()==null) throw new IllegalStateException("Id não retornado pelo banco");
        return key.getKey().longValue();
    }

    private void audit(User actor,long id,String action) {
        jdbc.update("""
            INSERT INTO commerce_catalog_audit(actor_user_id,entity_type,entity_id,action)
            VALUES (?, 'CONTRACT_PREPARATION', ?, ?)
            """,actor.getId(),id,action);
    }

    private void cleanup(long artworkId,String key,RuntimeException original) {
        try { documents.delete(artworkId,key); }
        catch (RuntimeException failure) { original.addSuppressed(failure); }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message);
    }
}
