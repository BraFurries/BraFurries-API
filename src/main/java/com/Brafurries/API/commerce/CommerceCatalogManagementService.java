package com.Brafurries.API.commerce;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import static com.Brafurries.API.commerce.CommerceCatalogDtos.*;
import static com.Brafurries.API.commerce.CommerceArtistDtos.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Administrative corrections to the BraFurries-only commercial catalog.
 * Historical contracts, stock and payment snapshots are never rewritten.
 * Physical deletion only succeeds when foreign keys prove a record unused.
 * All transactions and authorization are server-side (never UI-only).
 */
@Service
public class CommerceCatalogManagementService {
    private static final Set<String> MODALITIES=Set.of(
        "ONE_TIME_FEE","RIGHTS_ASSIGNMENT","EXCLUSIVE_LICENSE",
        "NONEXCLUSIVE_LICENSE","ROYALTY","HYBRID");
    private static final Set<String> METHODS=Set.of(
        "NONE","PERCENT_GROSS","PERCENT_NET","FIXED_UNIT","FIXED_MONTH");
    private final PlatformAuthorizationService authorization;
    private final JdbcTemplate jdbc;

    public CommerceCatalogManagementService(PlatformAuthorizationService authorization,
                                             JdbcTemplate jdbc) {
        this.authorization=authorization;
        this.jdbc=jdbc;
    }

    @Transactional
    public void updateArtwork(Authentication auth,long id,CreateArtwork input) {
        User actor=require(auth,PlatformPermission.ARTWORKS_MANAGE);
        if(input==null) throw invalid("Dados da obra obrigatórios");
        Map<String,Object> original=artwork(id);
        int newArtist=input.artistUserId()==null?0:input.artistUserId();
        if(newArtist<1 || !exists("SELECT COUNT(*) FROM users WHERE id=? AND merged_into_user_id IS NULL",
                                newArtist)) throw invalid("Selecione uma artista válida");
        BigDecimal newCost=money(input.creationCost(),"Custo da criação");
        boolean changedArtist=((Number)original.get("artistId")).intValue()!=newArtist;
        boolean changedCost=((BigDecimal)original.get("creationCost")).compareTo(newCost)!=0;
        if((changedArtist||changedCost) && exists(
             "SELECT COUNT(*) FROM commerce_artwork_contracts WHERE artwork_id=?",id)) {
            throw conflict("A obra já possui contrato histórico. Só título e descrição podem ser corrigidos.");
        }
        if((changedArtist||changedCost) && exists(
             "SELECT COUNT(*) FROM commerce_royalty_accruals WHERE artwork_id=?",id)) {
            throw conflict("Obra com royalties históricos: titular e custo não podem ser alterados.");
        }
        jdbc.update("""
            UPDATE commerce_artworks
               SET title=?,description=?,artist_user_id=?,creation_cost=?
             WHERE id=?
            """,text(input.title(),180,"Título"),optional(input.description(),5000),
                newArtist,newCost,id);
        audit(actor,"ARTWORK",id,"UPDATED");
    }

    @Transactional
    public void setArtworkArchived(Authentication auth,long id,boolean archive) {
        User actor=require(auth,PlatformPermission.ARTWORKS_MANAGE);
        Map<String,Object> original=artwork(id);
        String current=(String)original.get("status");
        if(archive && !"ARCHIVED".equals(current)) {
            if(exists("SELECT COUNT(*) FROM commerce_artwork_contracts "+
                      "WHERE artwork_id=? AND status='ACTIVE'",id)) {
                throw conflict("Encerre o contrato ativo antes de arquivar a obra");
            }
            jdbc.update("UPDATE commerce_artworks SET status='ARCHIVED' WHERE id=?",id);
            audit(actor,"ARTWORK",id,"ARCHIVED");
        } else if(!archive && "ARCHIVED".equals(current)) {
            boolean contractActive=exists("SELECT COUNT(*) FROM commerce_artwork_contracts "+
                                          "WHERE artwork_id=? AND status='ACTIVE'",id);
            jdbc.update("UPDATE commerce_artworks SET status=? WHERE id=?",
                        contractActive?"ACTIVE":"DRAFT",id);
            audit(actor,"ARTWORK",id,"RESTORED");
        }
    }

