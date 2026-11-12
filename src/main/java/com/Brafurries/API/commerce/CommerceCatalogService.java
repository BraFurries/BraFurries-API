package com.Brafurries.API.commerce;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;

import static com.Brafurries.API.commerce.CommerceCatalogDtos.*;

import com.Brafurries.API.entity.user.User;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * BRL catalog operations exclusive to BraFurries. All reads and writes verify
 * private PlatformPermission, never CommunityCapability/CommunityAdmin.
 */
@Service
public class CommerceCatalogService {
    private static final Set<String> MODALITIES = Set.of(
        "ONE_TIME_FEE", "RIGHTS_ASSIGNMENT", "EXCLUSIVE_LICENSE",
        "NONEXCLUSIVE_LICENSE", "ROYALTY", "HYBRID"
    );
    private static final Set<String> METHODS = Set.of(
        "NONE", "PERCENT_GROSS", "PERCENT_NET", "FIXED_UNIT", "FIXED_MONTH"
    );

    private final PlatformAuthorizationService authorization;
    private final CommercePrivateDocumentStorage documents;
    private final JdbcTemplate jdbc;

    public CommerceCatalogService(
        PlatformAuthorizationService authorization,
        CommercePrivateDocumentStorage documents,
        JdbcTemplate jdbc
    ) {
        this.authorization = authorization;
        this.documents = documents;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> templates(Authentication auth) {
        authorization.require(auth, PlatformPermission.CONTRACTS_MANAGE);
        return jdbc.queryForList("""
            SELECT id,name,description,full_text AS `fullText`,modality,royalty_method AS royaltyMethod,
                   royalty_percent AS royaltyPercent,royalty_fixed_amount AS royaltyFixedAmount,
                   active,created_at AS createdAt
              FROM commerce_contract_templates ORDER BY id DESC LIMIT 500
            """);
    }

    @Transactional
    public Created createTemplate(Authentication auth, CreateTemplate input) {
        User actor = authorization.require(auth, PlatformPermission.CONTRACTS_MANAGE).user();
        String modality = requireCode(input.modality(), MODALITIES, "Modalidade");
        String method = requireCode(input.royaltyMethod(), METHODS, "Método de repasse");
        BigDecimal percentage = input.royaltyPercent() == null
            ? null : rate(input.royaltyPercent());
        BigDecimal fixed = input.royaltyFixedAmount() == null
            ? null : money(input.royaltyFixedAmount(), "Valor fixo");
        validateRoyaltyTerms(method, percentage, fixed);
        String name = text(input.name(), 160, "Nome do modelo");
        String fullText = contractText(input.fullText());
        try {
            long id = insert("""
                INSERT INTO commerce_contract_templates
                    (name,description,full_text,modality,royalty_method,
                     royalty_percent,royalty_fixed_amount,created_by_user_id)
                VALUES (?,?,?,?,?,?,?,?)
                """, name, optional(input.description(), 5000), fullText,modality,
                method,percentage,fixed,actor.getId());
            audit(actor, "CONTRACT_TEMPLATE", id, "CREATED");
            return new Created(id);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("Já existe um modelo de contrato com esse nome", ex);
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> artworks(Authentication auth) {
        // Contract managers need to select an existing artwork even without
        // the right to register or modify artworks.
        var access = authorization.resolve(auth).access();
        if (!access.has(PlatformPermission.ARTWORKS_MANAGE)
            && !access.has(PlatformPermission.CONTRACTS_MANAGE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Sem autorização para consultar as artes");
        }
        return jdbc.queryForList("""
            SELECT art.id,art.title,art.description,art.artist_user_id AS artistUserId,
                   COALESCE(p.artist_name,u.display_name,u.username,CONCAT('Usuário ',u.id)) AS artistName,
                   art.creation_cost AS creationCost,art.status,art.created_at AS createdAt
              FROM commerce_artworks art
              JOIN users u ON u.id=art.artist_user_id
              LEFT JOIN commerce_artist_profiles p ON p.user_id=art.artist_user_id
             ORDER BY art.id DESC LIMIT 500
            """);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> searchArtists(Authentication auth,String search) {
        authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE);
        if (search == null || search.trim().length() < 2 || search.length() > 100) {
            throw invalid("Informe ao menos 2 caracteres para pesquisar artistas");
        }
        String like = "%" + search.trim().replace("%","").replace("_","") + "%";
        return jdbc.queryForList("""
            SELECT u.id,COALESCE(p.artist_name,u.display_name,u.username,
                CONCAT('Usuário ',u.id)) AS name
              FROM users u
              LEFT JOIN commerce_artist_profiles p ON p.user_id=u.id
              LEFT JOIN user_community_status member
                ON member.user_id=u.id AND member.community_id=?
             WHERE (p.user_id IS NOT NULL
                    OR (member.is_present=1 AND COALESCE(member.banned,0)=0))
               AND (LOWER(COALESCE(p.artist_name,'')) LIKE LOWER(?)
                    OR LOWER(COALESCE(u.display_name,'')) LIKE LOWER(?)
                    OR LOWER(COALESCE(u.username,'')) LIKE LOWER(?)
                    OR LOWER(COALESCE(member.display_name,'')) LIKE LOWER(?))
             ORDER BY u.id DESC LIMIT 30
            """,authorization.officialCommunityId(),like,like,like,like);
    }

    @Transactional
    public Created createArtwork(Authentication auth, CreateArtwork input) {
        User actor = authorization.require(auth, PlatformPermission.ARTWORKS_MANAGE).user();
        requireExists("users", input.artistUserId(), "Artista");
        long id = insert("""
            INSERT INTO commerce_artworks
                (title,description,artist_user_id,creation_cost,created_by_user_id)
            VALUES (?,?,?,?,?)
            """, text(input.title(), 180, "Título da arte"),
            optional(input.description(), 5000),
            input.artistUserId(), money(input.creationCost(), "Valor da arte"), actor.getId());
        audit(actor, "ARTWORK", id, "CREATED");
        return new Created(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> suppliers(Authentication auth) {
        authorization.require(auth, PlatformPermission.SUPPLIERS_MANAGE);
        return jdbc.queryForList("""
            SELECT id,name,contact_name AS contactName,email,phone,notes,active,
                   created_at AS createdAt
              FROM commerce_suppliers ORDER BY id DESC LIMIT 500
            """);
    }

    @Transactional
    public Created createSupplier(Authentication auth, CreateSupplier input) {
        User actor = authorization.require(auth, PlatformPermission.SUPPLIERS_MANAGE).user();
        try {
            long id = insert("""
                INSERT INTO commerce_suppliers
                    (name,contact_name,email,phone,notes,created_by_user_id)
                VALUES (?,?,?,?,?,?)
                """, text(input.name(),180,"Nome do fornecedor"),
                optional(input.contactName(),140), optional(input.email(),255),
                optional(input.phone(),64), optional(input.notes(),5000),actor.getId());
            audit(actor, "SUPPLIER", id, "CREATED");
            return new Created(id);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("Fornecedor já cadastrado com esse nome", ex);
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> supplierChoices(Authentication auth) {
        authorization.require(auth,PlatformPermission.PRODUCTS_MANAGE);
        return jdbc.queryForList("""
            SELECT id,name FROM commerce_suppliers WHERE active=1 ORDER BY name,id LIMIT 500
            """);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> products(Authentication auth) {
        authorization.require(auth, PlatformPermission.PRODUCTS_MANAGE);
        return jdbc.queryForList("""
            SELECT product.id,product.sku,product.name,product.description,
                   product.estimated_unit_cost AS estimatedUnitCost,
                   product.suggested_price AS suggestedPrice,
                   product.preferred_supplier_id AS preferredSupplierId,
                   supplier.name AS preferredSupplierName,
                   product.active,product.created_at AS createdAt
              FROM commerce_products product
              LEFT JOIN commerce_suppliers supplier
                ON supplier.id=product.preferred_supplier_id
             ORDER BY product.id DESC LIMIT 500
            """);
    }

    @Transactional
    public Created createProduct(Authentication auth, CreateProduct input) {
        User actor = authorization.require(auth, PlatformPermission.PRODUCTS_MANAGE).user();
        if (input.preferredSupplierId()!=null) {
            requireExistsActive("commerce_suppliers",input.preferredSupplierId(),"Fornecedor");
        }
        try {
            long id = insert("""
                INSERT INTO commerce_products
                    (sku,name,description,estimated_unit_cost,suggested_price,
                     preferred_supplier_id,created_by_user_id)
                VALUES (?,?,?,?,?,?,?)
                """, text(input.sku(),80,"SKU"),text(input.name(),180,"Produto"),
                optional(input.description(),5000),
                money(input.estimatedUnitCost(),"Custo estimado"),
                money(input.suggestedPrice(),"Preço sugerido"),
                input.preferredSupplierId(),actor.getId());
            audit(actor,"PRODUCT",id,"CREATED");
            return new Created(id);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("Esse SKU já está cadastrado",ex);
        }
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> productArtworks(Authentication auth, long productId) {
        authorization.require(auth,PlatformPermission.PRODUCTS_MANAGE);
        requireExists("commerce_products",productId,"Produto");
        return jdbc.queryForList("""
            SELECT rel.artwork_id AS artworkId,art.title,
                   rel.royalty_allocation_percent AS royaltyAllocationPercent
              FROM commerce_product_artworks rel
              JOIN commerce_artworks art ON art.id=rel.artwork_id
             WHERE rel.product_id=? ORDER BY art.title
            """,productId);
    }

    @Transactional
    public void linkArtwork(Authentication auth, long productId, long artworkId, LinkArtwork input) {
        User actor = authorization.require(auth,PlatformPermission.PRODUCTS_MANAGE).user();
        // Allocating royalties is a contractual decision, never an implicit
        // privilege of being able to add products.
        authorization.require(auth,PlatformPermission.CONTRACTS_MANAGE);
        lock("commerce_products",productId,"Produto");
        requireExists("commerce_artworks",artworkId,"Arte");
        String artworkStatus=jdbc.queryForObject(
            "SELECT status FROM commerce_artworks WHERE id=?",String.class,artworkId);
        if("ARCHIVED".equals(artworkStatus)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Obra arquivada: reative antes de vincular a novos produtos");
        }
        BigDecimal rate = input.royaltyAllocationPercent()==null
            ? null : rate(input.royaltyAllocationPercent());
        List<BigDecimal> previous = jdbc.query("""
            SELECT royalty_allocation_percent FROM commerce_product_artworks
             WHERE product_id=? AND artwork_id=?
            """,(rs,index)->rs.getBigDecimal(1),productId,artworkId);
        BigDecimal other = jdbc.queryForObject("""
            SELECT COALESCE(SUM(royalty_allocation_percent),0)
              FROM commerce_product_artworks
             WHERE product_id=? AND artwork_id<>?
            """,BigDecimal.class,productId,artworkId);
        if (rate != null && other != null && other.add(rate).compareTo(BigDecimal.valueOf(100))>0) {
            throw invalid("A soma do rateio de royalties das artes não pode ultrapassar 100%");
        }
        if (previous.isEmpty()) {
            jdbc.update("""
                INSERT INTO commerce_product_artworks
                    (product_id,artwork_id,royalty_allocation_percent,created_by_user_id)
                VALUES (?,?,?,?)
                """,productId,artworkId,rate,actor.getId());
        } else {
            jdbc.update("""
                UPDATE commerce_product_artworks SET royalty_allocation_percent=?
                 WHERE product_id=? AND artwork_id=?
                """,rate,productId,artworkId);
        }
        audit(actor,"PRODUCT",productId,"ARTWORK_LINKED");
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> productionBatches(Authentication auth) {
        authorization.require(auth,PlatformPermission.PRODUCTS_MANAGE);
        authorization.require(auth,PlatformPermission.SUPPLIERS_MANAGE);
        return jdbc.queryForList("""
            SELECT batch.id,batch.product_id AS productId,product.name AS productName,
                   batch.supplier_id AS supplierId,supplier.name AS supplierName,
                   batch.quantity,batch.unit_cost AS unitCost,
                   batch.additional_cost AS additionalCost,batch.total_cost AS totalCost,
                   batch.produced_on AS producedOn,batch.reference,batch.created_at AS createdAt
              FROM commerce_production_batches batch
              JOIN commerce_products product ON product.id=batch.product_id
              JOIN commerce_suppliers supplier ON supplier.id=batch.supplier_id
             ORDER BY batch.id DESC LIMIT 500
            """);
    }

    @Transactional
    public Created createProductionBatch(Authentication auth, CreateProductionBatch input) {
        User actor = authorization.require(auth,PlatformPermission.PRODUCTS_MANAGE).user();
        authorization.require(auth,PlatformPermission.SUPPLIERS_MANAGE);
        requireExistsActive("commerce_products",input.productId(),"Produto");
        requireExistsActive("commerce_suppliers",input.supplierId(),"Fornecedor");
        if (input.quantity()==null || input.quantity()<=0 || input.quantity()>10000000) {
            throw invalid("A quantidade do lote deve ser positiva e não exceder 10 milhões");
        }
        if (input.producedOn()==null || input.producedOn().isAfter(LocalDate.now(java.time.ZoneId.of("America/Sao_Paulo")))) {
            throw invalid("Informe uma data de produção válida, sem data futura");
        }
        BigDecimal unit=money(input.unitCost(),"Custo unitário");
        BigDecimal extra=money(input.additionalCost(),"Custos adicionais");
        BigDecimal total=unit.multiply(BigDecimal.valueOf(input.quantity())).add(extra);
        if (total.compareTo(new BigDecimal("9999999999999999.99"))>0) {
            throw invalid("O valor total do lote é muito alto");
        }
        long id=insert("""
            INSERT INTO commerce_production_batches
                (product_id,supplier_id,quantity,unit_cost,additional_cost,total_cost,
                 produced_on,reference,created_by_user_id)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,input.productId(),input.supplierId(),input.quantity(),unit,extra,total,
            input.producedOn(),optional(input.reference(),160),actor.getId());
        audit(actor,"PRODUCTION_BATCH",id,"CREATED");
        return new Created(id);
    }

    @Transactional(readOnly = true)
    public List<Map<String,Object>> contracts(Authentication auth, long artworkId) {
        authorization.require(auth,PlatformPermission.CONTRACTS_MANAGE);
        requireExists("commerce_artworks",artworkId,"Arte");
        // Deliberately DO NOT expose private R2 object keys via the API.
        return jdbc.queryForList("""
            SELECT c.id,c.artwork_id AS artworkId,c.template_id AS templateId,
                   c.artist_user_id AS artistUserId,c.modality_snapshot AS modality,
                   c.royalty_method_snapshot AS royaltyMethod,
                   c.royalty_percent_snapshot AS royaltyPercent,
                   c.royalty_fixed_snapshot AS royaltyFixedAmount,
                   c.one_time_amount AS oneTimeAmount,
                   c.effective_from AS effectiveFrom,c.effective_until AS effectiveUntil,
                   c.signed_sha256 AS sha256,c.signed_bytes AS sizeBytes,c.status,
                   c.created_at AS createdAt
              FROM commerce_artwork_contracts c WHERE c.artwork_id=?
             ORDER BY c.id DESC
            """,artworkId);
    }

    @Transactional
    public void terminateContract(Authentication auth,long artworkId,long contractId) {
        User actor=authorization.require(auth,PlatformPermission.CONTRACTS_MANAGE).user();
        lock("commerce_artworks",artworkId,"Arte");
        int changed=jdbc.update("""
            UPDATE commerce_artwork_contracts
               SET status='TERMINATED',ended_at=CURRENT_TIMESTAMP(6)
             WHERE id=? AND artwork_id=? AND status='ACTIVE'
            """,contractId,artworkId);
        if (changed==0) {
            requireContract(artworkId,contractId);
            return; // already ended: idempotent
        }
        audit(actor,"ARTWORK_CONTRACT",contractId,"TERMINATED");
    }

    /**
     * Owner-only removal of a demonstrably unused test agreement and its
     * private R2 PDF. This is not a general contract cancellation operation:
     * any linked accrual or signed preparation prevents physical deletion.
     *
     * The R2 deletion is deliberately the last operation before the JDBC
     * transaction commits. If R2 rejects the delete the DB transaction rolls
     * back, keeping the signed agreement intact.
     */
    @Transactional
    public void discardUnusedTestContract(Authentication auth,long artworkId,long contractId) {
        authorization.requireOwner(auth);
        lock("commerce_artworks",artworkId,"Arte");
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT id,signed_storage_key FROM commerce_artwork_contracts
             WHERE id=? AND artwork_id=? FOR UPDATE
            """,contractId,artworkId);
        if (rows.isEmpty()) throw notFound("Contrato");
        if (jdbc.queryForObject("""
            SELECT COUNT(*) FROM commerce_royalty_accruals
             WHERE contract_id=?
            """,Integer.class,contractId)>0) {
            throw conflict("Contrato com royalties registrados não pode ser excluído",null);
        }
        if (jdbc.queryForObject("""
            SELECT COUNT(*) FROM commerce_contract_preparations
             WHERE signed_contract_id=?
            """,Integer.class,contractId)>0) {
            throw conflict("Contrato vinculado a uma minuta assinada não pode ser excluído",null);
        }
        try {
            if (jdbc.update("""
                DELETE FROM commerce_artwork_contracts
                 WHERE id=? AND artwork_id=?
                """,contractId,artworkId)!=1) {
                throw conflict("Contrato alterado durante exclusão",null);
            }
        } catch (DataIntegrityViolationException ex) {
            throw conflict("Contrato possui dependências e não pode ser excluído",ex);
        }
        jdbc.update("""
            UPDATE commerce_artworks a
               SET status='DRAFT'
             WHERE a.id=? AND a.status='ACTIVE'
               AND NOT EXISTS(
                   SELECT 1 FROM commerce_artwork_contracts c
                    WHERE c.artwork_id=a.id AND c.status='ACTIVE')
            """,artworkId);
        // No object key is sent to the browser, and no other R2 prefix is allowed.
        documents.deleteExistingContract(artworkId,(String)rows.get(0).get("signed_storage_key"));
    }

    @Transactional(readOnly = true)
    public byte[] downloadContract(Authentication auth,long artworkId,long contractId) {
        authorization.require(auth,PlatformPermission.CONTRACTS_MANAGE);
        Map<String,Object> agreement=requireContract(artworkId,contractId);
        return documents.read(artworkId,agreement.get("signed_storage_key").toString(),
            agreement.get("signed_sha256").toString());
    }

    private Map<String,Object> requireContract(long artworkId,long contractId) {
        List<Map<String,Object>> matches=jdbc.queryForList("""
            SELECT signed_storage_key,signed_sha256 FROM commerce_artwork_contracts
             WHERE artwork_id=? AND id=?
            """,artworkId,contractId);
        if (matches.isEmpty()) throw notFound("Contrato");
        return matches.get(0);
    }

    private void lock(String table,long id,String label) {
        if (!Set.of("commerce_products","commerce_artworks").contains(table)) {
            throw new IllegalStateException("Illegal lock table");
        }
        List<Long> matches=jdbc.queryForList(
            "SELECT id FROM "+table+" WHERE id=? FOR UPDATE",Long.class,id);
        if (matches.isEmpty()) throw notFound(label);
    }

    private void requireExists(String table,Number id,String label) {
        if (!Set.of("users","commerce_artworks","commerce_products").contains(table)) {
            throw new IllegalStateException("Illegal entity table");
        }
        if (id==null || id.longValue()<1 ||
            jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=?",
                Integer.class,id)==0) throw notFound(label);
    }

    private void requireExistsActive(String table,Number id,String label) {
        if (!Set.of("commerce_products","commerce_suppliers").contains(table)) {
            throw new IllegalStateException("Illegal active entity table");
        }
        if (id==null || id.longValue()<1 ||
            jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=? AND active=1",
                Integer.class,id)==0) throw notFound(label+" ativo");
    }

    private String contractText(String body) {
        if (body == null || body.isBlank() || body.length() > 60000) {
            throw invalid("Preencha as cláusulas, termos e concessões (até 60.000 caracteres)");
        }
        return body.replace("\r\n","\n").trim();
    }

    private String requireCode(String code,Set<String> allowed,String label) {
        if (code==null || !allowed.contains(code)) throw invalid(label+" inválida");
        return code;
    }

    private void validateRoyaltyTerms(String method,BigDecimal percent,BigDecimal fixed) {
        boolean valid=switch(method) {
            case "NONE" -> percent==null && fixed==null;
            case "PERCENT_GROSS","PERCENT_NET" -> percent!=null && fixed==null;
            case "FIXED_UNIT","FIXED_MONTH" -> percent==null && fixed!=null;
            default -> false;
        };
        if (!valid) throw invalid("Informe somente o percentual ou valor fixo exigido pelo método de repasse");
    }

    private BigDecimal rate(BigDecimal value) {
        if (value==null || value.signum()<0 || value.compareTo(BigDecimal.valueOf(100))>0
            || value.scale()>4) {
            throw invalid("Percentual deve estar entre 0 e 100, com no máximo 4 casas decimais");
        }
        return value.setScale(4,RoundingMode.UNNECESSARY);
    }

    private BigDecimal money(BigDecimal value,String label) {
        if (value==null || value.signum()<0 || value.scale()>2
            || value.compareTo(new BigDecimal("9999999999999999.99"))>0) {
            throw invalid(label+" deve ser positivo ou zero, em reais, com no máximo 2 casas decimais");
        }
        return value.setScale(2,RoundingMode.UNNECESSARY);
    }

    private String text(String value,int max,String label) {
        if (value==null || value.isBlank() || value.trim().length()>max) {
            throw invalid(label+" deve ser preenchido e ter no máximo "+max+" caracteres");
        }
        return value.trim();
    }

    private String optional(String value,int max) {
        if (value==null || value.isBlank()) return null;
        if (value.trim().length()>max) throw invalid("Texto ultrapassa o limite de "+max+" caracteres");
        return value.trim();
    }

    private long insert(String sql,Object...values) {
        KeyHolder key=new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement=connection.prepareStatement(sql,Statement.RETURN_GENERATED_KEYS);
            for (int i=0;i<values.length;i++) statement.setObject(i+1,values[i]);
            return statement;
        },key);
        if (key.getKey()==null) throw new IllegalStateException("Id não retornado pelo banco");
        return key.getKey().longValue();
    }

    private void audit(User actor,String type,long id,String action) {
        jdbc.update("""
            INSERT INTO commerce_catalog_audit (actor_user_id,entity_type,entity_id,action)
            VALUES (?,?,?,?)
            """,actor.getId(),type,id,action);
    }

    private ResponseStatusException notFound(String type) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,type+" não encontrado");
    }

    private ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }

    private ResponseStatusException conflict(String message,Throwable cause) {
        return new ResponseStatusException(HttpStatus.CONFLICT,message,cause);
    }
}
