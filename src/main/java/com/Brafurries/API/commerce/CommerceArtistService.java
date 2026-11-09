package com.Brafurries.API.commerce;

import static com.Brafurries.API.commerce.CommerceArtistDtos.*;
import static com.Brafurries.API.commerce.CommerceFinanceSupport.*;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.platform.PlatformPermission;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * BraFurries-only commercial artist profiles.
 * A staff-issued invitation activates the same user id or consolidates all
 * current commerce ownership into the existing canonical account. Historical
 * signed artist signatory snapshots remain immutable.
 */
@Service
public class CommerceArtistService {
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
                   p.created_at AS createdAt
              FROM commerce_artist_profiles p
             ORDER BY p.user_id DESC LIMIT 500
            """);
    }

    /**
     * No actor-supplied user id or Community id. An authenticated website user
     * sees only rows belonging to its canonical users.id after a verified
     * staff-issued invitation and transactional account consolidation.
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
             WHERE a.artist_user_id=?
             GROUP BY a.id,a.title,a.status,a.artist_user_id,
                      p.artist_name,u.display_name,u.username
             ORDER BY a.id DESC LIMIT 500
            """,year,month,id);
        BigDecimal accruedMonth=scalar("""
            SELECT COALESCE(SUM(amount),0) FROM commerce_royalty_accruals
             WHERE artist_user_id=? AND period_year=? AND period_month=?
            """,id,year,month);
        BigDecimal accruedTotal=scalar("""
            SELECT COALESCE(SUM(amount),0) FROM commerce_royalty_accruals
             WHERE artist_user_id=?
            """,id);
        BigDecimal paidForPeriod=scalar("""
            SELECT COALESCE(SUM(pay.amount),0) FROM commerce_payments pay
              JOIN commerce_settlements s ON s.id=pay.settlement_id
             WHERE s.artist_user_id=? AND s.period_year=? AND s.period_month=?
            """,id,year,month);
        BigDecimal paidTotal=scalar("""
            SELECT COALESCE(SUM(pay.amount),0) FROM commerce_payments pay
              JOIN commerce_settlements s ON s.id=pay.settlement_id
             WHERE s.artist_user_id=?
            """,id);
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
             WHERE s.artist_user_id=?
             GROUP BY s.id,s.artist_user_id,p.artist_name,u.display_name,u.username,
                      s.period_year,s.period_month,s.status,s.total_amount
             ORDER BY s.period_year DESC,s.period_month DESC,s.id DESC LIMIT 200
            """,id);
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

    private BigDecimal scalar(String sql,Object... args) {
        BigDecimal value=jdbc.queryForObject(sql,BigDecimal.class,args);
        return value==null?BigDecimal.ZERO:value;
    }

    private void audit(int actor,String type,long id,String action) {
        jdbc.update("""
            INSERT INTO commerce_catalog_audit
                (actor_user_id,entity_type,entity_id,action)
            VALUES (?,?,?,?)
            """,actor,type,id,action);
    }
}
