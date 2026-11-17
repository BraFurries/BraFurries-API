package com.Brafurries.API.creatorstudio;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * All private Creator Studio access is based on a canonical users.id and a
 * currently valid subscription period. No Community, commerce or Patreon role
 * grants access. No payment processor state is trusted from client input.
 */
@Service
public class CreatorStudioAccessService {
    private final PlatformAuthorizationService identity;
    private final JdbcTemplate jdbc;

    public record AccessStatus(boolean active, String status,
                               String sourceKind, LocalDateTime expiresAtUtc) {}

    public CreatorStudioAccessService(PlatformAuthorizationService identity,
                                      JdbcTemplate jdbc) {
        this.identity = identity;
        this.jdbc = jdbc;
    }

    private int actorId(Authentication authentication) {
        User user = identity.currentUser(authentication);
        if (user.getId() == null || user.getMergedIntoUser() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Conta consolidada ou indisponível");
        }
        return user.getId();
    }

    @Transactional(readOnly = true)
    public AccessStatus myAccess(Authentication authentication) {
        return accessOf(actorId(authentication));
    }

    private AccessStatus accessOf(int userId) {
        List<AccessStatus> rows = jdbc.query("""
            SELECT source_kind,status,expires_at,
                   CASE WHEN status='ACTIVE'
                             AND starts_at<=UTC_TIMESTAMP(6)
                             AND expires_at>UTC_TIMESTAMP(6)
                        THEN 1 ELSE 0 END AS active
              FROM creator_studio_subscriptions
             WHERE user_id=?
            """, (rs, i) -> new AccessStatus(
                rs.getInt("active") == 1,
                rs.getString("status"),
                rs.getString("source_kind"),
                rs.getObject("expires_at", LocalDateTime.class)), userId);
        return rows.isEmpty() ? new AccessStatus(false, "NONE", null, null) : rows.get(0);
    }

    @Transactional(readOnly = true)
    public int requireActive(Authentication authentication) {
        int userId = actorId(authentication);
        Integer allowed = jdbc.queryForObject("""
            SELECT CASE WHEN EXISTS (
                SELECT 1 FROM creator_studio_subscriptions
                 WHERE user_id=?
                   AND status='ACTIVE'
                   AND starts_at<=UTC_TIMESTAMP(6)
                   AND expires_at>UTC_TIMESTAMP(6)
            ) THEN 1 ELSE 0 END
            """, Integer.class, userId);
        if (allowed == null || allowed != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "É necessária uma assinatura ativa do Creator Studio");
        }
        return userId;
    }

    /** Only platform owner can issue time-limited pilot access. */
    @Transactional
    public AccessStatus grantPilot(Authentication authentication, int targetUserId, int days) {
        User issuer = identity.requireOwner(authentication);
        if (issuer.getId() == null || issuer.getMergedIntoUser() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (targetUserId <= 0 || days < 1 || days > 90) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Usuário ou duração de piloto inválidos (1 a 90 dias)");
        }
        Integer exists = jdbc.queryForObject("""
            SELECT COUNT(*) FROM users
             WHERE id=? AND merged_into_user_id IS NULL
            """, Integer.class, targetUserId);
        if (exists == null || exists != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado");
        }

        List<Map<String, Object>> previous = jdbc.queryForList("""
            SELECT source_kind,status FROM creator_studio_subscriptions
             WHERE user_id=? FOR UPDATE
            """, targetUserId);
        String previousStatus = null;
        String action = "ACTIVATED";
        if (previous.isEmpty()) {
            jdbc.update("""
                INSERT INTO creator_studio_subscriptions
                    (user_id,source_kind,status,starts_at,expires_at,issued_by_user_id)
                VALUES (?,'PILOT','ACTIVE',UTC_TIMESTAMP(6),
                        DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? DAY),?)
                """, targetUserId, days, issuer.getId());
        } else {
            Map<String, Object> current = previous.get(0);
            if (!"PILOT".equals(current.get("source_kind"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Não sobrescrever uma assinatura paga com acesso piloto");
            }
            previousStatus = String.valueOf(current.get("status"));
            action = "RENEWED";
            jdbc.update("""
                UPDATE creator_studio_subscriptions
                   SET status='ACTIVE', starts_at=UTC_TIMESTAMP(6),
                       expires_at=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL ? DAY),
                       issued_by_user_id=?, external_reference=NULL
                 WHERE user_id=? AND source_kind='PILOT'
                """, issuer.getId(), targetUserId);
        }
        jdbc.update("""
            INSERT INTO creator_studio_subscription_events
                (user_id,actor_user_id,action,source_kind,previous_status,
                 new_status,starts_at,expires_at)
            SELECT ?,?,?,'PILOT',?,'ACTIVE',starts_at,expires_at
              FROM creator_studio_subscriptions WHERE user_id=?
            """, targetUserId, issuer.getId(), action, previousStatus, targetUserId);
        return accessOf(targetUserId);
    }

    @Transactional
    public void revokePilot(Authentication authentication, int targetUserId) {
        User issuer = identity.requireOwner(authentication);
        if (issuer.getId() == null || issuer.getMergedIntoUser() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        int changed = jdbc.update("""
            UPDATE creator_studio_subscriptions
               SET status='REVOKED'
             WHERE user_id=? AND source_kind='PILOT' AND status='ACTIVE'
            """, targetUserId);
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Assinatura piloto ativa não encontrada");
        }
        jdbc.update("""
            INSERT INTO creator_studio_subscription_events
                (user_id,actor_user_id,action,source_kind,previous_status,
                 new_status,starts_at,expires_at)
            SELECT ?,?,'REVOKED','PILOT','ACTIVE','REVOKED',starts_at,expires_at
              FROM creator_studio_subscriptions WHERE user_id=?
            """, targetUserId, issuer.getId(), targetUserId);
    }
}
