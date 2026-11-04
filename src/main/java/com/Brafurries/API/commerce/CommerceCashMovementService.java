package com.Brafurries.API.commerce;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;

import static com.Brafurries.API.commerce.CommerceFinanceSupport.*;
import static com.Brafurries.API.commerce.CommerceCashMovementDtos.*;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BraFurries cash funding and miscellaneous receipts/disbursements, not a
 * second sales ledger. Only private finance grants may read/post/reverse.
 * Append-only compensation preserves who corrected the historical record.
 */
@Service
public class CommerceCashMovementService {
    private static final Set<String> TYPES=Set.of("INCOME","EXPENSE","CONTRIBUTION");
    private static final Set<String> CONTRIBUTOR_TYPES=Set.of(
        "OWNER","STAFF","COMMUNITY_MEMBER","EXTERNAL");
    private static final Set<String> NATURES=Set.of("NON_REPAYABLE","REPAYABLE");
    private static final Set<String> METHODS=Set.of(
        "CASH","PIX","CARD_DEBIT","CARD_CREDIT",
        "BANK_TRANSFER","BANK_SLIP","OTHER");

    private final PlatformAuthorizationService authorization;
    private final CommerceFinanceSupport support;
    private final JdbcTemplate jdbc;

    public CommerceCashMovementService(
        PlatformAuthorizationService authorization,
        CommerceFinanceSupport support,
        JdbcTemplate jdbc
    ) {
        this.authorization=authorization;
        this.support=support;
        this.jdbc=jdbc;
    }

