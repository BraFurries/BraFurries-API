package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceFinanceSupport.*;
import static com.Brafurries.API.commerce.CommerceFinanceDtos.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Physical sales V1. Does not touch Community store or virtual currency.
 * Stock and royalty snapshots are created atomically with every confirmed sale.
 */
@Service
public class CommerceSalesService {
    private final CommerceAuthorizationService authorization;
    private final CommerceFinanceSupport support;
    private final JdbcTemplate jdbc;

    public CommerceSalesService(
        CommerceAuthorizationService authorization,
        CommerceFinanceSupport support,
        JdbcTemplate jdbc
    ) {
        this.authorization=authorization;
        this.support=support;
        this.jdbc=jdbc;
    }

    private record Batch(long id,long productId,int quantity,BigDecimal totalCost) {}
    private record Rights(
        long artworkId,int artistId,Long contractId,String method,
        BigDecimal rate,BigDecimal fixedUnit,BigDecimal allocation,
        LocalDate validFrom,LocalDate validUntil
    ) {}
    private record LineSnapshot(
        Batch batch,int quantity,BigDecimal price,BigDecimal lineGross,BigDecimal lineCost,
        List<Rights> rights
    ) {}

    @Transactional
    public Created createSale(Authentication auth,CreateSale request) {
        int actor=authorization.require(auth,CommercePermission.SALES_MANAGE).user().getId();
        if (request==null || request.items()==null || request.items().isEmpty()
                || request.items().size()>50) throw invalid("Venda precisa de 1 a 50 itens");
        if (request.soldOn()==null || request.soldOn().isAfter(today())
                || request.soldOn().getYear()<2000) {
            throw invalid("Data da venda inválida ou futura");
        }
        String key=requestId(request.requestKey());
        String channel=request.channel();
        if (!Set.of("EVENT","DIRECT","ONLINE","OTHER").contains(channel)) {
            throw invalid("Canal de venda inválido");
        }
        String reference=text(request.externalReference(),120,"Referência",false);
        String buyer=text(request.buyerLabel(),160,"Identificação do comprador",false);
        String notes=text(request.notes(),5000,"Observações",false);

        // Normalize inputs BEFORE hashing. A retry with changed values must be
        // rejected even when the client reuses the same UUID.
        TreeMap<Long,SaleLine> lines=new TreeMap<>();
        List<String> canonical=new ArrayList<>(List.of(
            channel,request.soldOn().toString()
        ));
        canonical.add(reference);
        canonical.add(buyer);
        canonical.add(notes);
        for (SaleLine input:request.items()) {
            if (input==null || input.batchId()==null || input.batchId()<1
                || input.quantity()==null || input.quantity()<1 || input.quantity()>1000000) {
                throw invalid("Item de venda ou quantidade inválida");
            }
            BigDecimal price=money(input.unitPrice(),"Preço unitário");
            if (lines.putIfAbsent(input.batchId(),
                new SaleLine(input.batchId(),input.quantity(),price))!=null) {
                throw invalid("O mesmo lote não pode aparecer duas vezes na venda");
            }
        }
        lines.values().forEach(x->{
            canonical.add(x.batchId().toString());
            canonical.add(x.quantity().toString());
            canonical.add(x.unitPrice().toPlainString());
        });
        String fingerprint=fingerprint(canonical);
        List<Map<String,Object>> prior=jdbc.queryForList("""
            SELECT id,request_sha256 FROM commerce_sales WHERE request_key=?
            """,key);
        if (!prior.isEmpty()) {
            if (!fingerprint.equals(prior.get(0).get("request_sha256"))) {
                throw conflict("Este identificador de venda já foi usado com outros dados");
            }
            return new Created(((Number)prior.get(0).get("id")).longValue());
        }

        // Lock products before reading artwork links; the catalog mutator also
        // locks the product, making royalty allocations stable for this sale.
        TreeMap<Long,Long> batchProducts=new TreeMap<>();
        for (Long batchId:lines.keySet()) {
            List<Long> productIds=jdbc.queryForList("""
                SELECT product_id FROM commerce_production_batches WHERE id=?
                """,Long.class,batchId);
            if (productIds.isEmpty()) throw missing("Lote de produção "+batchId+" não encontrado");
            batchProducts.put(batchId,productIds.get(0));
        }
        for (Long productId:new TreeSet<>(batchProducts.values())) {
            List<Long> found=jdbc.queryForList("""
                SELECT id FROM commerce_products WHERE id=? AND active=1 FOR UPDATE
                """,Long.class,productId);
            if (found.isEmpty()) throw conflict("Produto desativado ou inexistente: "+productId);
        }

        // Lock artworks in ascending order, protecting against a contract being
        // signed/terminated concurrently with a sale.
        TreeSet<Long> artworkIds=new TreeSet<>();
        for (Long productId:new TreeSet<>(batchProducts.values())) {
            artworkIds.addAll(jdbc.queryForList("""
                SELECT artwork_id FROM commerce_product_artworks WHERE product_id=?
                """,Long.class,productId));
        }
        for (Long artworkId:artworkIds) {
            List<Long> locked=jdbc.queryForList("""
                SELECT id FROM commerce_artworks WHERE id=? FOR UPDATE
                """,Long.class,artworkId);
            if (locked.isEmpty()) throw conflict("Arte vinculada ao produto não encontrada");
        }

        Map<Long,List<Rights>> rightsByProduct=new TreeMap<>();
        TreeSet<Integer> royaltyArtists=new TreeSet<>();
        for (Long productId:new TreeSet<>(batchProducts.values())) {
            List<Rights> rights=jdbc.query("""
                SELECT pa.artwork_id, a.artist_user_id, pa.royalty_allocation_percent,
                       c.id AS contract_id,c.royalty_method_snapshot,
                       c.royalty_percent_snapshot,c.royalty_fixed_snapshot,
                       c.effective_from,c.effective_until
                  FROM commerce_product_artworks pa
                  JOIN commerce_artworks a ON a.id=pa.artwork_id
                  LEFT JOIN commerce_artwork_contracts c
                    ON c.artwork_id=pa.artwork_id AND c.status='ACTIVE'
                 WHERE pa.product_id=? ORDER BY pa.artwork_id
                """,(rs,index)->new Rights(
                    rs.getLong("artwork_id"),rs.getInt("artist_user_id"),
                    (Long)rs.getObject("contract_id",Long.class),
                    rs.getString("royalty_method_snapshot"),
                    rs.getBigDecimal("royalty_percent_snapshot"),
                    rs.getBigDecimal("royalty_fixed_snapshot"),
                    rs.getBigDecimal("royalty_allocation_percent"),
                    rs.getObject("effective_from",LocalDate.class),
                    rs.getObject("effective_until",LocalDate.class)
                ),productId);
            BigDecimal sum=BigDecimal.ZERO;
            for (Rights right:rights) {
                if (right.contractId()==null || right.method()==null) {
                    throw conflict("Arte sem contrato assinado ativo. Não é possível vender este produto.");
                }
                if (right.validFrom().isAfter(request.soldOn())
                    || (right.validUntil()!=null && right.validUntil().isBefore(request.soldOn()))) {
                    throw conflict("Contrato de arte fora da vigência na data da venda");
                }
                if (!Set.of("NONE","PERCENT_GROSS","FIXED_UNIT").contains(right.method())) {
                    throw conflict("Repasse "+right.method()+
                        " exige regra financeira adicional antes de registrar esta venda");
                }
                if (!"NONE".equals(right.method())) {
                    if (right.allocation()==null) {
                        throw conflict("Defina o rateio da arte antes de vender o produto");
                    }
                    if (right.allocation().signum()<0
                        || right.allocation().compareTo(BigDecimal.valueOf(100))>0) {
                        throw conflict("Rateio contratual inválido");
                    }
                    sum=sum.add(right.allocation());
                    royaltyArtists.add(right.artistId());
                }
            }
            if (sum.compareTo(BigDecimal.valueOf(100))>0) {
                throw conflict("O rateio total das artes do produto ultrapassa 100%");
            }
            rightsByProduct.put(productId,rights);
        }

        int year=request.soldOn().getYear();
        int month=request.soldOn().getMonthValue();
        for (Integer artist:royaltyArtists) {
            support.lockPeriod(artist,year,month);
            support.requirePeriodNotSettled(artist,year,month);
        }

        List<LineSnapshot> snapshots=new ArrayList<>();
        BigDecimal gross=BigDecimal.ZERO;
        for (SaleLine item:lines.values()) {
            long productId=batchProducts.get(item.batchId());
            List<Batch> matched=jdbc.query("""
                SELECT id,product_id,quantity,total_cost
                  FROM commerce_production_batches WHERE id=? FOR UPDATE
                """,(rs,index)->new Batch(
                    rs.getLong("id"),rs.getLong("product_id"),rs.getInt("quantity"),
                    rs.getBigDecimal("total_cost")
                ),item.batchId());
            if (matched.isEmpty() || matched.get(0).productId()!=productId) {
                throw conflict("Lote de produção alterado durante a operação");
            }
            Batch batch=matched.get(0);
            Long sold=jdbc.queryForObject("""
                SELECT COALESCE(SUM(si.quantity),0)
                  FROM commerce_sale_items si
                  JOIN commerce_sales sale ON sale.id=si.sale_id
                 WHERE si.production_batch_id=? AND sale.status='CONFIRMED'
                """,Long.class,batch.id());
            long available=batch.quantity()-(sold==null?0:sold);
            if (available<item.quantity()) {
                throw conflict("Estoque insuficiente no lote "+batch.id()+
                    ". Disponível: "+Math.max(0,available));
            }
            BigDecimal lineGross=money(
                item.unitPrice().multiply(BigDecimal.valueOf(item.quantity())),
                "Valor da linha"
            );
            BigDecimal cost=batch.totalCost()
                .multiply(BigDecimal.valueOf(item.quantity()))
                .divide(BigDecimal.valueOf(batch.quantity()),2,RoundingMode.HALF_UP);
            gross=money(gross.add(lineGross),"Total da venda");
            snapshots.add(new LineSnapshot(batch,item.quantity(),item.unitPrice(),
                lineGross,cost,rightsByProduct.getOrDefault(productId,List.of())));
        }

        long saleId;
        try {
            saleId=support.insert("""
                INSERT INTO commerce_sales
                    (request_key,request_sha256,channel,external_reference,buyer_label,
                     sold_on,status,gross_amount,notes,created_by_user_id)
                VALUES (?,?,?,?,?,?,'CONFIRMED',?,?,?)
                """,key,fingerprint,channel,reference,buyer,request.soldOn(),
                gross,notes,actor);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent duplicate request: return a conflict; retrying with the
            // same UUID then returns the existing sale. Never duplicate effects.
            throw conflict("Venda duplicada: consulte a operação e repita com o mesmo UUID");
        }
        for (LineSnapshot line:snapshots) {
            long itemId=support.insert("""
                INSERT INTO commerce_sale_items
                    (sale_id,product_id,production_batch_id,quantity,
                     unit_price_snapshot,line_gross_amount,line_production_cost_snapshot)
                VALUES (?,?,?,?,?,?,?)
                """,saleId,line.batch().productId(),line.batch().id(),
                line.quantity(),line.price(),line.lineGross(),line.lineCost());
            for (Rights right:line.rights()) {
                if ("NONE".equals(right.method())) continue;
                BigDecimal amount=calculateRoyalty(line,right);
                support.insert("""
                    INSERT INTO commerce_royalty_accruals
                        (sale_item_id,artwork_id,contract_id,artist_user_id,
                         period_year,period_month,event_kind,royalty_method_snapshot,
                         contract_rate_snapshot,fixed_unit_snapshot,
                         allocation_percent_snapshot,basis_amount_snapshot,amount)
                    VALUES (?,?,?,?,?,?,'SALE',?,?,?,?,?,?)
                    """,itemId,right.artworkId(),right.contractId(),right.artistId(),
                    year,month,right.method(),right.rate(),right.fixedUnit(),
                    right.allocation(),line.lineGross(),amount);
            }
        }
        support.audit(actor,"SALE",saleId,"CONFIRMED");
        return new Created(saleId);
    }

