package com.Brafurries.API.commerce;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import static com.Brafurries.API.commerce.CommerceFinanceSupport.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import jakarta.validation.constraints.NotNull;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Catalog-only variant identity. Combinations and their labels are immutable. */
@Service
public class CommerceVariantService {
    private final PlatformAuthorizationService authorization;
    private final CommerceFinanceSupport support;
    private final JdbcTemplate jdbc;

    public CommerceVariantService(PlatformAuthorizationService authorization,CommerceFinanceSupport support,JdbcTemplate jdbc) {
        this.authorization=authorization;
        this.support=support;
        this.jdbc=jdbc;
    }

    public record Attribute(@NotBlank @Size(max=60) String name,@NotBlank @Size(max=60) String value) {}
    public record Create(@Valid @NotEmpty @Size(max=8) List<Attribute> attributes,
                         BigDecimal suggestedPrice,BigDecimal estimatedUnitCost) {}
    public record Update(@NotNull Boolean active,BigDecimal suggestedPrice,BigDecimal estimatedUnitCost) {}
    public record Selection(Long id,String label) {}

    @Transactional
    public CommerceCatalogDtos.Created create(Authentication auth,long productId,Create input) {
        int actor=authorization.require(auth,PlatformPermission.PRODUCTS_MANAGE).user().getId();
        lockProduct(jdbc,productId,true);
        if(input==null||input.attributes()==null||input.attributes().isEmpty()||input.attributes().size()>8)
            throw invalid("Informe de 1 a 8 atributos da variação");
        BigDecimal price=input.suggestedPrice()==null?null:money(input.suggestedPrice(),"Preço sugerido");
        BigDecimal cost=input.estimatedUnitCost()==null?null:money(input.estimatedUnitCost(),"Custo estimado");
        var configured=jdbc.queryForList("SELECT id,name FROM commerce_product_attributes WHERE product_id=? ORDER BY id FOR UPDATE",productId);
        Set<Long> seen=new HashSet<>();
        TreeMap<Long,Long> ids=new TreeMap<>();
        TreeMap<Long,String> labels=new TreeMap<>();
        for(Attribute pair:input.attributes()) {
            if(pair==null) throw invalid("Atributo inválido");
            String name=text(pair.name(),60,"Atributo",true);
            String value=text(pair.value(),60,"Valor",true);
            var attributes=jdbc.queryForList("SELECT id,name FROM commerce_product_attributes WHERE product_id=? AND name=? FOR UPDATE",productId,name);
            long attributeId;
            if(attributes.isEmpty()) {
                if(!configured.isEmpty()) throw invalid("Use os atributos já definidos para este produto");
                attributeId=support.insert("INSERT INTO commerce_product_attributes(product_id,name) VALUES (?,?)",productId,name);
            } else {
                attributeId=((Number)attributes.get(0).get("id")).longValue();
                name=(String)attributes.get(0).get("name");
            }
            if(!seen.add(attributeId)) throw invalid("Cada atributo deve aparecer apenas uma vez");
            var values=jdbc.queryForList("SELECT id,label FROM commerce_product_attribute_values WHERE product_id=? AND attribute_id=? AND label=? FOR UPDATE",
                productId,attributeId,value);
            long valueId;
            if(values.isEmpty()) {
                valueId=support.insert("INSERT INTO commerce_product_attribute_values(product_id,attribute_id,label) VALUES (?,?,?)",productId,attributeId,value);
            } else {
                valueId=((Number)values.get(0).get("id")).longValue();
                value=(String)values.get(0).get("label");
            }
            ids.put(attributeId,valueId);
            labels.put(attributeId,name+": "+value);
        }
        if(!configured.isEmpty()&&seen.size()!=configured.size()) throw invalid("Preencha todos os atributos do produto");
        String label=text(String.join(" · ",labels.values()),255,"Descrição da variação",true);
        List<String> canonical=new ArrayList<>();
        ids.forEach((a,v)->{canonical.add(a.toString());canonical.add(v.toString());});
        String signature=fingerprint(canonical);
        if(!jdbc.queryForList("SELECT id FROM commerce_product_variants WHERE product_id=? AND combination_sha256=? FOR UPDATE",Long.class,productId,signature).isEmpty())
            throw conflict("Esta combinação já existe; reative-a se necessário");
        long id=support.insert("""
            INSERT INTO commerce_product_variants(product_id,combination_sha256,label,suggested_price,estimated_unit_cost,created_by_user_id)
            VALUES (?,?,?,?,?,?)
            """,productId,signature,label,price,cost,actor);
        ids.forEach((attribute,value)->jdbc.update("""
            INSERT INTO commerce_product_variant_values(product_id,variant_id,attribute_id,value_id) VALUES (?,?,?,?)
            """,productId,id,attribute,value));
        audit(actor,id,"CREATED");
        return new CommerceCatalogDtos.Created(id);
    }

