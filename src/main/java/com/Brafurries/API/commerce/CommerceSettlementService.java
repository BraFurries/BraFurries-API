package com.Brafurries.API.commerce;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;

import static com.Brafurries.API.commerce.CommerceFinanceSupport.*;
import static com.Brafurries.API.commerce.CommerceFinanceDtos.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Monthly royalty statements are frozen per artist and São Paulo business month.
 * Payments are manual ledger entries; this service NEVER initiates transfers.
 */
@Service
public class CommerceSettlementService {
    private final PlatformAuthorizationService authorization;
    private final CommerceFinanceSupport support;
    private final JdbcTemplate jdbc;

    public CommerceSettlementService(
        PlatformAuthorizationService authorization,
        CommerceFinanceSupport support,
        JdbcTemplate jdbc
    ) {
        this.authorization=authorization;
        this.support=support;
        this.jdbc=jdbc;
    }

    private record Statement(long id,int artistId,int year,int month,
                             String status,BigDecimal total) {}

    @Transactional
    public Created createDraft(Authentication auth,CreateStatement input) {
        int actor=authorization.require(auth,PlatformPermission.SETTLEMENTS_MANAGE).user().getId();
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        if (input==null || input.artistUserId()==null ||
            input.year()==null || input.month()==null) {
            throw invalid("Artista e período obrigatórios");
        }
        int year=input.year(), month=input.month(), artist=input.artistUserId();
        if (artist<1 || year<2000 || year>2200 || month<1 || month>12) {
            throw invalid("Artista ou período inválido");
        }
        if (!YearMonth.of(year,month).isBefore(currentMonth())) {
            throw conflict("Só é possível fechar um mês já encerrado");
        }
        support.lockPeriod(artist,year,month);
        List<Map<String,Object>> existing=jdbc.queryForList("""
            SELECT id,status FROM commerce_settlements
             WHERE artist_user_id=? AND period_year=? AND period_month=?
             FOR UPDATE
            """,artist,year,month);
        if (!existing.isEmpty()) {
            return new Created(((Number)existing.get(0).get("id")).longValue());
        }
        List<Map<String,Object>> events=jdbc.queryForList("""
            SELECT id,amount FROM commerce_royalty_accruals
             WHERE artist_user_id=? AND period_year=? AND period_month=?
             ORDER BY id FOR UPDATE
            """,artist,year,month);
        if (events.isEmpty()) throw conflict("Nenhum royalty apurado para o artista neste mês");
        BigDecimal total=BigDecimal.ZERO;
        for (Map<String,Object> event:events) {
            total=total.add((BigDecimal)event.get("amount"));
        }
        if (total.signum()<0) {
            throw conflict("Saldo negativo requer análise antes do fechamento");
        }
        total=signedMoney(total,"Total do fechamento");
        long id;
        try {
            id=support.insert("""
                INSERT INTO commerce_settlements
                    (artist_user_id,period_year,period_month,status,
                     total_amount,created_by_user_id)
                VALUES (?, ?, ?, 'DRAFT', ?, ?)
                """,artist,year,month,total,actor);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("Este artista já possui demonstrativo para o mês");
        }
        for (Map<String,Object> event:events) {
            jdbc.update("""
                INSERT INTO commerce_settlement_items
                    (settlement_id,accrual_id,amount_snapshot) VALUES (?,?,?)
                """,id,((Number)event.get("id")).longValue(),event.get("amount"));
        }
        support.audit(actor,"SETTLEMENT",id,"DRAFT_CREATED");
        return new Created(id);
    }

    @Transactional
    public void discardDraft(Authentication auth,long settlementId) {
        int actor=authorization.require(auth,PlatformPermission.SETTLEMENTS_MANAGE).user().getId();
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        Statement statement=loadStatement(settlementId,false);
        support.lockPeriod(statement.artistId(),statement.year(),statement.month());
        statement=loadStatement(settlementId,true);
        if (!"DRAFT".equals(statement.status())) {
            throw conflict("Somente rascunhos podem ser descartados");
        }
        jdbc.update("DELETE FROM commerce_settlement_items WHERE settlement_id=?",settlementId);
        jdbc.update("DELETE FROM commerce_settlements WHERE id=?",settlementId);
        support.audit(actor,"SETTLEMENT",settlementId,"DRAFT_DISCARDED");
    }

