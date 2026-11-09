package com.Brafurries.API.commerce;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import static com.Brafurries.API.commerce.CommerceFinanceSupport.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Append-only ADMINISTRATIVE corrections to BraFurries financial records.
 * Original payment and cash rows never change; no amounts, dates, methods,
 * settlement ownership or external transfer status can be edited here.
 */
@Service
public class CommerceFinancialMetadataRevisionService {
    public record PaymentRevision(String reference,String notes,String reason) {}
    public record CashRevision(String description,String reference,String notes,String reason) {}
    private final PlatformAuthorizationService authorization;
    private final CommerceFinanceSupport support;
    private final JdbcTemplate jdbc;
    public CommerceFinancialMetadataRevisionService(PlatformAuthorizationService authorization,
                                                      CommerceFinanceSupport support,
                                                      JdbcTemplate jdbc) {
        this.authorization=authorization;
        this.support=support;
        this.jdbc=jdbc;
    }

    @Transactional
    public void revisePayment(Authentication auth,long statementId,long paymentId,
                              PaymentRevision input) {
        int actor=authorization.require(auth,PlatformPermission.PAYMENTS_RECORD).user().getId();
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        if(input==null) throw invalid("Dados de correção obrigatórios");
        String reference=text(input.reference(),120,"Referência",true);
        String notes=text(input.notes(),5000,"Observações",false);
        String reason=reason(input.reason());
        // Settlement-level serialization protects concurrent corrections to
        // different payments and the uniqueness of effective references.
        List<Long> settlement=jdbc.queryForList("""
            SELECT id FROM commerce_settlements WHERE id=? FOR UPDATE
            """,Long.class,statementId);
        if(settlement.isEmpty()) throw missing("Fechamento não encontrado");
        List<Map<String,Object>> payments=jdbc.queryForList("""
            SELECT id,event_kind,external_reference AS reference,notes
              FROM commerce_payments WHERE id=? AND settlement_id=? FOR UPDATE
            """,paymentId,statementId);
        if(payments.isEmpty()) throw missing("Pagamento não encontrado neste fechamento");
        Map<String,Object> original=payments.get(0);
        if(!"PAYMENT".equals(original.get("event_kind"))) {
            throw conflict("Não é possível retificar os metadados de um estorno");
        }
        List<Map<String,Object>> recent=jdbc.queryForList("""
            SELECT updated_reference AS reference,updated_notes AS notes
              FROM commerce_payment_metadata_revisions
             WHERE payment_id=? ORDER BY id DESC LIMIT 1 FOR UPDATE
            """,paymentId);
        Map<String,Object> current=recent.isEmpty()?original:recent.get(0);
        String oldRef=(String)current.get("reference");
        String oldNotes=(String)current.get("notes");
        // Duplicate network retries must not manufacture extra revisions.
        if(Objects.equals(reference,oldRef)&&Objects.equals(notes,oldNotes)) return;

        // Unique business reference within a settlement, including corrected
        // references, not just the immutable originals with DB UNIQUE indices.
        List<Long> conflicts=jdbc.queryForList("""
            SELECT p.id FROM commerce_payments p
              LEFT JOIN commerce_payment_metadata_revisions r
                ON r.id=(SELECT MAX(latest.id)
                           FROM commerce_payment_metadata_revisions latest
                          WHERE latest.payment_id=p.id)
             WHERE p.settlement_id=? AND p.id<>?
               AND (CASE WHEN r.id IS NULL THEN p.external_reference
                         ELSE r.updated_reference END)=?
             LIMIT 1 FOR UPDATE
            """,Long.class,statementId,paymentId,reference);
        if(!conflicts.isEmpty()) throw conflict("Referência já utilizada neste fechamento");

        jdbc.update("""
            INSERT INTO commerce_payment_metadata_revisions
               (payment_id,previous_reference,updated_reference,previous_notes,
                updated_notes,reason,actor_user_id)
            VALUES (?,?,?,?,?,?,?)
            """,paymentId,oldRef,reference,oldNotes,notes,reason,actor);
        support.audit(actor,"PAYMENT",paymentId,"METADATA_CORRECTED");
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> paymentHistory(Authentication auth,long settlementId,
                                                   long paymentId) {
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        List<Long> found=jdbc.queryForList("""
            SELECT id FROM commerce_payments WHERE id=? AND settlement_id=?
            """,Long.class,paymentId,settlementId);
        if(found.isEmpty()) throw missing("Pagamento não encontrado neste fechamento");
        return jdbc.queryForList("""
            SELECT id,previous_reference AS previousReference,
                   updated_reference AS updatedReference,
                   previous_notes AS previousNotes,updated_notes AS updatedNotes,
                   reason,actor_user_id AS actorUserId,created_at AS createdAt
              FROM commerce_payment_metadata_revisions
             WHERE payment_id=? ORDER BY id DESC LIMIT 100
            """,paymentId);
    }

    @Transactional
    public void reviseCash(Authentication auth,long movementId,CashRevision input) {
        int actor=authorization.require(auth,PlatformPermission.CASHFLOW_MANAGE).user().getId();
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        if(input==null) throw invalid("Dados de correção obrigatórios");
        String description=text(input.description(),500,"Descrição",true);
        String reference=text(input.reference(),120,"Referência",false);
        String notes=text(input.notes(),5000,"Observações",false);
        String reason=reason(input.reason());
        List<Map<String,Object>> records=jdbc.queryForList("""
            SELECT id,event_kind,description,external_reference AS reference,notes
              FROM commerce_cash_movements WHERE id=? FOR UPDATE
            """,movementId);
        if(records.isEmpty()) throw missing("Movimentação financeira não encontrada");
        Map<String,Object> original=records.get(0);
        if(!"POSTED".equals(original.get("event_kind"))) {
            throw conflict("Apenas lançamentos originais podem ter dados administrativos corrigidos");
        }
        List<Map<String,Object>> revisions=jdbc.queryForList("""
            SELECT updated_description AS description,
                   updated_reference AS reference,updated_notes AS notes
              FROM commerce_cash_metadata_revisions
             WHERE movement_id=? ORDER BY id DESC LIMIT 1 FOR UPDATE
            """,movementId);
        Map<String,Object> previous=revisions.isEmpty()?original:revisions.get(0);
        String oldDesc=(String)previous.get("description"),
               oldRef=(String)previous.get("reference"),
               oldNotes=(String)previous.get("notes");
        if(Objects.equals(description,oldDesc)&&Objects.equals(reference,oldRef)
           &&Objects.equals(notes,oldNotes)) return;
        jdbc.update("""
            INSERT INTO commerce_cash_metadata_revisions
               (movement_id,previous_description,updated_description,
                previous_reference,updated_reference,previous_notes,updated_notes,
                reason,actor_user_id)
            VALUES (?,?,?,?,?,?,?,?,?)
            """,movementId,oldDesc,description,oldRef,reference,oldNotes,notes,
                 reason,actor);
        support.audit(actor,"CASH_MOVEMENT",movementId,"METADATA_CORRECTED");
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> cashHistory(Authentication auth,long id) {
        authorization.require(auth,PlatformPermission.FINANCE_VIEW);
        List<Long> found=jdbc.queryForList("""
            SELECT id FROM commerce_cash_movements WHERE id=?
            """,Long.class,id);
        if(found.isEmpty()) throw missing("Movimentação financeira não encontrada");
        return jdbc.queryForList("""
            SELECT id,previous_description AS previousDescription,
                   updated_description AS updatedDescription,
                   previous_reference AS previousReference,
                   updated_reference AS updatedReference,
                   previous_notes AS previousNotes,updated_notes AS updatedNotes,
                   reason,actor_user_id AS actorUserId,created_at AS createdAt
              FROM commerce_cash_metadata_revisions
             WHERE movement_id=? ORDER BY id DESC LIMIT 100
            """,id);
    }

    private String reason(String text) {
        String r=text(text,500,"Justificativa",true);
        if(r.length()<3) throw invalid("Justificativa deve ter ao menos 3 caracteres");
        return r;
    }
}
