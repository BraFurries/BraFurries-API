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

    /** Owner-only overview. No Community status or Commerce role can grant access. */
    public record Invitation(int userId, String displayName, String tier,
                             String sourceKind, String status, boolean active,
                             LocalDateTime startsAtUtc, LocalDateTime expiresAtUtc,
                             boolean mergedAccount) {}

    public record InvitationPage(List<Invitation> items, int page, int pageSize, long total) {}

    public record Candidate(int userId, String displayName, boolean mergedAccount,
                            AccessStatus currentAccess) {}

    private void requireActiveOwner(Authentication auth) {
        User issuer = identity.requireOwner(auth);
        if (issuer.getId() == null || issuer.getMergedIntoUser() != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Conta proprietária consolidada ou indisponível");
        }
    }

    /** Preview the canonical account BEFORE a grant; does not change any rights. */
    @Transactional(readOnly = true)
    public Candidate candidate(Authentication auth, int userId) {
        requireActiveOwner(auth);
        if (userId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ID de usuário inválido");
        }
        List<Candidate> users = jdbc.query("""
            SELECT id, display_name, merged_into_user_id
              FROM users WHERE id=?
            """, (rs, n) -> new Candidate(
                rs.getInt("id"),
                rs.getString("display_name"),
                rs.getObject("merged_into_user_id") != null,
                new AccessStatus(false, null, "NOT_AUTHORIZED", null, null)
            ), userId);
        if (users.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conta não encontrada");
        }
        Candidate selected = users.get(0);
        AccessStatus stored = accessOf(selected.userId());
        AccessStatus effective = selected.mergedAccount()
            ? new AccessStatus(false, stored.tier(), stored.status(),
                stored.sourceKind(), stored.expiresAtUtc()) : stored;
        return new Candidate(selected.userId(), selected.displayName(),
            selected.mergedAccount(), effective);
    }

    /** Owner-only paginated grants list, including revoked and merged accounts. */
    @Transactional(readOnly = true)
    public InvitationPage invitations(Authentication auth, String status, int page, int pageSize) {
        requireActiveOwner(auth);
        String filter = status == null || status.isBlank()
            ? "ALL" : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!List.of("ALL", "ACTIVE", "REVOKED", "SUSPENDED").contains(filter)
            || page < 1 || page > 100_000 || pageSize < 1 || pageSize > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Filtro ou paginação inválida");
        }
        Long total = jdbc.queryForObject("""
            SELECT COUNT(*) FROM creator_studio_subscriptions
             WHERE (?='ALL' OR status=?)
            """, Long.class, filter, filter);
        List<Invitation> rows = jdbc.query("""
            SELECT s.user_id,u.display_name,s.tier,s.source_kind,s.status,
                   s.starts_at,s.expires_at,u.merged_into_user_id,
                   CASE WHEN s.status='ACTIVE'
                         AND s.starts_at<=UTC_TIMESTAMP(6)
                         AND (s.expires_at IS NULL OR s.expires_at>UTC_TIMESTAMP(6))
                         AND u.merged_into_user_id IS NULL
                     THEN 1 ELSE 0 END AS effective_access
              FROM creator_studio_subscriptions s
              JOIN users u ON u.id=s.user_id
             WHERE (?='ALL' OR s.status=?)
             ORDER BY s.created_at DESC,s.user_id DESC
             LIMIT ? OFFSET ?
            """, (rs, n) -> new Invitation(
                rs.getInt("user_id"),
                rs.getString("display_name"),
                rs.getString("tier"),
                rs.getString("source_kind"),
                rs.getString("status"),
                rs.getInt("effective_access") == 1,
                rs.getObject("starts_at", LocalDateTime.class),
                rs.getObject("expires_at", LocalDateTime.class),
                rs.getObject("merged_into_user_id") != null
            ), filter, filter, pageSize, (page - 1) * pageSize);
        return new InvitationPage(rows, page, pageSize, total == null ? 0 : total);
    }
}
