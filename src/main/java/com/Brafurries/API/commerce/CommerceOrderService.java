package com.Brafurries.API.commerce;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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

import static com.Brafurries.API.commerce.CommerceFinanceSupport.*;
import static com.Brafurries.API.commerce.CommerceOrderDtos.*;

/**
 * Operational preorders, deliberately separate from the financial sales ledger.
 * No order operation affects batch stock, commerce_sales, royalty accruals or cash.
 */
@Service
public class CommerceOrderService {
    private static final Set<String> CHANNELS=Set.of("EVENT","DIRECT","ONLINE","OTHER");
    private static final Set<String> PAYMENT_METHODS=Set.of(
        "PIX","CASH","CARD_DEBIT","CARD_CREDIT","BANK_TRANSFER","BANK_SLIP","OTHER");

    private final PlatformAuthorizationService authorization;
    private final CommerceFinanceSupport support;
    private final JdbcTemplate jdbc;

    public CommerceOrderService(PlatformAuthorizationService authorization,
                                CommerceFinanceSupport support,JdbcTemplate jdbc) {
        this.authorization=authorization;
        this.support=support;
        this.jdbc=jdbc;
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> productChoices(Authentication auth) {
        require(auth);
        // Only sellable product name and suggested price; never supplier costs.
        return jdbc.queryForList("""
            SELECT id,name,suggested_price AS suggestedPrice
              FROM commerce_products WHERE active=1 ORDER BY name,id LIMIT 500
            """);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(Authentication auth) {
        require(auth);
        return jdbc.queryForList("""
            SELECT o.id,o.buyer_label AS buyerLabel,o.channel,
                   o.external_reference AS externalReference,
                   o.ordered_on AS orderedOn,o.expected_delivery_on AS expectedDeliveryOn,
                   o.status,o.total_amount AS totalAmount,o.notes,
                   o.created_at AS createdAt,o.ready_at AS readyAt,
                   o.delivered_on AS deliveredOn,o.cancellation_reason AS cancellationReason,
                   (SELECT COALESCE(SUM(p.amount),0)
                      FROM commerce_order_payment_events p WHERE p.order_id=o.id) AS paidAmount
              FROM commerce_orders o ORDER BY o.id DESC LIMIT 500
            """);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> items(Authentication auth,long orderId) {
        require(auth);
        requireOrder(orderId,false);
        return jdbc.queryForList("""
            SELECT id,product_id AS productId,item_label AS label,
                   size_label AS size,quantity,unit_price_snapshot AS unitPrice,
                   line_amount AS lineAmount
              FROM commerce_order_items
             WHERE order_id=? ORDER BY id
            """,orderId);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> payments(Authentication auth,long orderId) {
        require(auth);
        requireOrder(orderId,false);
        return jdbc.queryForList("""
            SELECT id,event_kind AS eventKind,original_event_id AS originalEventId,
                   amount,received_on AS receivedOn,payment_method AS paymentMethod,
                   external_reference AS externalReference,notes,
                   recorded_by_user_id AS recordedByUserId,created_at AS createdAt
              FROM commerce_order_payment_events
             WHERE order_id=? ORDER BY id DESC LIMIT 500
            """,orderId);
    }

    private record ValidatedLine(Long productId,String label,String size,int quantity,
                                 BigDecimal unitPrice,BigDecimal lineTotal) {}

    @Transactional
    public Created create(Authentication auth,Create input) {
        int actor=require(auth);
        if(input==null||input.items()==null||input.items().isEmpty()||input.items().size()>50)
            throw invalid("Encomenda precisa ter entre 1 e 50 itens");
        String key=requestId(input.requestKey());
        String buyer=text(input.buyerLabel(),160,"Comprador",true);
        String channel=input.channel();
        if(channel==null||!CHANNELS.contains(channel)) throw invalid("Canal de encomenda inválido");
        String reference=text(input.externalReference(),120,"Referência",false);
        String notes=text(input.notes(),5000,"Observações",false);
        LocalDate ordered=input.orderedOn(),due=input.expectedDeliveryOn();
        if(ordered==null||ordered.getYear()<2000||ordered.isAfter(today())||
           (due!=null&&due.isBefore(ordered))) throw invalid("Datas da encomenda inválidas");
        BigDecimal total=BigDecimal.ZERO;
        List<ValidatedLine> lines=new ArrayList<>();
        List<String> signature=new ArrayList<>(List.of(
            buyer,channel,ordered.toString()));
        signature.add(reference);
        signature.add(due==null?null:due.toString());
        signature.add(notes);
        for(Line line:input.items()) {
            if(line==null||line.quantity()==null||line.quantity()<1||line.quantity()>100000)
                throw invalid("Quantidade inválida");
            String label=text(line.label(),180,"Produto da encomenda",true);
            String size=text(line.size(),64,"Tamanho",false);
            Long product=line.productId();
            if(product!=null) {
                if(product<1) throw invalid("Produto do catálogo inválido");
            }
            BigDecimal unit=money(line.unitPrice(),"Preço unitário");
            BigDecimal amount=money(unit.multiply(BigDecimal.valueOf(line.quantity())),
                "Total do item");
            total=money(total.add(amount),"Total da encomenda");
            lines.add(new ValidatedLine(product,label,size,line.quantity(),unit,amount));
            signature.add(product==null?null:product.toString());
            signature.add(label);signature.add(size);
            signature.add(Integer.toString(line.quantity()));
            signature.add(unit.toPlainString());
        }
        String hash=fingerprint(signature);
        var existing=findByRequestKey(key,false);
        if(!existing.isEmpty()) return replay(existing.get(0),hash);

        // An idempotent retry still works when a catalog item is retired.
        // Validate active catalog products only before the FIRST insertion.
        for(ValidatedLine line:lines) {
            if(line.productId()!=null && jdbc.queryForList(
                "SELECT id FROM commerce_products WHERE id=? AND active=1",
                Long.class,line.productId()).size()!=1) {
                throw invalid("Produto do catálogo inválido");
            }
        }
        long id;
        try {
            id=support.insert("""
                INSERT INTO commerce_orders
                    (request_key,request_sha256,channel,external_reference,buyer_label,
                     ordered_on,expected_delivery_on,total_amount,notes,created_by_user_id)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,key,hash,channel,reference,buyer,ordered,due,total,notes,actor);
        } catch(DataIntegrityViolationException ex) {
            // Current read bypasses an older REPEATABLE READ snapshot after
            // waiting for a concurrently committed unique request key.
            var concurrent=findByRequestKey(key,true);
            if(!concurrent.isEmpty()) return replay(concurrent.get(0),hash);
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Referência da encomenda já registrada ou dados inválidos",ex);
        }
        // Never swallow item or audit insert errors as an idempotent replay.
        // Such errors MUST roll back the whole order.
        for(ValidatedLine line:lines) {
            jdbc.update("""
                INSERT INTO commerce_order_items
                    (order_id,product_id,item_label,size_label,quantity,
                     unit_price_snapshot,line_amount)
                VALUES (?,?,?,?,?,?,?)
                """,id,line.productId(),line.label(),line.size(),line.quantity(),
                line.unitPrice(),line.lineTotal());
        }
        support.audit(actor,"ORDER",id,"CREATED");
        return new Created(id);
    }

    @Transactional
    public void transition(Authentication auth,long orderId,Transition input) {
        int actor=require(auth);
        if(input==null) throw invalid("Informe o status");
        Map<String,Object> order=requireOrder(orderId,true);
        String current=(String)order.get("status");
        String next=input.status();
        if("READY".equals(next)&&"OPEN".equals(current)) {
            jdbc.update("""
                UPDATE commerce_orders SET status='READY',ready_at=CURRENT_TIMESTAMP(6),
                    updated_by_user_id=? WHERE id=?
                """,actor,orderId);
        } else if("DELIVERED".equals(next)&&"READY".equals(current)) {
            LocalDate delivery=input.deliveredOn();
            if(delivery==null||delivery.isAfter(today())||delivery.getYear()<2000) {
                throw invalid("Informe a data real de entrega");
            }
            jdbc.update("""
                UPDATE commerce_orders SET status='DELIVERED',delivered_on=?,
                    updated_by_user_id=? WHERE id=?
                """,delivery,actor,orderId);
        } else if("CANCELLED".equals(next)&&Set.of("OPEN","READY").contains(current)) {
            String reason=text(input.reason(),500,"Justificativa de cancelamento",true);
            if(paidAmount(orderId).signum()>0) {
                throw conflict("Estorne os recebimentos da encomenda antes do cancelamento");
            }
            jdbc.update("""
                UPDATE commerce_orders SET status='CANCELLED',
                    cancelled_at=CURRENT_TIMESTAMP(6),cancellation_reason=?,
                    updated_by_user_id=? WHERE id=?
                """,reason,actor,orderId);
        } else {
            throw conflict("Transição inválida: "+current+" -> "+next);
        }
        support.audit(actor,"ORDER",orderId,next);
    }

    @Transactional
    public Created receive(Authentication auth,long orderId,Receive input) {
        int actor=require(auth);
        Map<String,Object> order=requireOrder(orderId,true);
        if(input==null) throw invalid("Pagamento obrigatório");
        String key=requestId(input.requestKey());
        BigDecimal amount=money(input.amount(),"Valor recebido");
        if(amount.signum()<=0) throw invalid("Valor recebido deve ser positivo");
        String method=paymentMethod(input.paymentMethod());
        LocalDate date=validReceiptDate(input.receivedOn());
        String ref=text(input.externalReference(),120,"Referência",false);
        String notes=text(input.notes(),500,"Observações",false);
        String hash=fingerprint(List.of(
            Long.toString(orderId),amount.toPlainString(),date.toString(),method,
            ref==null?"":ref,notes==null?"":notes,"RECEIPT"));
        var existing=findPaymentKey(key,false);
        if(!existing.isEmpty()) return replay(existing.get(0),hash);
        if("CANCELLED".equals(order.get("status"))) throw conflict("Encomenda cancelada");
        BigDecimal total=(BigDecimal)order.get("totalAmount");
        BigDecimal paid=paidAmount(orderId);
        if(paid.add(amount).compareTo(total)>0)
            throw invalid("Pagamento não pode ultrapassar o total da encomenda");
        long id;
        try {
            id=support.insert("""
                INSERT INTO commerce_order_payment_events
                    (order_id,request_key,request_sha256,event_kind,amount,
                     received_on,payment_method,external_reference,notes,recorded_by_user_id)
                VALUES (?,?,?,'RECEIPT',?,?,?,?,?,?)
                """,orderId,key,hash,amount,date,method,ref,notes,actor);
        } catch(DataIntegrityViolationException ex) {
            var concurrent=findPaymentKey(key,true);
            if(!concurrent.isEmpty()) return replay(concurrent.get(0),hash);
            throw conflict("Registro de pagamento em conflito");
        }
        support.audit(actor,"ORDER_PAYMENT",id,"RECEIVED");
        return new Created(id);
    }

    @Transactional
    public Created reverse(Authentication auth,long orderId,long receiptId,Reverse input) {
        int actor=require(auth);
        requireOrder(orderId,true);
        if(input==null) throw invalid("Estorno obrigatório");
        String key=requestId(input.requestKey());
        LocalDate date=validReceiptDate(input.reversedOn());
        String reason=text(input.reason(),500,"Motivo do estorno",true);
        var origin=jdbc.queryForList("""
            SELECT id,event_kind AS eventKind,amount,payment_method AS paymentMethod
              FROM commerce_order_payment_events
             WHERE id=? AND order_id=? FOR UPDATE
            """,receiptId,orderId);
        if(origin.size()!=1||!"RECEIPT".equals(origin.get(0).get("eventKind"))) {
            throw missing("Recebimento original não encontrado");
        }
        BigDecimal amount=((BigDecimal)origin.get(0).get("amount")).negate();
        String method=(String)origin.get(0).get("paymentMethod");
        String hash=fingerprint(List.of(Long.toString(orderId),Long.toString(receiptId),
            amount.toPlainString(),date.toString(),reason,"REVERSAL"));
        var existing=findPaymentKey(key,false);
        if(!existing.isEmpty()) return replay(existing.get(0),hash);
        long id;
        try {
            id=support.insert("""
                INSERT INTO commerce_order_payment_events
                    (order_id,request_key,request_sha256,event_kind,original_event_id,
                     amount,received_on,payment_method,notes,recorded_by_user_id)
                VALUES (?,?,?,'REVERSAL',?,?,?,?,?,?)
                """,orderId,key,hash,receiptId,amount,date,method,reason,actor);
        } catch(DataIntegrityViolationException ex) {
            var concurrent=findPaymentKey(key,true);
            if(!concurrent.isEmpty()) return replay(concurrent.get(0),hash);
            throw conflict("Este recebimento já foi estornado");
        }
        support.audit(actor,"ORDER_PAYMENT",id,"REVERSED");
        return new Created(id);
    }

    private int require(Authentication auth) {
        return authorization.require(auth,PlatformPermission.SALES_MANAGE).user().getId();
    }
    private Map<String,Object> requireOrder(long id,boolean lock) {
        if(id<1) throw missing("Encomenda não encontrada");
        var rows=jdbc.queryForList("""
            SELECT id,status,total_amount AS totalAmount FROM commerce_orders WHERE id=?
            """+(lock?" FOR UPDATE":""),id);
        if(rows.size()!=1) throw missing("Encomenda não encontrada");
        return rows.get(0);
    }
    private List<Map<String,Object>> findByRequestKey(String key,boolean locking) {
        return jdbc.queryForList("""
            SELECT id,request_sha256 AS requestHash FROM commerce_orders WHERE request_key=?
            """+(locking?" FOR UPDATE":""),key);
    }
    private List<Map<String,Object>> findPaymentKey(String key,boolean locking) {
        return jdbc.queryForList("""
            SELECT id,request_sha256 AS requestHash
              FROM commerce_order_payment_events WHERE request_key=?
            """+(locking?" FOR UPDATE":""),key);
    }
    private Created replay(Map<String,Object> row,String hash) {
        if(!hash.equals(row.get("requestHash")))
            throw conflict("Chave de idempotência já utilizada com dados diferentes");
        return new Created(((Number)row.get("id")).longValue());
    }
    private BigDecimal paidAmount(long orderId) {
        return jdbc.queryForObject("""
            SELECT COALESCE(SUM(amount),0)
              FROM commerce_order_payment_events WHERE order_id=?
            """,BigDecimal.class,orderId);
    }
    private LocalDate validReceiptDate(LocalDate date) {
        if(date==null||date.getYear()<2000||date.isAfter(today()))
            throw invalid("Data de pagamento inválida");
        return date;
    }
    private String paymentMethod(String method) {
        if(method==null||!PAYMENT_METHODS.contains(method)) throw invalid("Forma de pagamento inválida");
        return method;
    }
}
