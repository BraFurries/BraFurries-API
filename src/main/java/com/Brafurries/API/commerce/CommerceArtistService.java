package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceArtistDtos.*;
import static com.Brafurries.API.commerce.CommerceFinanceSupport.*;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import java.math.BigDecimal;
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
 * BraFurries-only commercial artist profiles. The legacy artist_user_id stays
 * immutable in artwork/contract/royalty/settlement history. Claiming grants
 * account-scoped READ access through a separately approved owner link, never
 * OAuth ownership, login credentials, role grants or Community permissions.
 */
@Service
public class CommerceArtistService {
    private static final Set<String> VERIFICATION_METHODS =
        Set.of("TELEGRAM", "EMAIL", "IN_PERSON", "OTHER");

    private final PlatformAuthorizationService authorization;
    private final JdbcTemplate jdbc;
    private final CommerceFinanceSupport finance;

    public CommerceArtistService(PlatformAuthorizationService authorization,
                                 JdbcTemplate jdbc,CommerceFinanceSupport finance) {
        this.authorization=authorization;
        this.jdbc=jdbc;
        this.finance=finance;
    }

    /**
     * Create a true inert users row: never manufacture an e-mail, a password,
     * an OAuth subject, a Discord member or a privileged role.
     */
    @Transactional
    public Created registerExternal(Authentication auth, RegisterExternalArtist input) {
        User actor=authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE).user();
        if (input==null) throw invalid("Informe os dados do artista");
        String name=text(input.artistName(),120,"Nome artístico",true);
        String display=name.substring(0,Math.min(name.length(),32));
        long id=finance.insert("INSERT INTO users (display_name) VALUES (?)",display);
        int userId=Math.toIntExact(id);
        jdbc.update("""
            INSERT INTO commerce_artist_profiles
                (user_id,artist_name,telegram_handle,instagram_handle,
                 contact_email,internal_notes,created_by_user_id)
            VALUES (?,?,?,?,?,?,?)
            """,userId,name,
            text(input.telegramHandle(),100,"Telegram",false),
            text(input.instagramHandle(),100,"Instagram",false),
            text(input.contactEmail(),255,"E-mail de contato",false),
            text(input.internalNotes(),5000,"Observações",false),actor.getId());
        audit(actor.getId(),"ARTIST",userId,"CREATED");
        return new Created(userId);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> artists(Authentication auth) {
        authorization.require(auth,PlatformPermission.ARTWORKS_MANAGE);
        // This is administrative contact information; never serve it to a
        // claimant or an unapproved logged-in member.
        return jdbc.queryForList("""
            SELECT p.user_id AS userId,p.artist_name AS artistName,
                   p.telegram_handle AS telegramHandle,
                   p.instagram_handle AS instagramHandle,
                   p.contact_email AS contactEmail,
                   p.internal_notes AS internalNotes,
                   p.claimed_by_user_id AS claimedByUserId,p.claimed_at AS claimedAt,
                   p.created_at AS createdAt
              FROM commerce_artist_profiles p
             ORDER BY p.user_id DESC LIMIT 500
            """);
    }

