package com.Brafurries.API.commerce;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import static com.Brafurries.API.commerce.CommerceFinanceSupport.*;
import static com.Brafurries.API.commerce.CommerceCatalogDtos.CreateProductionBatch;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Corrects BraFurries-only production lots without rewriting historical sale
 * cost snapshots or changing remaining stock after a sale (even a cancelled sale).
 * Every update has an immutable before/after audit record.
 */
@Service
public class CommerceProductionBatchCorrectionService {
    private static final String SNAPSHOT_SQL="""
        SELECT JSON_OBJECT(
          'productId',product_id,'supplierId',supplier_id,
          'quantity',quantity,'unitCost',unit_cost,
          'additionalCost',additional_cost,'totalCost',total_cost,
          'producedOn',produced_on,'reference',reference,
          'soldQuantity',sold_quantity
        )
          FROM commerce_production_batches WHERE id=?
        """;
    private final PlatformAuthorizationService authorization;
    private final JdbcTemplate jdbc;

    public CommerceProductionBatchCorrectionService(
        PlatformAuthorizationService authorization,JdbcTemplate jdbc
    ) {
        this.authorization=authorization;
        this.jdbc=jdbc;
    }

    public record Correction(CreateProductionBatch data,String reason) {}

    @Transactional(readOnly=true)
    public List<Map<String,Object>> revisions(Authentication auth,long id) {
        requireBoth(auth);
        return jdbc.queryForList("""
            SELECT revision.id,revision.before_json AS beforeJson,
                   revision.after_json AS afterJson,revision.reason,
                   revision.actor_user_id AS actorUserId,
                   revision.created_at AS createdAt
              FROM commerce_production_batch_revisions revision
             WHERE revision.batch_id=? ORDER BY revision.id DESC LIMIT 100
            """,id);
    }

    @Transactional
    public void correct(Authentication auth,long id,Correction correction) {
        int actor=requireBoth(auth);
        if(correction==null || correction.data()==null)
            throw invalid("Informe os dados do lote");
        CreateProductionBatch d=correction.data();
        String reason=text(correction.reason(),500,"Justificativa",true);
        if(reason.length()<3) throw invalid("A justificativa deve ter pelo menos 3 caracteres");

        List<Map<String,Object>> found=jdbc.queryForList("""
            SELECT product_id AS productId,supplier_id AS supplierId,
                   quantity,unit_cost AS unitCost,additional_cost AS additionalCost,
                   produced_on AS producedOn,reference
              FROM commerce_production_batches WHERE id=? FOR UPDATE
            """,id);
        if(found.isEmpty()) throw missing("Lote de produção não encontrado");
        Map<String,Object> row=found.get(0);
        if(d.productId()==null || d.productId()<1 ||
           d.supplierId()==null || d.supplierId()<1 ||
           d.quantity()==null || d.quantity()<1 || d.quantity()>10000000) {
            throw invalid("Produto, fornecedor e quantidade válidos são obrigatórios");
        }
        if(d.producedOn()==null || d.producedOn().getYear()<2000 ||
           d.producedOn().isAfter(today())) throw invalid("Data de produção inválida");
        BigDecimal unit=money(d.unitCost(),"Custo unitário");
        BigDecimal extra=money(d.additionalCost(),"Custo adicional");
        BigDecimal total=unit.multiply(BigDecimal.valueOf(d.quantity())).add(extra);
        if(total.compareTo(new BigDecimal("9999999999999999.99"))>0)
            throw invalid("Total excede o limite monetário");
        String reference=text(d.reference(),160,"Referência",false);

        boolean changedEconomics=
            number(row.get("productId"))!=d.productId() ||
            number(row.get("supplierId"))!=d.supplierId() ||
            number(row.get("quantity"))!=d.quantity() ||
            ((BigDecimal)row.get("unitCost")).compareTo(unit)!=0 ||
            ((BigDecimal)row.get("additionalCost")).compareTo(extra)!=0 ||
            !date(row.get("producedOn")).equals(d.producedOn());
        boolean changedReference=!java.util.Objects.equals(row.get("reference"),reference);
        if(!changedEconomics && !changedReference)
            throw invalid("Nenhuma alteração encontrada");

        // Locks batch row before querying sale history. Sale registration locks
        // the same batch row before writing sale_item snapshots.
        List<Long> historicalSales=jdbc.queryForList("""
            SELECT id FROM commerce_sale_items
             WHERE production_batch_id=? LIMIT 1 FOR UPDATE
            """,Long.class,id);
        if(changedEconomics && !historicalSales.isEmpty()) {
            throw conflict("Lote utilizado em venda (inclusive cancelada): somente a referência pode ser corrigida");
        }
        if(changedEconomics) {
            if(number(row.get("productId"))!=d.productId() &&
               jdbc.queryForList("""
                   SELECT id FROM commerce_products WHERE id=? AND active=1
                   """,Long.class,d.productId()).isEmpty())
                throw invalid("Produto inativo ou inexistente");
            if(number(row.get("supplierId"))!=d.supplierId() &&
               jdbc.queryForList("""
                   SELECT id FROM commerce_suppliers WHERE id=? AND active=1
                   """,Long.class,d.supplierId()).isEmpty())
                throw invalid("Fornecedor inativo ou inexistente");
        }

        String before=jdbc.queryForObject(SNAPSHOT_SQL,String.class,id);
        if(changedEconomics) {
            jdbc.update("""
                UPDATE commerce_production_batches
                   SET product_id=?,supplier_id=?,quantity=?,unit_cost=?,
                       additional_cost=?,total_cost=?,produced_on=?,reference=?
                 WHERE id=?
                """,d.productId(),d.supplierId(),d.quantity(),unit,extra,total,
                     d.producedOn(),reference,id);
        } else {
            jdbc.update("UPDATE commerce_production_batches SET reference=? WHERE id=?",
                        reference,id);
        }
        String after=jdbc.queryForObject(SNAPSHOT_SQL,String.class,id);
        jdbc.update("""
            INSERT INTO commerce_production_batch_revisions
                (batch_id,before_json,after_json,reason,actor_user_id)
            VALUES (?,?,?,?,?)
            """,id,before,after,reason,actor);
        jdbc.update("""
            INSERT INTO commerce_catalog_audit
                (actor_user_id,entity_type,entity_id,action) VALUES (?,?,?,?)
            """,actor,"PRODUCTION_BATCH",id,"CORRECTED");
    }

    private int requireBoth(Authentication auth) {
        int actor=authorization.require(auth,PlatformPermission.PRODUCTS_MANAGE)
            .user().getId();
        authorization.require(auth,PlatformPermission.SUPPLIERS_MANAGE);
        return actor;
    }
    private long number(Object o) { return ((Number)o).longValue(); }
    private LocalDate date(Object o) {
        if(o instanceof LocalDate l) return l;
        return ((Date)o).toLocalDate();
    }
}
