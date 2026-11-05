package com.Brafurries.API.auth.social;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserIdentityLink;
import com.Brafurries.API.entity.user.UserIdentityLinkSource;
import com.Brafurries.API.entity.user.UserIdentityLinkStatus;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserIdentityLinkRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Called exclusively after authenticated site session + verified Discord OAuth.
 * Two provider credentials owned by one person resolve to ONE operational user.
 * Original user row is preserved only for historical actor/audit provenance.
 *
 * Never copies an auth token, bypasses a ban or transfers historical actor IDs.
 */
@Service
public class VerifiedAccountConsolidationService {
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("^[a-zA-Z0-9_]+$");
    private static final Set<String> PRESERVED_TABLES = Set.of(
        "user_identity_links", "user_tokens", "user_refresh_tokens",
        "user_account_consolidations", "user_social_link_challenges",
        "user_ban_discord_effects", "config_command_uses");
    private static final Set<String> ACTOR_PREFIXES = Set.of(
        "actor_", "author_", "created_by_", "updated_by_", "linked_by_",
        "revoked_by_", "approved_by_", "applied_by_", "granted_by_",
        "assigned_by_", "requested_by_", "reviewed_by_", "decided_by_",
        "evaluated_by_", "issued_by_", "reconciled_by_", "restored_by_",
        "original_", "from_", "to_");

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final UserDiscordRepository discord;
    private final UserIdentityLinkRepository identityLinks;

    public VerifiedAccountConsolidationService(
        JdbcTemplate jdbc,
        UserRepository users,
        UserDiscordRepository discord,
        UserIdentityLinkRepository identityLinks
    ) {
        this.jdbc = jdbc;
        this.users = users;
        this.discord = discord;
        this.identityLinks = identityLinks;
    }

    @Transactional
    public User linkVerifiedDiscord(Integer authenticatedUserId, long discordUserId, String username, String displayName) {
        UserDiscord known = discord.findByDiscordUserId(discordUserId).orElse(null);
        Integer otherId = known == null ? null : known.getUser().getId();

        List<Integer> ids = new ArrayList<>();
        ids.add(authenticatedUserId);
        if (otherId != null && !otherId.equals(authenticatedUserId)) ids.add(otherId);
        ids.sort(Comparator.naturalOrder());
        java.util.Map<Integer,User> locked = new java.util.HashMap<>();
        for (Integer id : ids) {
            locked.put(id, users.findByIdForUpdate(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.CONFLICT, "Conta informada não existe")));
        }
        User actor = locked.get(authenticatedUserId);
        if (actor.getMergedIntoUserId() != null) throw conflict("Esta sessão pertence a uma conta já consolidada. Entre novamente.");
        if (known == null || otherId.equals(authenticatedUserId)) {
            ensureNoOtherDiscord(actor, discordUserId);
            UserDiscord existing = known;
            if (existing == null) {
                existing = new UserDiscord();
                existing.setUser(actor);
                existing.setDiscordUserId(discordUserId);
            }
            existing.setUsername(truncate(username, 32, "discord"));
            existing.setDisplayName(truncate(displayName, 32, null));
            discord.saveAndFlush(existing);
            rememberProvider(actor.getId(), "discord", Long.toString(discordUserId));
            return actor;
        }

        User canonical = locked.get(otherId);
        if (canonical.getMergedIntoUserId() != null) throw conflict("A conta do Discord já foi consolidada. Reinicie a vinculação.");
        ensureNoOtherDiscord(actor, discordUserId);
        String originalEmail = actor.getEmail();
        if (originalEmail == null || originalEmail.isBlank()) {
            throw conflict("A conta autenticada não possui um e-mail válido para recuperação.");
        }
        if (canonical.getPasswordHash() != null && actor.getPasswordHash() != null
            && !canonical.getPasswordHash().equals(actor.getPasswordHash())) {
            throw conflict("As contas têm credenciais locais distintas. É necessário escolher qual manter.");
        }