    @Transactional
    public Created requestClaim(Authentication auth,RequestClaim input) {
        User applicant=activeAccount(auth);
        if(input==null || input.artistUserId()==null || input.artistUserId()<=0) {
            throw invalid("Informe o ID do artista");
        }
        int artistId=input.artistUserId();
        if(artistId==applicant.getId()) {
            throw conflict("Esta conta já é a identidade comercial da arte");
        }
        String message=text(input.message(),1000,"Mensagem",false);
        Map<String,Object> profile=lockProfile(artistId);
        if(profile.get("claimed_by_user_id")!=null) {
            throw conflict("Este cadastro já foi reivindicado");
        }
        List<Map<String,Object>> existing=jdbc.queryForList("""
            SELECT id FROM commerce_artist_claim_requests
             WHERE artist_user_id=? AND requester_user_id=? AND status='PENDING'
             FOR UPDATE
            """,artistId,applicant.getId());
        if(!existing.isEmpty()) return new Created(number(existing.get(0),"id"));
        // The generated unique pending_artist and pending_requester DB keys
        // prevent two concurrent claims and bound active requests per account.
        try {
            long id=finance.insert("""
                INSERT INTO commerce_artist_claim_requests
                    (artist_user_id,requester_user_id,requester_message)
                VALUES (?,?,?)
                """,artistId,applicant.getId(),message);
            audit(applicant.getId(),"ARTIST_CLAIM",id,"REQUESTED");
            return new Created(id);
        } catch(DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Já existe uma reivindicação pendente",ex);
        }
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> myClaims(Authentication auth) {
        int userId=activeAccount(auth).getId();
        // Do not return other applicants, owner review notes or private contact.
        return jdbc.queryForList("""
            SELECT r.id,r.artist_user_id AS artistUserId,
                   p.artist_name AS artistName,r.status,
                   r.created_at AS createdAt,r.reviewed_at AS reviewedAt
              FROM commerce_artist_claim_requests r
              JOIN commerce_artist_profiles p ON p.user_id=r.artist_user_id
             WHERE r.requester_user_id=?
             ORDER BY r.id DESC LIMIT 100
            """,userId);
    }

    @Transactional(readOnly=true)
    public List<Map<String,Object>> pendingClaims(Authentication auth) {
        authorization.requireOwner(auth);
        return jdbc.queryForList("""
            SELECT r.id,r.artist_user_id AS artistUserId,
                   p.artist_name AS artistName,
                   p.telegram_handle AS telegramHandle,
                   p.instagram_handle AS instagramHandle,
                   p.contact_email AS contactEmail,
                   r.requester_user_id AS requesterUserId,
                   COALESCE(u.display_name,u.username,'Usuário') AS requesterName,
                   u.email AS requesterEmail,r.requester_message AS message,
                   r.created_at AS createdAt
              FROM commerce_artist_claim_requests r
              JOIN commerce_artist_profiles p ON p.user_id=r.artist_user_id
              JOIN users u ON u.id=r.requester_user_id
             WHERE r.status='PENDING' ORDER BY r.id LIMIT 100
            """);
    }

    @Transactional
    public void approve(Authentication auth,long claimId,ApproveClaim input) {
        User owner=authorization.requireOwner(auth);
        if(input==null) throw invalid("Confirmação de identidade obrigatória");
        String method=text(input.verificationMethod(),20,"Método de verificação",true);
        if(!VERIFICATION_METHODS.contains(method)) throw invalid("Método de verificação inválido");
        String notes=text(input.verificationNotes(),1000,"Evidência da verificação",true);
        // Owner attests that the independently known contact channel was
        // verified OUTSIDE the website. The applicant's typed note or
        // artist nickname is never proof. No auto-approval.
        Map<String,Object> observed=requireClaim(claimId,false);
        int artistId=Math.toIntExact(number(observed,"artist_user_id"));
        Map<String,Object> profile=lockProfile(artistId);
        Map<String,Object> claim=requireClaim(claimId,true);
        if(!"PENDING".equals(claim.get("status"))) {
            throw conflict("Esta solicitação já foi analisada");
        }
        if(Math.toIntExact(number(claim,"artist_user_id"))!=artistId
            || profile.get("claimed_by_user_id")!=null) {
            throw conflict("A titularidade do artista mudou");
        }
        int applicantId=Math.toIntExact(number(claim,"requester_user_id"));
        if(applicantId==artistId) throw conflict("O titular já corresponde à conta");
        List<Map<String,Object>> applicants=jdbc.queryForList("""
            SELECT id,email_verified_at,merged_into_user_id
              FROM users WHERE id=? FOR UPDATE
            """,applicantId);
        if(applicants.isEmpty()
           || applicants.get(0).get("merged_into_user_id")!=null
           || applicants.get(0).get("email_verified_at")==null) {
            throw conflict("A conta solicitante precisa estar ativa e com e-mail verificado");
        }
        // A true external placeholder must not already own login identities
        // or active Community history. This guards ad hoc/out-of-band rows.
        List<Map<String,Object>> artistAccounts=jdbc.queryForList("""
            SELECT email,password_hash,merged_into_user_id FROM users WHERE id=? FOR UPDATE
            """,artistId);
        if(artistAccounts.isEmpty() ||
            artistAccounts.get(0).get("email")!=null ||
            artistAccounts.get(0).get("password_hash")!=null ||
            artistAccounts.get(0).get("merged_into_user_id")!=null) {
            throw conflict("O artista já possui credenciais; requer conciliação de contas");
        }
        if(exists("SELECT id FROM user_discord WHERE user_id=? LIMIT 1 FOR UPDATE",artistId)
           || exists("SELECT id FROM user_social_identities WHERE user_id=? LIMIT 1 FOR UPDATE",artistId)
           || exists("SELECT id FROM user_community_status WHERE user_id=? LIMIT 1 FOR UPDATE",artistId)
           || exists("SELECT role_id FROM api_user_roles WHERE user_id=? LIMIT 1 FOR UPDATE",artistId)
           || exists("SELECT id FROM user_telegram WHERE user_id=? LIMIT 1 FOR UPDATE",artistId)
           || exists("SELECT id FROM user_patreon WHERE user_id=? LIMIT 1 FOR UPDATE",artistId)
           || exists("SELECT user_id FROM user_permissions WHERE user_id=? LIMIT 1 FOR UPDATE",artistId)
           || exists("SELECT id FROM user_identity_links WHERE (user_a_id=? OR user_b_id=?) AND revoked_at IS NULL LIMIT 1 FOR UPDATE",artistId,artistId)) {
            throw conflict("O artista já possui identidade ou permissões; requer conciliação");
        }
        int approved=jdbc.update("""
            UPDATE commerce_artist_claim_requests
               SET status='APPROVED',verification_method=?,review_notes=?,
                   reviewed_by_user_id=?,reviewed_at=CURRENT_TIMESTAMP(6)
             WHERE id=? AND status='PENDING'
            """,method,notes,owner.getId(),claimId);
        if(approved!=1) throw conflict("Esta reivindicação mudou durante a revisão");
        int linked=jdbc.update("""
            UPDATE commerce_artist_profiles
               SET claimed_by_user_id=?,claimed_at=CURRENT_TIMESTAMP(6),
                   claim_approved_by_user_id=?
             WHERE user_id=? AND claimed_by_user_id IS NULL
            """,applicantId,owner.getId(),artistId);
        if(linked!=1) throw conflict("A titularidade do artista mudou durante a revisão");
        audit(owner.getId(),"ARTIST_CLAIM",claimId,"APPROVED");
    }

    @Transactional
    public void reject(Authentication auth,long claimId,RejectClaim input) {
        User owner=authorization.requireOwner(auth);
        String reason=text(input==null?null:input.reason(),1000,"Motivo da recusa",true);
        Map<String,Object> observed=requireClaim(claimId,false);
        int artistId=Math.toIntExact(number(observed,"artist_user_id"));
        lockProfile(artistId);
        Map<String,Object> claim=requireClaim(claimId,true);
        if(!"PENDING".equals(claim.get("status"))) {
            throw conflict("Esta solicitação já foi analisada");
        }
        int rejected=jdbc.update("""
            UPDATE commerce_artist_claim_requests
               SET status='REJECTED',review_notes=?,reviewed_by_user_id=?,
                   reviewed_at=CURRENT_TIMESTAMP(6)
             WHERE id=? AND status='PENDING'
            """,reason,owner.getId(),claimId);
        if(rejected!=1) throw conflict("Esta reivindicação mudou durante a revisão");
        audit(owner.getId(),"ARTIST_CLAIM",claimId,"REJECTED");
    }

    @Transactional
    public void revoke(Authentication auth,long artistId,RevokeClaim input) {
        User owner=authorization.requireOwner(auth);
        String reason=text(input==null?null:input.reason(),1000,"Motivo da revogação",true);
        Map<String,Object> profile=lockProfile(artistId);
        if(profile.get("claimed_by_user_id")==null) {
            throw conflict("O artista não possui reivindicação ativa");
        }
        int applicantId=Math.toIntExact(number(profile,"claimed_by_user_id"));
        List<Map<String,Object>> approved=jdbc.queryForList("""
            SELECT id FROM commerce_artist_claim_requests
             WHERE artist_user_id=? AND requester_user_id=? AND status='APPROVED'
             ORDER BY id DESC LIMIT 1 FOR UPDATE
            """,artistId,applicantId);
        if(approved.isEmpty()) throw conflict("Registro de aprovação não encontrado");
        long claimId=number(approved.get(0),"id");
        // Do not overwrite the ORIGINAL approval reviewer, proof or date.
        int revoked=jdbc.update("""
            UPDATE commerce_artist_claim_requests
               SET status='REVOKED',revocation_reason=?,revoked_by_user_id=?,
                   revoked_at=CURRENT_TIMESTAMP(6)
             WHERE id=? AND status='APPROVED'
            """,reason,owner.getId(),claimId);
        if(revoked!=1) throw conflict("Esta reivindicação mudou durante a revogação");
        int unlinked=jdbc.update("""
            UPDATE commerce_artist_profiles
               SET claimed_by_user_id=NULL,claimed_at=NULL,
                   claim_approved_by_user_id=NULL
             WHERE user_id=? AND claimed_by_user_id=?
            """,artistId,applicantId);
        if(unlinked!=1) throw conflict("Não foi possível remover a titularidade do artista");
        audit(owner.getId(),"ARTIST_CLAIM",claimId,"REVOKED");
    }

    /**
     * No actor-supplied user id or Community id. An authenticated website user
     * sees its own historical artist_user_id plus independently owner-approved
     * artist profiles, including records from before the account existed.
     *
     * Payments are tied to artist/period, NOT a particular artwork. Never
     * distribute paid amounts per artwork without a real allocation ledger.
     */
    @Transactional(readOnly=true)
    public Portfolio portfolio(Authentication auth,int year,int month) {
        User actor=activeAccount(auth);
        if(year<2000||year>2200||month<1||month>12) throw invalid("Período inválido");
        int id=actor.getId();

        List<Map<String,Object>> artworks=jdbc.queryForList("""
            SELECT a.id,a.title,a.status,a.artist_user_id AS artistUserId,
                   COALESCE(p.artist_name,u.display_name,u.username,'Artista') AS artistName,
                   COALESCE(SUM(r.amount),0) AS accruedTotal,
                   COALESCE(SUM(CASE WHEN r.period_year=? AND r.period_month=?
                                     THEN r.amount ELSE 0 END),0) AS accruedMonth
              FROM commerce_artworks a
              JOIN users u ON u.id=a.artist_user_id
              LEFT JOIN commerce_artist_profiles p ON p.user_id=a.artist_user_id
              LEFT JOIN commerce_royalty_accruals r
                ON r.artwork_id=a.id AND r.artist_user_id=a.artist_user_id
             WHERE (a.artist_user_id=? OR p.claimed_by_user_id=?)
             GROUP BY a.id,a.title,a.status,a.artist_user_id,
                      p.artist_name,u.display_name,u.username
             ORDER BY a.id DESC LIMIT 500
            """,year,month,id,id);
        BigDecimal accruedMonth=scalar("""
            SELECT COALESCE(SUM(amount),0) FROM commerce_royalty_accruals
             WHERE (artist_user_id=? OR artist_user_id IN
                    (SELECT user_id FROM commerce_artist_profiles
                      WHERE claimed_by_user_id=?))
               AND period_year=? AND period_month=?
            """,id,id,year,month);
        BigDecimal accruedTotal=scalar("""
            SELECT COALESCE(SUM(amount),0) FROM commerce_royalty_accruals
             WHERE artist_user_id=? OR artist_user_id IN
                   (SELECT user_id FROM commerce_artist_profiles
                     WHERE claimed_by_user_id=?)
            """,id,id);
        BigDecimal paidForPeriod=scalar("""
            SELECT COALESCE(SUM(pay.amount),0) FROM commerce_payments pay
              JOIN commerce_settlements s ON s.id=pay.settlement_id
             WHERE (s.artist_user_id=? OR s.artist_user_id IN
                    (SELECT user_id FROM commerce_artist_profiles
                      WHERE claimed_by_user_id=?))
               AND s.period_year=? AND s.period_month=?
            """,id,id,year,month);
        BigDecimal paidTotal=scalar("""
            SELECT COALESCE(SUM(pay.amount),0) FROM commerce_payments pay
              JOIN commerce_settlements s ON s.id=pay.settlement_id
             WHERE s.artist_user_id=? OR s.artist_user_id IN
                   (SELECT user_id FROM commerce_artist_profiles
                     WHERE claimed_by_user_id=?)
            """,id,id);
        List<Map<String,Object>> statements=jdbc.queryForList("""
            SELECT s.id,s.artist_user_id AS artistUserId,
                   COALESCE(p.artist_name,u.display_name,u.username,'Artista') AS artistName,
                   s.period_year AS periodYear,s.period_month AS periodMonth,
                   s.status,s.total_amount AS totalAmount,
                   COALESCE(SUM(pay.amount),0) AS paidAmount
              FROM commerce_settlements s
              JOIN users u ON u.id=s.artist_user_id
              LEFT JOIN commerce_artist_profiles p ON p.user_id=s.artist_user_id
              LEFT JOIN commerce_payments pay ON pay.settlement_id=s.id
             WHERE (s.artist_user_id=? OR p.claimed_by_user_id=?)
             GROUP BY s.id,s.artist_user_id,p.artist_name,u.display_name,u.username,
                      s.period_year,s.period_month,s.status,s.total_amount
             ORDER BY s.period_year DESC,s.period_month DESC,s.id DESC LIMIT 200
            """,id,id);
        return new Portfolio(year,month,accruedMonth,accruedTotal,
            paidForPeriod,paidTotal,artworks,statements);
    }

    private User activeAccount(Authentication auth) {
        if(!authorization.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Área comercial indisponível");
        }
        User user=authorization.currentUser(auth);
        if(user.getMergedIntoUser()!=null || user.getId()==null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Conta consolidada ou indisponível");
        }
        return user;
    }

    private Map<String,Object> lockProfile(long id) {
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT user_id,claimed_by_user_id FROM commerce_artist_profiles
             WHERE user_id=? FOR UPDATE
            """,id);
        if(rows.isEmpty()) throw missing("Artista comercial não encontrado");
        return rows.get(0);
    }

    private Map<String,Object> requireClaim(long id,boolean lock) {
        List<Map<String,Object>> rows=jdbc.queryForList("""
            SELECT id,artist_user_id,requester_user_id,status
              FROM commerce_artist_claim_requests WHERE id=?
            """+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty()) throw missing("Solicitação não encontrada");
        return rows.get(0);
    }

    private boolean exists(String sql,Object... identifiers) {
        // Current locking read: previous non-locking SELECT might reuse a stale
        // MariaDB REPEATABLE READ transaction snapshot acquired by the initial
        // claim lookup and miss a newly linked provider or Community identity.
        return !jdbc.queryForList(sql,identifiers).isEmpty();
    }

    private BigDecimal scalar(String sql,Object... args) {
        BigDecimal value=jdbc.queryForObject(sql,BigDecimal.class,args);
        return value==null?BigDecimal.ZERO:value;
    }

    private long number(Map<String,Object> row,String key) {
        return ((Number)row.get(key)).longValue();
    }

    private void audit(int actor,String type,long id,String action) {
        jdbc.update("""
            INSERT INTO commerce_catalog_audit
                (actor_user_id,entity_type,entity_id,action)
            VALUES (?,?,?,?)
            """,actor,type,id,action);
    }
}