    @Transactional
    public void update(Authentication auth,long productId,long variantId,Update input) {
        int actor=authorization.require(auth,PlatformPermission.PRODUCTS_MANAGE).user().getId();
        if(input==null||input.active()==null) throw invalid("Dados da variação obrigatórios");
        lockProduct(jdbc,productId,false);
        if(jdbc.queryForList("SELECT id FROM commerce_product_variants WHERE product_id=? AND id=? FOR UPDATE",Long.class,productId,variantId).isEmpty())
            throw missing("Variação não encontrada");
        BigDecimal price=input.suggestedPrice()==null?null:money(input.suggestedPrice(),"Preço sugerido");
        BigDecimal cost=input.estimatedUnitCost()==null?null:money(input.estimatedUnitCost(),"Custo estimado");
        jdbc.update("UPDATE commerce_product_variants SET active=?,suggested_price=?,estimated_unit_cost=? WHERE product_id=? AND id=?",
            input.active()?1:0,price,cost,productId,variantId);
        audit(actor,variantId,"UPDATED");
    }

    private void audit(int actor,long variant,String action) {
        jdbc.update("INSERT INTO commerce_catalog_audit(actor_user_id,entity_type,entity_id,action) VALUES (?,'PRODUCT_VARIANT',?,?)",actor,variant,action);
    }

    static void lockProduct(JdbcTemplate jdbc,long productId,boolean active) {
        if(productId<1||jdbc.queryForList("SELECT id FROM commerce_products WHERE id=?"+(active?" AND active=1":"")+" FOR UPDATE",Long.class,productId).isEmpty())
            throw invalid("Produto inativo ou inexistente");
    }

    /** Caller locks product before selecting; no free-text variant accepted. */
    static Selection selection(JdbcTemplate jdbc,Long productId,Long variantId) {
        if(productId==null) {
            if(variantId!=null) throw invalid("Item avulso não aceita uma variação do catálogo");
            return new Selection(null,null);
        }
        if(variantId==null) {
            if(!jdbc.queryForList("SELECT id FROM commerce_product_variants WHERE product_id=? LIMIT 1 FOR UPDATE",Long.class,productId).isEmpty())
                throw invalid("Selecione uma variação cadastrada do produto");
            return new Selection(null,null);
        }
        if(variantId<1) throw invalid("Variação inválida");
        var rows=jdbc.queryForList("SELECT label FROM commerce_product_variants WHERE product_id=? AND id=? AND active=1 FOR UPDATE",productId,variantId);
        if(rows.size()!=1) throw invalid("Variação inativa ou não pertence ao produto");
        return new Selection(variantId,(String)rows.get(0).get("label"));
    }