        // SQL writes and JPA changes are part of the SAME transactional boundary.
        // If any UNIQUE/FK check rejects a transfer, the entire consolidation rolls back.
        try {
            transferData(canonical.getId(), actor.getId());
            if (canonical.getPasswordHash() == null) canonical.setPasswordHash(actor.getPasswordHash());
            if (canonical.getProfileImageUrl() == null && actor.getProfileImageUrl() != null) {
                canonical.setProfileImageUrl(actor.getProfileImageUrl());
                canonical.setProfileImageKey(actor.getProfileImageKey());
                canonical.setProfileImageContentType(actor.getProfileImageContentType());
                canonical.setProfileImageUpdatedAt(actor.getProfileImageUpdatedAt());
            }
            jdbc.update("UPDATE user_social_link_challenges SET consumed_at=NOW(6) " +
                "WHERE initiated_user_id=? AND consumed_at IS NULL", actor.getId());
            jdbc.update("UPDATE user_refresh_tokens SET revoked_at=NOW() " +
                "WHERE user_id IN (?,?) AND revoked_at IS NULL", canonical.getId(), actor.getId());
            jdbc.update("UPDATE user_tokens SET revoked_at=NOW() " +
                "WHERE user_id IN (?,?) AND revoked_at IS NULL AND used_at IS NULL", canonical.getId(), actor.getId());

            actor.setEmail(null);
            actor.setMergedIntoUserId(canonical.getId());
            actor.setAuthVersion(actor.getAuthVersion() + 1);
            users.saveAndFlush(actor);
            canonical.setEmail(originalEmail.trim().toLowerCase(Locale.ROOT));
            canonical.setAuthVersion(canonical.getAuthVersion() + 1);
            users.saveAndFlush(canonical);
            rememberProvider(canonical.getId(), "discord", Long.toString(discordUserId));

            jdbc.update("""
                INSERT INTO user_account_consolidations
                  (canonical_user_id,absorbed_user_id,proved_discord_user_id,initiated_by_user_id)
                VALUES (?,?,?,?)
                """, canonical.getId(), actor.getId(), discordUserId, actor.getId());

            // Preserve moderation/anti-alt propagation: the absorbed user is not
            // a login identity, but its original sanctions remain traceable.
            if (identityLinks.findActiveByPair(canonical.getId(), actor.getId()).isEmpty()) {
                UserIdentityLink identity = new UserIdentityLink();
                identity.setUserA(canonical);
                identity.setUserB(actor);
                identity.setStatus(UserIdentityLinkStatus.CONFIRMED);
                identity.setLinkSource(UserIdentityLinkSource.SOCIAL);
                identity.setReason("Consolidação de contas após prova OAuth e sessão autenticada");
                identity.setCreatedByUser(actor);
                identityLinks.saveAndFlush(identity);
            } else {
                UserIdentityLink identity = identityLinks.findActiveByPair(canonical.getId(), actor.getId()).orElseThrow();
                if (identity.getStatus() != UserIdentityLinkStatus.CONFIRMED) {
                    throw conflict("Existe uma marcação administrativa de identidade suspeita que precisa ser resolvida antes da consolidação.");
                }
            }
            return canonical;
        } catch (DataIntegrityViolationException ex) {
            throw conflict("Foram encontrados dados ou permissões incompatíveis entre as contas. Nenhum dado foi alterado.");
        }
    }

    private void ensureNoOtherDiscord(User source, long provenDiscordId) {
        var linked = discord.findAllByUserIdOrderByIdAsc(source.getId());
        if (linked.stream().anyMatch(value -> value.getDiscordUserId() != provenDiscordId)) {
            throw conflict("Esta conta já possui outra identidade Discord. Confirme a posse dela antes de consolidar.");
        }
    }

    private void transferData(int to, int from) {
        // Avoid blindly updating composite-FK dependents: the member parent row
        // must first exist under the canonical user in each independent Community.
        transferCommunityMemberships(to, from);
        dedupeRoleGrants(to, from);

        List<Map<String,Object>> refs = jdbc.queryForList("""
            SELECT TABLE_NAME AS table_name, COLUMN_NAME AS column_name
            FROM information_schema.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA=DATABASE()
              AND REFERENCED_TABLE_NAME='users'
              AND REFERENCED_COLUMN_NAME='id'
            ORDER BY TABLE_NAME, ORDINAL_POSITION
            """);
        Set<String> transferred = new HashSet<>();
        for (Map<String,Object> ref : refs) {
            String table = String.valueOf(ref.get("table_name"));
            String column = String.valueOf(ref.get("column_name"));
            if (!isSubjectField(table,column)) continue;
            if (!SQL_IDENTIFIER.matcher(table).matches() || !SQL_IDENTIFIER.matcher(column).matches()) {
                throw conflict("Estrutura de referência não reconhecida na consolidação.");
            }
            String identifier = table + "." + column;
            if (!transferred.add(identifier)) continue;
            jdbc.update("UPDATE `" + table + "` SET `" + column + "`=? WHERE `" + column + "`=?", to, from);
        }
        jdbc.update("DELETE FROM user_community_status WHERE user_id=?", from);
    }

    private boolean isSubjectField(String table, String column) {
        if (PRESERVED_TABLES.contains(table) || table.equals("users")
            || table.equals("user_discord") || table.equals("user_community_status")) return false;
        if (column.equals("user_id") || column.equals("owner_user_id")
            || column.equals("host_user_id") || column.equals("target_user_id")
            || column.equals("candidate_user_id") || column.equals("applicant_user_id")
            || column.equals("member_user_id") || column.equals("buyer_user_id")) return true;
        for (String prefix : ACTOR_PREFIXES) if (column.startsWith(prefix)) return false;
        // Unknown FK roles are historical references until explicitly reviewed.
        return false;
    }

    private void transferCommunityMemberships(int to, int from) {
        List<String> columns = jdbc.queryForList("""
            SELECT COLUMN_NAME
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA=DATABASE()
              AND TABLE_NAME='user_community_status'
              AND COLUMN_NAME NOT IN ('id','user_id')
              AND EXTRA NOT LIKE '%auto_increment%'
              AND EXTRA NOT LIKE '%GENERATED%'
            ORDER BY ORDINAL_POSITION
            """, String.class);
        if (columns.isEmpty()) throw conflict("Estrutura de comunidades indisponível.");
        for (String column : columns)
            if (!SQL_IDENTIFIER.matcher(column).matches()) throw conflict("Campo de comunidade desconhecido.");
        String equality = columns.stream()
            .map(column -> "s.`" + column + "` <=> t.`" + column + "`")
            .collect(java.util.stream.Collectors.joining(" AND "));
        Integer divergent = jdbc.queryForObject(
            "SELECT COUNT(*) FROM user_community_status s JOIN user_community_status t " +
            "ON s.community_id=t.community_id WHERE s.user_id=? AND t.user_id=? " +
            "AND NOT (" + equality + ")", Integer.class, from, to);
        if (divergent != null && divergent > 0)
            throw conflict("Os perfis têm informações divergentes na mesma Community. Escolha qual manter antes da consolidação.");

        String quoted = columns.stream().map(c -> "`" + c + "`")
            .collect(java.util.stream.Collectors.joining(","));
        String projected = columns.stream().map(c -> "s.`" + c + "`")
            .collect(java.util.stream.Collectors.joining(","));
        jdbc.update("INSERT INTO user_community_status (`user_id`," + quoted + ") " +
            "SELECT ?," + projected + " FROM user_community_status s " +
            "WHERE s.user_id=? AND NOT EXISTS (SELECT 1 FROM user_community_status t " +
            "WHERE t.community_id=s.community_id AND t.user_id=?)", to, from, to);
    }

    private void dedupeRoleGrants(int to, int from) {
        jdbc.update("""
            DELETE src FROM api_user_roles src JOIN api_user_roles dst
              ON src.role_id=dst.role_id AND dst.user_id=?
             WHERE src.user_id=?
            """, to, from);
        jdbc.update("""
            DELETE src FROM platform_user_permissions src JOIN platform_user_permissions dst
              ON src.permission_code=dst.permission_code AND dst.user_id=?
             WHERE src.user_id=?
            """, to, from);
        jdbc.update("""
            DELETE src FROM platform_authorized_users src JOIN platform_authorized_users dst
              ON dst.user_id=?
             WHERE src.user_id=?
            """, to, from);
        jdbc.update("""
            DELETE src FROM community_team_role_assignments src
             JOIN community_team_role_assignments dst
               ON src.community_id=dst.community_id AND src.role_id=dst.role_id
              AND dst.user_id=?
            WHERE src.user_id=?
            """, to, from);
        jdbc.update("""
            DELETE src FROM community_user_capability_grants src
             JOIN community_user_capability_grants dst
               ON src.community_id=dst.community_id AND src.capability=dst.capability
              AND dst.user_id=?
            WHERE src.user_id=?
            """, to, from);
    }

    private void rememberProvider(int userId, String provider, String subject) {
        Integer assigned = jdbc.queryForObject("""
            SELECT COUNT(*) FROM user_social_identities
            WHERE provider=? AND provider_subject=? AND user_id<>?
            """, Integer.class, provider, subject, userId);
        if (assigned != null && assigned > 0)
            throw conflict("Esta identidade OAuth já pertence a outra conta.");
        jdbc.update("""
            INSERT INTO user_social_identities (user_id,provider,provider_subject,verified_email)
            VALUES (?,?,?,NULL)
            ON DUPLICATE KEY UPDATE last_verified_at=NOW(6)
            """, userId, provider, subject);
    }

    private String truncate(String value, int maxLength, String fallback) {
        String safe = value == null || value.isBlank() ? fallback : value.trim();
        return safe == null || safe.length() <= maxLength ? safe : safe.substring(0, maxLength);
    }

    private ResponseStatusException conflict(String detail) {
        return new ResponseStatusException(HttpStatus.CONFLICT, detail);
    }
}