    @Transactional
    public void deleteArtwork(Authentication auth,long id) {
        User actor=require(auth,PlatformPermission.ARTWORKS_MANAGE);
        Map<String,Object> original=artwork(id);
        if("ACTIVE".equals(original.get("status"))) {
            throw conflict("Arquive a obra antes de solicitar a exclusão");
        }
        try {
            int deleted=jdbc.update("DELETE FROM commerce_artworks WHERE id=?",id);
            if(deleted!=1) throw missing("Obra");
        } catch(DataIntegrityViolationException ex) {
            throw conflict("Obra com vínculos, contrato, arquivo ou histórico: arquive, não exclua");
        }
        audit(actor,"ARTWORK",id,"DELETED");
    }

    @Transactional
    public void updateSupplier(Authentication auth,long id,CreateSupplier input) {
        User actor=require(auth,PlatformPermission.SUPPLIERS_MANAGE);
        if(input==null) throw invalid("Dados do fornecedor obrigatórios");
        lockById("commerce_suppliers",id,"Fornecedor");
        try {
            jdbc.update("""
                UPDATE commerce_suppliers
                   SET name=?,contact_name=?,email=?,phone=?,notes=?
                 WHERE id=?
                """,text(input.name(),180,"Nome"),optional(input.contactName(),140),
                    optional(input.email(),255),optional(input.phone(),64),
                    optional(input.notes(),5000),id);
        } catch(DataIntegrityViolationException ex) {
            throw conflict("Já existe um fornecedor com esse nome");
        }
        audit(actor,"SUPPLIER",id,"UPDATED");
    }

    @Transactional
    public void setSupplierActive(Authentication auth,long id,boolean active) {
        User actor=require(auth,PlatformPermission.SUPPLIERS_MANAGE);
        lockById("commerce_suppliers",id,"Fornecedor");
        jdbc.update("UPDATE commerce_suppliers SET active=? WHERE id=?",active?1:0,id);
        audit(actor,"SUPPLIER",id,active?"RESTORED":"ARCHIVED");
    }

    @Transactional
    public void deleteSupplier(Authentication auth,long id) {
        User actor=require(auth,PlatformPermission.SUPPLIERS_MANAGE);
        lockById("commerce_suppliers",id,"Fornecedor");
        try {
            jdbc.update("DELETE FROM commerce_suppliers WHERE id=?",id);
        } catch(DataIntegrityViolationException ex) {
            throw conflict("Fornecedor já utilizado por produtos ou lotes: desative em vez de excluir");
        }
        audit(actor,"SUPPLIER",id,"DELETED");
    }

    @Transactional
    public void updateProduct(Authentication auth,long id,CreateProduct input) {
        User actor=require(auth,PlatformPermission.PRODUCTS_MANAGE);
        if(input==null) throw invalid("Dados do produto obrigatórios");
        Map<String,Object> rows=one("""
            SELECT sku,preferred_supplier_id AS supplierId FROM commerce_products
             WHERE id=? FOR UPDATE
            """,id);
        if(rows==null) throw missing("Produto");
        String sku=text(input.sku(),80,"SKU");
        if(!sku.equals(rows.get("sku")) &&
           (exists("SELECT COUNT(*) FROM commerce_production_batches WHERE product_id=?",id) ||
            exists("SELECT COUNT(*) FROM commerce_order_items WHERE product_id=?",id))) {
            throw conflict("O SKU não pode mudar após produção ou encomendas");
        }
        if(input.preferredSupplierId()!=null &&
           !input.preferredSupplierId().equals(asLong(rows.get("supplierId"))) &&
           !exists("SELECT COUNT(*) FROM commerce_suppliers WHERE id=? AND active=1",
                   input.preferredSupplierId())) {
            throw invalid("Fornecedor preferencial inativo ou inexistente");
        }
        try {
            jdbc.update("""
                UPDATE commerce_products
                   SET sku=?,name=?,description=?,estimated_unit_cost=?,
                       suggested_price=?,preferred_supplier_id=?
                 WHERE id=?
                """,sku,text(input.name(),180,"Produto"),
                    optional(input.description(),5000),
                    money(input.estimatedUnitCost(),"Custo estimado"),
                    money(input.suggestedPrice(),"Preço sugerido"),
                    input.preferredSupplierId(),id);
        } catch(DataIntegrityViolationException ex) {
            throw conflict("Este SKU já está cadastrado");
        }
        audit(actor,"PRODUCT",id,"UPDATED");
    }