    static List<Map<String,Object>> attachChoices(JdbcTemplate jdbc,List<Map<String,Object>> products,boolean catalog) {
        if(products.isEmpty()) return products;
        Object[] args=products.stream().map(row->((Number)row.get("id")).longValue()).toArray();
        String placeholders=String.join(",",Collections.nCopies(args.length,"?"));
        String cost=catalog?",COALESCE(v.estimated_unit_cost,p.estimated_unit_cost) AS estimatedUnitCost":"";
        var choices=jdbc.queryForList("""
            SELECT v.id,v.product_id AS productId,v.label,v.active,
                   COALESCE(v.suggested_price,p.suggested_price) AS suggestedPrice
            """+cost+"""
              FROM commerce_product_variants v JOIN commerce_products p ON p.id=v.product_id
             WHERE v.product_id IN (
            """+placeholders+")"+(catalog?"":" AND v.active=1")+" ORDER BY v.id",args);
        Map<Long,List<Map<String,Object>>> grouped=new HashMap<>();
        for(var row:choices) grouped.computeIfAbsent(((Number)row.get("productId")).longValue(),id->new ArrayList<>()).add(row);
        return products.stream().map(row->{
            Map<String,Object> copy=new LinkedHashMap<>(row);
            copy.put("variants",grouped.getOrDefault(((Number)row.get("id")).longValue(),List.of()));
            return copy;
        }).toList();
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(Authentication auth,long productId) {
        authorization.require(auth,PlatformPermission.PRODUCTS_MANAGE);
        if(jdbc.queryForList("SELECT id FROM commerce_products WHERE id=?",Long.class,productId).isEmpty())
            throw missing("Produto não encontrado");
        var variants=jdbc.queryForList("""
            SELECT id,product_id AS productId,label,active,suggested_price AS suggestedPrice,
                   estimated_unit_cost AS estimatedUnitCost
              FROM commerce_product_variants WHERE product_id=? ORDER BY id
            """,productId);
        var attributes=jdbc.queryForList("""
            SELECT rel.variant_id AS variantId,attr.name AS name,val.label AS value
              FROM commerce_product_variant_values rel
              JOIN commerce_product_attributes attr ON attr.id=rel.attribute_id
              JOIN commerce_product_attribute_values val ON val.id=rel.value_id
             WHERE rel.product_id=? ORDER BY rel.attribute_id
            """,productId);
        Map<Long,List<Map<String,Object>>> grouped=new HashMap<>();
        for(var row:attributes) {
            long id=((Number)row.get("variantId")).longValue();
            grouped.computeIfAbsent(id,k->new ArrayList<>()).add(Map.of("name",row.get("name"),"value",row.get("value")));
        }
        return variants.stream().map(row->{
            Map<String,Object> copy=new LinkedHashMap<>(row);
            copy.put("attributes",grouped.getOrDefault(((Number)row.get("id")).longValue(),List.of()));
            return copy;
        }).toList();
    }

    /** Finance view only: orders and confirmed sales are separate populations. */
    @Transactional(readOnly=true)
    public List<Map<String,Object>> statistics(Authentication auth,long productId,LocalDate from,LocalDate to) {
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        LocalDate end=to==null?today():to;
        LocalDate start=from==null?end.minusDays(29):from;
        if(start.getYear()<2000||end.isAfter(today())||start.isAfter(end))
            throw invalid("Período inválido");
        long days=ChronoUnit.DAYS.between(start,end)+1;
        return jdbc.queryForList("""
            SELECT v.id AS variantId,v.label,v.active,
                COALESCE(o.units,0) AS orderedUnits,COALESCE(s.units,0) AS soldUnits,
                COALESCE(b.available,0) AS availableUnits,
                ROUND(COALESCE(s.units,0)/?,2) AS soldPerDay,
                COALESCE(s.gross,0) AS grossAmount,COALESCE(s.cost,0) AS productionCost
              FROM (
                SELECT id,label,active FROM commerce_product_variants WHERE product_id=?
                UNION ALL SELECT NULL,'Sem variação / legado',1
              ) v
              LEFT JOIN (
                SELECT i.variant_id,SUM(i.quantity) units FROM commerce_order_items i
                 JOIN commerce_orders o ON o.id=i.order_id
                 WHERE i.product_id=? AND o.status<>'CANCELLED' AND o.ordered_on BETWEEN ? AND ? GROUP BY i.variant_id
              ) o ON o.variant_id <=> v.id
              LEFT JOIN (
                SELECT i.variant_id,SUM(i.quantity) units,SUM(i.line_gross_amount) gross,
                       SUM(i.line_production_cost_snapshot) cost
                  FROM commerce_sale_items i JOIN commerce_sales s ON s.id=i.sale_id
                 WHERE i.product_id=? AND s.status='CONFIRMED' AND s.sold_on BETWEEN ? AND ? GROUP BY i.variant_id
              ) s ON s.variant_id <=> v.id
              LEFT JOIN (
                SELECT variant_id,SUM(quantity-sold_quantity) available
                  FROM commerce_production_batches WHERE product_id=? GROUP BY variant_id
              ) b ON b.variant_id <=> v.id
             ORDER BY soldUnits DESC,v.id
            """,days,productId,productId,start,end,productId,start,end,productId);
    }
}