    @Transactional
    public void approve(Authentication auth,long settlementId) {
        int actor=authorization.require(auth,PlatformPermission.SETTLEMENTS_MANAGE).user().getId();
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        Statement statement=loadStatement(settlementId,false);
        support.lockPeriod(statement.artistId(),statement.year(),statement.month());
        statement=loadStatement(settlementId,true);
        if (!"DRAFT".equals(statement.status())) {
            if (List.of("APPROVED","PARTIALLY_PAID","PAID").contains(statement.status())) {
                return; // idempotent repeated approval
            }
            throw conflict("O demonstrativo não pode ser aprovado neste estado");
        }
        // Validate that every posted accrual is present exactly once, so a
        // draft can never approve partial or stale royalty history.
        List<Long> uncovered=jdbc.queryForList("""
            SELECT a.id FROM commerce_royalty_accruals a
              LEFT JOIN commerce_settlement_items i
                ON i.accrual_id=a.id AND i.settlement_id=?
             WHERE a.artist_user_id=? AND a.period_year=? AND a.period_month=?
               AND i.accrual_id IS NULL
             FOR UPDATE
            """,Long.class,statement.id(),statement.artistId(),
            statement.year(),statement.month());
        if (!uncovered.isEmpty()) {
            throw conflict("Existem lançamentos não incluídos no rascunho");
        }
        List<BigDecimal> itemAmounts=jdbc.queryForList("""
            SELECT amount_snapshot FROM commerce_settlement_items
             WHERE settlement_id=? FOR UPDATE
            """,BigDecimal.class,statement.id());
        BigDecimal total=BigDecimal.ZERO;
        for (BigDecimal lineAmount:itemAmounts) {
            total=total.add(lineAmount);
        }
        if (total.compareTo(statement.total())!=0) {
            throw conflict("A soma dos itens do demonstrativo não confere");
        }
        String status=total.signum()==0?"PAID":"APPROVED";
        jdbc.update("""
            UPDATE commerce_settlements SET status=?,
                approved_by_user_id=?,approved_at=CURRENT_TIMESTAMP(6)
             WHERE id=? AND status='DRAFT'
            """,status,actor,settlementId);
        support.audit(actor,"SETTLEMENT",settlementId,"APPROVED");
    }

    @Transactional
    public Created recordPayment(Authentication auth,long settlementId,RecordPayment payment) {
        int actor=authorization.require(auth,PlatformPermission.PAYMENTS_RECORD).user().getId();
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        if (payment==null) throw invalid("Pagamento obrigatório");
        String key=requestId(payment.requestKey());
        BigDecimal amount=money(payment.amount(),"Valor do pagamento");
        if (amount.signum()==0) throw invalid("O pagamento precisa ser maior que zero");
        if (payment.paidOn()==null || payment.paidOn().isAfter(today())
            || payment.paidOn().getYear()<2000) throw invalid("Data do pagamento inválida");
        String reference=text(payment.externalReference(),120,"Referência da transferência",true);
        String notes=text(payment.notes(),5000,"Observações",false);

        List<Map<String,Object>> prior=jdbc.queryForList("""
            SELECT id,settlement_id,amount,paid_on,external_reference,notes
              FROM commerce_payments WHERE request_key=?
            """,key);
        if (!prior.isEmpty()) {
            Map<String,Object> row=prior.get(0);
            LocalDate paid=((java.sql.Date)row.get("paid_on")).toLocalDate();
            if (((Number)row.get("settlement_id")).longValue()!=settlementId
                || ((BigDecimal)row.get("amount")).compareTo(amount)!=0
                || !paid.equals(payment.paidOn())
                || !reference.equals(row.get("external_reference"))
                || !java.util.Objects.equals(notes,row.get("notes"))) {
                throw conflict("A chave do pagamento já foi usada com outros dados");
            }
            return new Created(((Number)row.get("id")).longValue());
        }

        Statement statement=loadStatement(settlementId,true);
        if (!List.of("APPROVED","PARTIALLY_PAID").contains(statement.status())) {
            throw conflict("O fechamento precisa estar aprovado e com saldo a pagar");
        }
        List<BigDecimal> paidAmounts=jdbc.queryForList("""
            SELECT amount FROM commerce_payments
             WHERE settlement_id=? FOR UPDATE
            """,BigDecimal.class,settlementId);
        BigDecimal paid=BigDecimal.ZERO;
        for (BigDecimal recorded:paidAmounts) paid=paid.add(recorded);
        BigDecimal remaining=statement.total().subtract(paid);
        if (remaining.compareTo(amount)<0) {
            throw conflict("Pagamento excede o saldo pendente do fechamento");
        }
        long id;
        try {
            id=support.insert("""
                INSERT INTO commerce_payments
                    (settlement_id,request_key,amount,paid_on,external_reference,
                     notes,recorded_by_user_id)
                VALUES (?,?,?,?,?,?,?)
                """,settlementId,key,amount,payment.paidOn(),reference,notes,actor);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("Transferência já registrada. Confira a referência ou repita o mesmo UUID.");
        }
        String newStatus=remaining.compareTo(amount)==0?"PAID":"PARTIALLY_PAID";
        jdbc.update("""
            UPDATE commerce_settlements SET status=? WHERE id=?
            """,newStatus,settlementId);
        support.audit(actor,"PAYMENT",id,"RECORDED");
        return new Created(id);
    }

