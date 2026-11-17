package com.Brafurries.API.creatorstudio;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.sql.ResultSet;
import java.sql.SQLException;
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
 * A Creator Studio subscription is an explicit invitation as well as a tier.
 * No row -> no access, even to Free. No Community/Patreon/commerce bypass.
 * Pilot contributors receive PRO with no expiration.
 */
@Service
public class CreatorStudioAccessService {
    private final PlatformAuthorizationService identity;
    private final JdbcTemplate jdbc;

    public record AccessStatus(boolean allowed, String tier, String status,
                               String sourceKind, LocalDateTime expiresAtUtc) {}

    public CreatorStudioAccessService(PlatformAuthorizationService identity,
                                      JdbcTemplate jdbc) {
        this.identity = identity;
        this.jdbc = jdbc;
    }

    int actorId(Authentication authentication) {
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

    private static AccessStatus mapAccess(ResultSet rs, int row) throws SQLException {
        return new AccessStatus(rs.getInt("allowed") == 1, rs.getString("tier"),
            rs.getString("status"), rs.getString("source_kind"),
            rs.getObject("expires_at", LocalDateTime.class));
    }

    private AccessStatus accessOf(int userId) {
        List<AccessStatus> rows = jdbc.query("""
            SELECT tier,source_kind,status,expires_at,
                   CASE WHEN status='ACTIVE'
                             AND starts_at<=UTC_TIMESTAMP(6)
                             AND (expires_at IS NULL OR expires_at>UTC_TIMESTAMP(6))
                        THEN 1 ELSE 0 END AS allowed
              FROM creator_studio_subscriptions
             WHERE user_id=?
            """, CreatorStudioAccessService::mapAccess, userId);
        return rows.isEmpty()
            ? new AccessStatus(false, null, "NOT_AUTHORIZED", null, null)
            : rows.get(0);
    }

    @Transactional(readOnly = true)
    public int requireActive(Authentication authentication) {
        return requireTier(authentication, null);
    }

    @Transactional(readOnly = true)
    public int requirePro(Authentication authentication) {
        return requireTier(authentication, "PRO");
    }

    private int requireTier(Authentication authentication, String minimumTier) {
        int userId = actorId(authentication);
        Integer allowed = jdbc.queryForObject("""
            SELECT CASE WHEN EXISTS (
                SELECT 1 FROM creator_studio_subscriptions
                 WHERE user_id=?
                   AND status='ACTIVE'
                   AND starts_at<=UTC_TIMESTAMP(6)
                   AND (expires_at IS NULL OR expires_at>UTC_TIMESTAMP(6))
                   AND (? IS NULL OR tier=?)
            ) THEN 1 ELSE 0 END
            """, Integer.class, userId, minimumTier, minimumTier);
        if (allowed == null || allowed != 1) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Acesso autorizado ao plano Creator Studio necessário");
        }
        return userId;
    }

    /** Explicit invitation for the limited, free edition. No self-enrollment. */
    @Transactional
    public AccessStatus grantFree(Authentication auth, int targetUserId) {
        return grant(auth, targetUserId, "INVITE", "FREE");
    }

    /** Founding pilot participant; permanent PRO, never auto-downgraded. */
    @Transactional
    public AccessStatus grantPilotLifetimePro(Authentication auth, int targetUserId) {
        return grant(auth, targetUserId, "PILOT", "PRO");
    }

    private AccessStatus grant(Authentication auth, int targetUserId, String source, String tier) {
        User issuer = identity.requireOwner(auth);
        if (issuer.getId() == null || issuer.getMergedIntoUser() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        if (targetUserId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Usuário inválido");
        }

        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM users WHERE id=? AND merged_into_user_id IS NULL
            """, Integer.class, targetUserId);
        if (count == null || count != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Usuário ativo não encontrado");
        }

        List<Map<String, Object>> oldRows = jdbc.queryForList("""
            SELECT tier,source_kind,status
              FROM creator_studio_subscriptions
             WHERE user_id=? FOR UPDATE
            """, targetUserId);
        String oldStatus = null;
        String action = "ACTIVATED";
        if (oldRows.isEmpty()) {
            jdbc.update("""
                INSERT INTO creator_studio_subscriptions
                    (user_id,tier,source_kind,status,starts_at,expires_at,issued_by_user_id)
                VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6),NULL,?)
                """, targetUserId, tier, source, issuer.getId());
        } else {
            Map<String, Object> old = oldRows.get(0);
            String previousSource = String.valueOf(old.get("source_kind"));
            oldStatus = String.valueOf(old.get("status"));
            if ("BILLING".equals(previousSource)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Acesso associado a cobrança; não sobrescrever administrativamente");
            }
            if ("PILOT".equals(previousSource) && "INVITE".equals(source)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Não rebaixar o Pro vitalício do piloto para Free");
            }
            if (source.equals(previousSource) && "ACTIVE".equals(oldStatus)) {
                AccessStatus currentAccess = accessOf(targetUserId);
                if (currentAccess.allowed()) {
                    return currentAccess; // Idempotent, no duplicate audit.
                }
                // An expired or not-yet-started FREE invitation can be reissued.
            }
            action = "INVITE".equals(previousSource) && "PILOT".equals(source)
                ? "PLAN_CHANGED" : "RENEWED";
            jdbc.update("""
                UPDATE creator_studio_subscriptions
                   SET tier=?,source_kind=?,status='ACTIVE',starts_at=UTC_TIMESTAMP(6),
                       expires_at=NULL,issued_by_user_id=?,external_reference=NULL
                 WHERE user_id=?
                """, tier, source, issuer.getId(), targetUserId);
        }
        jdbc.update("""
            INSERT INTO creator_studio_subscription_events
                (user_id,actor_user_id,action,tier,source_kind,previous_status,
                 new_status,starts_at,expires_at)
            SELECT ?,?,?,tier,source_kind,?,'ACTIVE',starts_at,expires_at
              FROM creator_studio_subscriptions WHERE user_id=?
            """, targetUserId, issuer.getId(), action, oldStatus, targetUserId);
        return accessOf(targetUserId);
    }

    /** Explicit owner action. No automatic expiry or billing-triggered revocation. */
    @Transactional
    public void revokeGrant(Authentication auth, int targetUserId) {
        User issuer = identity.requireOwner(auth);
        if (issuer.getId() == null || issuer.getMergedIntoUser() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        int changed = jdbc.update("""
            UPDATE creator_studio_subscriptions
               SET status='REVOKED'
             WHERE user_id=? AND source_kind IN ('INVITE','PILOT')
               AND status='ACTIVE'
            """, targetUserId);
        if (changed != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Concessão Free/Pilot ativa não encontrada");
        }
        jdbc.update("""
            INSERT INTO creator_studio_subscription_events
                (user_id,actor_user_id,action,tier,source_kind,previous_status,
                 new_status,starts_at,expires_at)
            SELECT ?,?,'REVOKED',tier,source_kind,'ACTIVE','REVOKED',
                   starts_at,expires_at
              FROM creator_studio_subscriptions WHERE user_id=?
            """, targetUserId, issuer.getId(), targetUserId);
    }
}