    @Transactional
    public void setProductActive(Authentication auth,long id,boolean active) {
        User actor=require(auth,PlatformPermission.PRODUCTS_MANAGE);
        lockById("commerce_products",id,"Produto");
        jdbc.update("UPDATE commerce_products SET active=? WHERE id=?",active?1:0,id);
        audit(actor,"PRODUCT",id,active?"RESTORED":"ARCHIVED");
    }

    @Transactional
    public void deleteProduct(Authentication auth,long id) {
        User actor=require(auth,PlatformPermission.PRODUCTS_MANAGE);
        lockById("commerce_products",id,"Produto");
        try {
            jdbc.update("DELETE FROM commerce_products WHERE id=?",id);
        } catch(DataIntegrityViolationException ex) {
            throw conflict("Produto utilizado por lotes, encomendas ou artes: desative em vez de excluir");
        }
        audit(actor,"PRODUCT",id,"DELETED");
    }

    @Transactional
    public void updateTemplate(Authentication auth,long id,CreateTemplate input) {
        User actor=require(auth,PlatformPermission.CONTRACTS_MANAGE);
        if(input==null) throw invalid("Dados do modelo obrigatórios");
        lockById("commerce_contract_templates",id,"Modelo");
        String method=code(input.royaltyMethod(),METHODS,"Método de repasse");
        String modality=code(input.modality(),MODALITIES,"Modalidade");
        BigDecimal rate=input.royaltyPercent()==null?null:rate(input.royaltyPercent());
        BigDecimal fixed=input.royaltyFixedAmount()==null?null:
            money(input.royaltyFixedAmount(),"Valor fixo");
        if(!(("NONE".equals(method) && rate==null && fixed==null) ||
             (Set.of("PERCENT_GROSS","PERCENT_NET").contains(method) &&
                rate!=null && fixed==null) ||
             (Set.of("FIXED_UNIT","FIXED_MONTH").contains(method) &&
                rate==null && fixed!=null))) {
            throw invalid("Confira a modalidade e os valores da regra de royalties");
        }
        try {
            jdbc.update("""
                UPDATE commerce_contract_templates
                   SET name=?,description=?,modality=?,royalty_method=?,
                       royalty_percent=?,royalty_fixed_amount=?
                 WHERE id=?
                """,text(input.name(),160,"Modelo"),optional(input.description(),5000),
                    modality,method,rate,fixed,id);
        } catch(DataIntegrityViolationException ex) {
            throw conflict("Já existe um modelo de contrato com esse nome");
        }
        // Signed agreements retain immutable royalty snapshots.
        audit(actor,"CONTRACT_TEMPLATE",id,"UPDATED");
    }

    @Transactional
    public void setTemplateActive(Authentication auth,long id,boolean active) {
        User actor=require(auth,PlatformPermission.CONTRACTS_MANAGE);
        lockById("commerce_contract_templates",id,"Modelo");
        jdbc.update("UPDATE commerce_contract_templates SET active=? WHERE id=?",active?1:0,id);
        audit(actor,"CONTRACT_TEMPLATE",id,active?"RESTORED":"ARCHIVED");
    }

    @Transactional
    public void deleteTemplate(Authentication auth,long id) {
        User actor=require(auth,PlatformPermission.CONTRACTS_MANAGE);
        lockById("commerce_contract_templates",id,"Modelo");
        try {
            jdbc.update("DELETE FROM commerce_contract_templates WHERE id=?",id);
        } catch(DataIntegrityViolationException ex) {
            throw conflict("Modelo com contratos assinados: desative em vez de excluir");
        }
        audit(actor,"CONTRACT_TEMPLATE",id,"DELETED");
    }

    @Transactional
    public void unlinkArtwork(Authentication auth,long productId,long artworkId) {
        User actor=require(auth,PlatformPermission.PRODUCTS_MANAGE);
        authorization.require(auth,PlatformPermission.CONTRACTS_MANAGE);
        // Matches the product lock order used when booking a sale.
        lockById("commerce_products",productId,"Produto");
        int deleted=jdbc.update("""
            DELETE FROM commerce_product_artworks
             WHERE product_id=? AND artwork_id=?
            """,productId,artworkId);
        if(deleted==0) throw missing("Vínculo entre arte e produto");
        // Recorded royalty snapshots remain untouched.
        audit(actor,"PRODUCT",productId,"ARTWORK_UNLINKED");
    }