    /**
     * Administrative correction only; does not initiate a transfer or a refund.
     * A distinct negative ledger event offsets a positive recorded payment.
     * The original row is never modified, and the historical settlement
     * obligation and royalty accruals remain immutable.
     */
    @Transactional
    public Created reversePayment(Authentication auth,long settlementId,
                                  long originalPaymentId,ReversePayment input) {
        int actor=authorization.requireOwner(auth).getId();
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        if(input==null || input.reversedOn()==null ||
           input.reversedOn().getYear()<2000 || input.reversedOn().isAfter(today())) {
            throw invalid("Data do estorno inválida");
        }
        String reason=text(input.reason(),500,"Motivo do estorno",true);
        if(reason.length()<3) throw invalid("Informe um motivo com pelo menos 3 caracteres");

        // Lock the settlement before the payment, following recordPayment order
        // to avoid deadlocks with concurrent legitimate transfers.
        Statement statement=loadStatement(settlementId,true);
        if(!List.of("APPROVED","PARTIALLY_PAID","PAID").contains(statement.status())) {
            throw conflict("Apenas fechamentos aprovados podem ter pagamentos estornados");
        }
        List<Map<String,Object>> originals=jdbc.queryForList("""
            SELECT id,amount,paid_on,event_kind
              FROM commerce_payments
             WHERE id=? AND settlement_id=? FOR UPDATE
            """,originalPaymentId,settlementId);
        if(originals.size()!=1) throw missing("Pagamento não encontrado");
        Map<String,Object> original=originals.get(0);
        if(!"PAYMENT".equals(original.get("event_kind"))) {
            throw conflict("Somente pagamentos originais podem ser estornados");
        }

        List<Long> prior=jdbc.queryForList("""
            SELECT id FROM commerce_payments
             WHERE original_payment_id=? FOR UPDATE
            """,Long.class,originalPaymentId);
        if(!prior.isEmpty()) return new Created(prior.get(0));

        LocalDate originalDate=((java.sql.Date)original.get("paid_on")).toLocalDate();
        if(input.reversedOn().isBefore(originalDate)) {
            throw invalid("O estorno não pode ser anterior ao pagamento original");
        }
        BigDecimal originalAmount=(BigDecimal)original.get("amount");
        if(originalAmount.signum()<=0) throw conflict("Valor original inválido");

        List<BigDecimal> amounts=jdbc.queryForList("""
            SELECT amount FROM commerce_payments
             WHERE settlement_id=? FOR UPDATE
            """,BigDecimal.class,settlementId);
        BigDecimal paid=BigDecimal.ZERO;
        for(BigDecimal row:amounts) paid=paid.add(row);
        BigDecimal after=paid.subtract(originalAmount);
        if(after.signum()<0 || after.compareTo(statement.total())>0) {
            throw conflict("Saldo financeiro inconsistente; conciliação manual necessária");
        }

        String syntheticReference="REV-"+originalPaymentId+"-"+UUID.randomUUID();
        long reversalId;
        try {
            reversalId=support.insert("""
                INSERT INTO commerce_payments
                    (settlement_id,request_key,amount,event_kind,original_payment_id,
                     reversal_reason,reversed_by_user_id,reversed_at,paid_on,
                     external_reference,notes,recorded_by_user_id)
                VALUES (?,?,?,'REVERSAL',?,?,?,CURRENT_TIMESTAMP(6),?,?,?,?)
                """,settlementId,UUID.randomUUID().toString(),originalAmount.negate(),
                     originalPaymentId,reason,actor,input.reversedOn(),
                     syntheticReference,null,actor);
        } catch(DataIntegrityViolationException ex) {
            throw conflict("Este pagamento já foi estornado ou a operação está em conflito");
        }

        String next=after.signum()==0?"APPROVED":"PARTIALLY_PAID";
        jdbc.update("UPDATE commerce_settlements SET status=? WHERE id=?",
                    next,settlementId);
        support.audit(actor,"PAYMENT",reversalId,"REVERSED");
        return new Created(reversalId);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> listStatements(Authentication auth,int year,int month) {
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        if (year<2000 || year>2200 || month<1 || month>12) throw invalid("Período inválido");
        return jdbc.queryForList("""
            SELECT statement.id,statement.artist_user_id AS artistUserId,
                   COALESCE(p.artist_name,u.display_name,u.username,
                       CONCAT('Usuário ',u.id)) AS artistName,
                   statement.period_year AS periodYear,
                   statement.period_month AS periodMonth,
                   statement.status,statement.total_amount AS totalAmount,
                   (SELECT COALESCE(SUM(payment.amount),0)
                      FROM commerce_payments payment
                     WHERE payment.settlement_id=statement.id) AS paidAmount,
                   statement.created_at AS createdAt,
                   statement.approved_at AS approvedAt
              FROM commerce_settlements statement
              JOIN users u ON u.id=statement.artist_user_id
              LEFT JOIN commerce_artist_profiles p ON p.user_id=statement.artist_user_id
             WHERE statement.period_year=? AND statement.period_month=?
             ORDER BY statement.artist_user_id LIMIT 500
            """,year,month);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> statementItems(Authentication auth,long settlementId) {
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        return jdbc.queryForList("""
            SELECT item.accrual_id AS accrualId,item.amount_snapshot AS amount,
                   royalty.artwork_id AS artworkId,
                   royalty.contract_id AS contractId,
                   royalty.royalty_method_snapshot AS royaltyMethod,
                   royalty.event_kind AS eventKind,
                   saleitem.sale_id AS saleId
              FROM commerce_settlement_items item
              JOIN commerce_royalty_accruals royalty ON royalty.id=item.accrual_id
              JOIN commerce_sale_items saleitem ON saleitem.id=royalty.sale_item_id
             WHERE item.settlement_id=? ORDER BY item.accrual_id
            """,settlementId);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> payments(Authentication auth,long settlementId) {
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        return jdbc.queryForList("""
            SELECT id,amount,paid_on AS paidOn,external_reference AS externalReference,
                   notes,created_at AS createdAt,
                   event_kind AS eventKind,original_payment_id AS originalPaymentId,
                   reversal_reason AS reversalReason,reversed_at AS reversedAt
              FROM commerce_payments WHERE settlement_id=?
             ORDER BY id
            """,settlementId);
    }

    private Statement loadStatement(long id,boolean lock) {
        List<Statement> records=jdbc.query("""
            SELECT id,artist_user_id,period_year,period_month,status,total_amount
              FROM commerce_settlements WHERE id=?
            """+(lock?" FOR UPDATE":""),
            (rs,index)->new Statement(
                rs.getLong("id"),rs.getInt("artist_user_id"),rs.getInt("period_year"),
                rs.getInt("period_month"),rs.getString("status"),
                rs.getBigDecimal("total_amount")
            ),id);
        if(records.isEmpty()) throw missing("Demonstrativo não encontrado");
        return records.get(0);
    }
}