    @Transactional
    public Created create(Authentication auth,CreateMovement input) {
        int actor=authorization.require(auth,PlatformPermission.CASHFLOW_MANAGE).user().getId();
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        if (input==null) throw invalid("Movimentação não informada");
        String key=requestId(input.requestKey());
        String type=input.movementType();
        if (type==null || !TYPES.contains(type)) throw invalid("Tipo de movimentação inválido");
        BigDecimal amount=money(input.amount(),"Valor da movimentação");
        if (amount.signum()<=0) throw invalid("O valor da movimentação deve ser maior que zero");
        LocalDate occurredOn=input.occurredOn();
        if (occurredOn==null || occurredOn.getYear()<2000 || occurredOn.isAfter(today())) {
            throw invalid("Informe uma data válida, sem futuro");
        }
        String category=text(input.category(),80,"Categoria",true);
        String description=text(input.description(),500,"Descrição",true);
        String counterparty=text(input.counterpartyLabel(),160,"Origem/destino",true);
        String method=input.paymentMethod();
        if (method==null || !METHODS.contains(method)) throw invalid("Forma de pagamento inválida");
        String reference=text(input.externalReference(),120,"Referência",false);
        String notes=text(input.notes(),5000,"Observações",false);
        String contributorType=input.contributorType();
        String nature=input.contributionNature();

        if ("CONTRIBUTION".equals(type)) {
            if (contributorType==null || nature==null || !CONTRIBUTOR_TYPES.contains(contributorType) || !NATURES.contains(nature)) {
                throw invalid("Aporte exige tipo de contribuinte e natureza reembolsável ou não");
            }
        } else if (contributorType!=null || nature!=null) {
            throw invalid("Natureza de aporte só se aplica a movimentações de aporte");
        }

        Integer linkedId=input.counterpartyUserId();
        if (linkedId!=null) {
            if (linkedId<=0) throw invalid("Usuário de origem/destino inválido");
            Integer exists=jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE id=?",Integer.class,linkedId);
            if (exists==null || exists!=1) throw invalid("Usuário vinculado não encontrado");
            if (contributorType!=null && Set.of("STAFF","COMMUNITY_MEMBER").contains(contributorType)) {
                Integer member=jdbc.queryForObject("""
                    SELECT COUNT(*) FROM user_community_status
                     WHERE user_id=? AND community_id=?
                    """,Integer.class,linkedId,authorization.officialCommunityId());
                if (member==null || member<1) {
                    throw invalid("Contribuinte não pertence à BraFurries");
                }
            }
        }

        // Idempotency represents the entire normalized economic operation.
        List<String> canonical=new ArrayList<>();
        canonical.add(type);
        canonical.add(amount.toPlainString());
        canonical.add(occurredOn.toString());
        canonical.add(category);
        canonical.add(description);
        canonical.add(counterparty);
        canonical.add(linkedId==null?null:linkedId.toString());
        canonical.add(contributorType);
        canonical.add(nature);
        canonical.add(method);
        canonical.add(reference);
        canonical.add(notes);
        String fingerprint=fingerprint(canonical);
        List<Map<String,Object>> existing=jdbc.queryForList("""
            SELECT id,request_sha256 FROM commerce_cash_movements WHERE request_key=?
            """,key);
        if (!existing.isEmpty()) {
            if (!fingerprint.equals(existing.get(0).get("request_sha256"))) {
                throw conflict("UUID de movimentação já utilizado com outros dados");
            }
            return new Created(((Number)existing.get(0).get("id")).longValue());
        }

        long id;
        try {
            id=support.insert("""
                INSERT INTO commerce_cash_movements
                    (request_key,request_sha256,movement_type,amount,occurred_on,
                     category,description,counterparty_label,counterparty_user_id,
                     contributor_type,contribution_nature,payment_method,
                     external_reference,notes,created_by_user_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,key,fingerprint,type,amount,occurredOn,category,
                description,counterparty,linkedId,contributorType,nature,
                method,reference,notes,actor);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("Movimentação duplicada; consulte o histórico e repita com o mesmo UUID");
        }
        support.audit(actor,"CASH_MOVEMENT",id,"POSTED");
        return new Created(id);
    }

    @Transactional
    public Created reverse(Authentication auth,long movementId,String reason) {
        int actor=authorization.require(auth,PlatformPermission.CASHFLOW_MANAGE).user().getId();
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        String why=text(reason,500,"Justificativa",true);
        List<Map<String,Object>> records=jdbc.queryForList("""
            SELECT id,request_sha256,movement_type,event_kind,amount,occurred_on,
                   category,description,counterparty_label,counterparty_user_id,
                   contributor_type,contribution_nature,payment_method,
                   external_reference,notes
              FROM commerce_cash_movements WHERE id=? FOR UPDATE
            """,movementId);
        if (records.isEmpty()) throw missing("Movimentação não encontrada");
        Map<String,Object> original=records.get(0);
        if (!"POSTED".equals(original.get("event_kind"))) {
            throw conflict("Somente lançamentos originais podem ser estornados");
        }
        // Unique reversal_of_id enforces idempotency even across concurrent requests.
        List<Long> prior=jdbc.queryForList("""
            SELECT id FROM commerce_cash_movements WHERE reversal_of_id=? FOR UPDATE
            """,Long.class,movementId);
        if (!prior.isEmpty()) return new Created(prior.get(0));

        long id;
        try {
            String newKey=UUID.randomUUID().toString();
            String fingerprint=fingerprint(List.of(Long.toString(movementId),why));
            id=support.insert("""
                INSERT INTO commerce_cash_movements
                    (request_key,request_sha256,movement_type,event_kind,reversal_of_id,
                     amount,occurred_on,category,description,counterparty_label,
                     counterparty_user_id,contributor_type,contribution_nature,
                     payment_method,external_reference,notes,reversal_reason,
                     created_by_user_id)
                VALUES (?,?,?,'REVERSAL',?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,newKey,fingerprint,original.get("movement_type"),movementId,
                original.get("amount"),original.get("occurred_on"),
                original.get("category"),original.get("description"),
                original.get("counterparty_label"),original.get("counterparty_user_id"),
                original.get("contributor_type"),original.get("contribution_nature"),
                original.get("payment_method"),original.get("external_reference"),
                original.get("notes"),why,actor);
        } catch (DataIntegrityViolationException ex) {
            throw conflict("A movimentação já foi estornada por outra operação");
        }
        support.audit(actor,"CASH_MOVEMENT",id,"REVERSED");
        return new Created(id);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> list(Authentication auth,int year,int month) {
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        YearMonth ym=period(year,month);
        return jdbc.queryForList("""
            SELECT m.id,m.request_key AS requestKey,m.movement_type AS movementType,
                   m.event_kind AS eventKind,m.reversal_of_id AS reversalOfId,
                   m.amount,m.signed_amount AS signedAmount,
                   m.occurred_on AS occurredOn,m.category,m.description,
                   m.counterparty_label AS counterpartyLabel,
                   m.counterparty_user_id AS counterpartyUserId,
                   m.contributor_type AS contributorType,
                   m.contribution_nature AS contributionNature,
                   m.payment_method AS paymentMethod,
                   m.external_reference AS externalReference,m.notes,
                   m.reversal_reason AS reversalReason,
                   m.created_at AS createdAt
              FROM commerce_cash_movements m
             WHERE m.occurred_on >= ? AND m.occurred_on < ?
             ORDER BY m.occurred_on DESC,m.id DESC LIMIT 1000
            """,Date.valueOf(ym.atDay(1)),Date.valueOf(ym.plusMonths(1).atDay(1)));
    }

    @Transactional(readOnly=true)
    public Map<String,Object> summary(Authentication auth,int year,int month) {
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        YearMonth ym=period(year,month);
        Date from=Date.valueOf(ym.atDay(1));
        Date until=Date.valueOf(ym.plusMonths(1).atDay(1));

        BigDecimal salesGross=sum("""
            SELECT COALESCE(SUM(gross_amount),0) FROM commerce_sales
             WHERE sold_on>=? AND sold_on<? AND status='CONFIRMED'
            """,from,until);
        BigDecimal fees=sum("""
            SELECT COALESCE(SUM(processing_fee_amount),0) FROM commerce_sales
             WHERE sold_on>=? AND sold_on<? AND status='CONFIRMED'
            """,from,until);
        BigDecimal miscIncome=sumByType("INCOME",null,from,until);
        BigDecimal expenses=sumByType("EXPENSE",null,from,until);
        BigDecimal nonRepayable=sumByType("CONTRIBUTION","NON_REPAYABLE",from,until);
        BigDecimal repayable=sumByType("CONTRIBUTION","REPAYABLE",from,until);
        BigDecimal artistPayouts=sum("""
            SELECT COALESCE(SUM(amount),0) FROM commerce_payments
             WHERE paid_on>=? AND paid_on<?
            """,from,until);
        BigDecimal netSales=salesGross.subtract(fees);
        BigDecimal funding=nonRepayable.add(repayable);
        BigDecimal cashChange=netSales.add(miscIncome).add(expenses)
            .add(funding).subtract(artistPayouts);

        // Costs of production aren't automatically cash outflows: invoice
        // production and payment need not happen in the same period.
        return Map.ofEntries(
            Map.entry("year",year),Map.entry("month",month),
            Map.entry("grossSales",salesGross),Map.entry("processingFees",fees),
            Map.entry("netSales",netSales),Map.entry("otherIncome",miscIncome),
            Map.entry("operatingExpenses",expenses),Map.entry("fundingNonRepayable",nonRepayable),
            Map.entry("fundingRepayable",repayable),Map.entry("artistPayouts",artistPayouts),
            Map.entry("netCashMovement",cashChange)
        );
    }

    private BigDecimal sumByType(String type,String nature,Date from,Date until) {
        if (nature==null) {
            return sum("""
                SELECT COALESCE(SUM(signed_amount),0) FROM commerce_cash_movements
                 WHERE movement_type=? AND occurred_on>=? AND occurred_on<?
                """,type,from,until);
        }
        return sum("""
            SELECT COALESCE(SUM(signed_amount),0) FROM commerce_cash_movements
             WHERE movement_type=? AND contribution_nature=?
               AND occurred_on>=? AND occurred_on<?
            """,type,nature,from,until);
    }

    private BigDecimal sum(String sql,Object...params) {
        BigDecimal value=jdbc.queryForObject(sql,BigDecimal.class,params);
        return value==null?BigDecimal.ZERO:value;
    }

    private YearMonth period(int year,int month) {
        if (year<2000 || year>2200 || month<1 || month>12) {
            throw invalid("Mês contábil inválido");
        }
        return YearMonth.of(year,month);
    }
}