    @Transactional
    public void updateArtist(Authentication auth,int userId,RegisterExternalArtist input) {
        User actor=require(auth,PlatformPermission.ARTWORKS_MANAGE);
        if(userId<1 || input==null) throw invalid("Artista inválida");
        var profile=one("""
            SELECT user_id FROM commerce_artist_profiles WHERE user_id=? FOR UPDATE
            """,userId);
        if(profile==null) throw missing("Artista externa");
        String email=optional(input.contactEmail(),255);
        if(email!=null && !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw invalid("E-mail de contato inválido");
        }
        jdbc.update("""
            UPDATE commerce_artist_profiles
               SET artist_name=?,telegram_handle=?,instagram_handle=?,
                   contact_email=?,internal_notes=?
             WHERE user_id=?
            """,text(input.artistName(),120,"Nome artístico"),
                optional(input.telegramHandle(),100),
                optional(input.instagramHandle(),100),
                email,optional(input.internalNotes(),5000),userId);
        audit(actor,"ARTIST",userId,"UPDATED");
    }

    private User require(Authentication auth,PlatformPermission permission) {
        return authorization.require(auth,permission).user();
    }
    private Map<String,Object> artwork(long id) {
        Map<String,Object> row=one("""
            SELECT artist_user_id AS artistId,creation_cost AS creationCost,status
              FROM commerce_artworks WHERE id=? FOR UPDATE
            """,id);
        if(row==null) throw missing("Obra");
        return row;
    }
    private Map<String,Object> one(String sql,Object id) {
        List<Map<String,Object>> rows=jdbc.queryForList(sql,id);
        return rows.isEmpty()?null:rows.get(0);
    }
    private void lockById(String table,long id,String label) {
        if(!Set.of("commerce_suppliers","commerce_products",
                   "commerce_contract_templates").contains(table)) {
            throw new IllegalArgumentException("Tabela não permitida");
        }
        if(one("SELECT id FROM "+table+" WHERE id=? FOR UPDATE",id)==null)
            throw missing(label);
    }
    private boolean exists(String sql,Object id) {
        Number count=jdbc.queryForObject(sql,Number.class,id);
        return count!=null && count.longValue()>0;
    }
    private Long asLong(Object value) {
        return value==null?null:((Number)value).longValue();
    }
    private String text(String value,int max,String name) {
        if(value==null || value.isBlank() || value.trim().length()>max)
            throw invalid(name+" deve ser informado (máximo "+max+" caracteres)");
        return value.trim();
    }
    private String optional(String value,int max) {
        if(value==null || value.isBlank()) return null;
        if(value.trim().length()>max) throw invalid("Texto excede "+max+" caracteres");
        return value.trim();
    }
    private BigDecimal money(BigDecimal value,String label) {
        if(value==null || value.signum()<0 || value.scale()>2 ||
           value.compareTo(new BigDecimal("9999999999999999.99"))>0)
            throw invalid(label+" inválido (R$, 2 casas)");
        return value.setScale(2,RoundingMode.UNNECESSARY);
    }
    private BigDecimal rate(BigDecimal value) {
        if(value.signum()<0 || value.compareTo(new BigDecimal("100"))>0 ||
           value.scale()>4) throw invalid("Percentual de royalties inválido");
        return value.setScale(4,RoundingMode.UNNECESSARY);
    }
    private String code(String value,Set<String> allowed,String label) {
        if(value==null || !allowed.contains(value)) throw invalid(label+" inválida");
        return value;
    }
    private void audit(User actor,String type,long id,String action) {
        jdbc.update("""
            INSERT INTO commerce_catalog_audit(actor_user_id,entity_type,entity_id,action)
            VALUES (?,?,?,?)
            """,actor.getId(),type,id,action);
    }
    private ResponseStatusException invalid(String why) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,why);
    }
    private ResponseStatusException conflict(String why) {
        return new ResponseStatusException(HttpStatus.CONFLICT,why);
    }
    private ResponseStatusException missing(String name) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,name+" não encontrado");
    }
}
