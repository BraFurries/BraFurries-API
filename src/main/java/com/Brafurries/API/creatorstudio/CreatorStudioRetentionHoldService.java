package com.Brafurries.API.creatorstudio;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Temporary exceptional retention is exclusively authorized by the platform
 * owner, NOT Community administrators, artists or the Coddy runtime.
 * Holds are not a user-selectable way to postpone ordinary expiry.
 */
@Service
public class CreatorStudioRetentionHoldService {
    private final PlatformAuthorizationService identity;
    private final JdbcTemplate jdbc;

    public enum Category { LEGAL_OBLIGATION, DISPUTE, DATA_REQUEST, OTHER }
    public record HoldDraft(int artistUserId, long commissionId, Category category, String reason) {}
    public record Hold(int artistUserId, long commissionId, boolean active,
                       Category category, String reason) {}

    public CreatorStudioRetentionHoldService(PlatformAuthorizationService identity,
                                              JdbcTemplate jdbc) {
        this.identity=identity;
        this.jdbc=jdbc;
    }

    private int owner(Authentication auth) {
        User user=identity.requireOwner(auth);
        if (user.getId()==null || user.getMergedIntoUser()!=null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return user.getId();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,
            "Commission ou retenção excepcional não encontrada");
    }

    private void lockCommission(int artist, long commission) {
        List<Long> found=jdbc.query("""
            SELECT id FROM creator_studio_commissions
             WHERE owner_user_id=? AND id=? FOR UPDATE
            """,(rs,n)->rs.getLong("id"),artist,commission);
        if (found.isEmpty()) throw notFound();
    }

    private Hold stored(int artist, long commission) {
        List<Hold> items=jdbc.query("""
            SELECT active,category,reason
              FROM creator_studio_retention_holds
             WHERE owner_user_id=? AND commission_id=?
            """,(rs,n)->new Hold(artist,commission,rs.getInt("active")==1,
                Category.valueOf(rs.getString("category")),rs.getString("reason")),
                artist,commission);
        return items.isEmpty()?null:items.get(0);
    }

    @Transactional(readOnly=true)
    public Hold inspect(Authentication auth, int artistId, long commissionId) {
        owner(auth);
        if (artistId<=0 || commissionId<=0) throw notFound();
        Hold item=stored(artistId,commissionId);
        if (item==null) throw notFound();
        return item;
    }

    @Transactional
    public Hold place(Authentication auth, HoldDraft draft) {
        int admin=owner(auth);
        if (draft==null || draft.artistUserId()<=0 || draft.commissionId()<=0 ||
            draft.category()==null || draft.reason()==null ||
            draft.reason().isBlank() || draft.reason().trim().length()>500)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Retenção inválida");
        String reason=draft.reason().trim();
        int artist=draft.artistUserId();
        long commission=draft.commissionId();
        lockCommission(artist,commission);
        Hold old=stored(artist,commission);
        if (old!=null && old.active() && old.category()==draft.category()
            && old.reason().equals(reason)) return old;
        if (old==null) {
            jdbc.update("""
                INSERT INTO creator_studio_retention_holds
                    (owner_user_id,commission_id,active,category,reason,
                     created_by_user_id,updated_by_user_id)
                VALUES (?,?,1,?,?,?,?)
                """,artist,commission,draft.category().name(),reason,admin,admin);
        } else {
            jdbc.update("""
                UPDATE creator_studio_retention_holds
                   SET active=1,category=?,reason=?,updated_by_user_id=?
                 WHERE owner_user_id=? AND commission_id=?
                """,draft.category().name(),reason,admin,artist,commission);
        }
        audit(artist,commission,admin,"PLACED",draft.category());
        return stored(artist,commission);
    }

    @Transactional
    public void release(Authentication auth,int artist,long commission) {
        int admin=owner(auth);
        if (artist<=0 || commission<=0) throw notFound();
        lockCommission(artist,commission);
        Hold old=stored(artist,commission);
        if (old==null || !old.active()) throw notFound();
        int updated=jdbc.update("""
            UPDATE creator_studio_retention_holds
               SET active=0,updated_by_user_id=?
             WHERE owner_user_id=? AND commission_id=? AND active=1
            """,admin,artist,commission);
        if (updated!=1) throw new IllegalStateException("Retention hold changed");
        audit(artist,commission,admin,"RELEASED",old.category());
    }

    private void audit(int artist,long commission,int admin,String action,Category category) {
        jdbc.update("""
            INSERT INTO creator_studio_retention_hold_events
                (owner_user_id,commission_id,actor_user_id,action,category)
            VALUES (?,?,?,?,?)
            """,artist,commission,admin,action,category.name());
    }
}