    static BigDecimal calculateRoyalty(LineSnapshot line,Rights right) {
        return switch(right.method()) {
            case "PERCENT_GROSS" -> CommerceRoyaltyMath.gross(
                line.lineGross(),right.rate(),right.allocation());
            case "FIXED_UNIT" -> CommerceRoyaltyMath.fixedUnit(
                right.fixedUnit(),line.quantity(),right.allocation());
            default -> throw conflict("Método de royalty não suportado");
        };
    }

    @Transactional
    public void cancelSale(Authentication auth,long saleId,String reason) {
        int actor=authorization.require(auth,CommercePermission.SALES_MANAGE).user().getId();
        String reasonText=text(reason,500,"Motivo do cancelamento",true);
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT id,status FROM commerce_sales WHERE id=? FOR UPDATE
            """,saleId);
        if (rows.isEmpty()) throw missing("Venda não encontrada");
        if ("CANCELLED".equals(rows.get(0).get("status"))) return;

        List<Map<String,Object>> periods=jdbc.queryForList("""
            SELECT DISTINCT accrual.artist_user_id,accrual.period_year,accrual.period_month
              FROM commerce_royalty_accruals accrual
              JOIN commerce_sale_items item ON item.id=accrual.sale_item_id
             WHERE item.sale_id=?
             ORDER BY accrual.artist_user_id,accrual.period_year,accrual.period_month
            """,saleId);
        for (Map<String,Object> period:periods) {
            int artist=((Number)period.get("artist_user_id")).intValue();
            int year=((Number)period.get("period_year")).intValue();
            int month=((Number)period.get("period_month")).intValue();
            support.lockPeriod(artist,year,month);
            support.requirePeriodNotSettled(artist,year,month);
        }

        // Every sale/cancellation locks its batches, so concurrent transactions
        // cannot oversell stock immediately after a cancellation.
        for (Long batchId:jdbc.queryForList("""
            SELECT production_batch_id FROM commerce_sale_items
             WHERE sale_id=? ORDER BY production_batch_id
            """,Long.class,saleId)) {
            jdbc.queryForList("""
                SELECT id FROM commerce_production_batches WHERE id=? FOR UPDATE
                """,Long.class,batchId);
        }
        jdbc.update("""
            INSERT INTO commerce_royalty_accruals
                (sale_item_id,artwork_id,contract_id,artist_user_id,period_year,period_month,
                 event_kind,original_accrual_id,royalty_method_snapshot,
                 contract_rate_snapshot,fixed_unit_snapshot,
                 allocation_percent_snapshot,basis_amount_snapshot,amount)
            SELECT original.sale_item_id,original.artwork_id,original.contract_id,
                   original.artist_user_id,original.period_year,original.period_month,
                   'REVERSAL',original.id,original.royalty_method_snapshot,
                   original.contract_rate_snapshot,original.fixed_unit_snapshot,
                   original.allocation_percent_snapshot,original.basis_amount_snapshot,
                   -original.amount
              FROM commerce_royalty_accruals original
              JOIN commerce_sale_items item ON item.id=original.sale_item_id
             WHERE item.sale_id=? AND original.event_kind='SALE'
            """,saleId);
        jdbc.update("""
            UPDATE commerce_sales
               SET status='CANCELLED',cancelled_by_user_id=?,
                   cancelled_at=CURRENT_TIMESTAMP(6),cancellation_reason=?
             WHERE id=? AND status='CONFIRMED'
            """,actor,reasonText,saleId);
        support.audit(actor,"SALE",saleId,"CANCELLED");
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> listSales(Authentication auth) {
        var access=authorization.resolve(auth).access();
        if (!access.has(CommercePermission.SALES_MANAGE)
            && !access.has(CommercePermission.FINANCE_VIEW)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Sem acesso ao histórico de vendas");
        }
        return jdbc.queryForList("""
            SELECT id,channel,external_reference AS externalReference,
                   buyer_label AS buyerLabel,sold_on AS soldOn,status,
                   gross_amount AS grossAmount,created_at AS createdAt,
                   cancellation_reason AS cancellationReason
              FROM commerce_sales ORDER BY id DESC LIMIT 500
            """);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> saleItems(Authentication auth,long saleId) {
        var access=authorization.resolve(auth).access();
        if (!access.has(CommercePermission.SALES_MANAGE)
            && !access.has(CommercePermission.FINANCE_VIEW)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Sem acesso à venda");
        }
        return jdbc.queryForList("""
            SELECT i.id,i.product_id AS productId,p.name AS productName,
                   i.production_batch_id AS batchId,i.quantity,
                   i.unit_price_snapshot AS unitPrice,i.line_gross_amount AS lineGross
              FROM commerce_sale_items i
              JOIN commerce_products p ON p.id=i.product_id
             WHERE i.sale_id=? ORDER BY i.id
            """,saleId);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> royalties(Authentication auth,int year,int month) {
        authorization.require(auth,CommercePermission.FINANCE_VIEW);
        if (year<2000 || year>2200 || month<1 || month>12) throw invalid("Mês inválido");
        return jdbc.queryForList("""
            SELECT acc.id,acc.artist_user_id AS artistUserId,
                   COALESCE(u.display_name,u.username,
                       CONCAT('Usuário ',u.id)) AS artistName,
                   acc.artwork_id AS artworkId,acc.contract_id AS contractId,
                   acc.period_year AS periodYear,acc.period_month AS periodMonth,
                   acc.event_kind AS eventKind,
                   acc.royalty_method_snapshot AS royaltyMethod,
                   acc.contract_rate_snapshot AS contractRate,
                   acc.fixed_unit_snapshot AS fixedUnit,
                   acc.allocation_percent_snapshot AS allocation,
                   acc.basis_amount_snapshot AS basisAmount,acc.amount,
                   i.sale_id AS saleId,acc.created_at AS createdAt
              FROM commerce_royalty_accruals acc
              JOIN users u ON u.id=acc.artist_user_id
              JOIN commerce_sale_items i ON i.id=acc.sale_item_id
             WHERE acc.period_year=? AND acc.period_month=?
             ORDER BY acc.artist_user_id,acc.id LIMIT 2000
            """,year,month);
    }
}
