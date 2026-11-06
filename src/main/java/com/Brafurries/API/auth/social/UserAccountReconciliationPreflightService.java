package com.Brafurries.API.auth.social;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Non-mutating inventory for an already OAuth-proven pair of users.
 *
 * A preflight result is NEVER a merge authorization: the transfer policies,
 * optimistic revalidation and complete atomic consolidation are separate work.
 * The catalog is checked at runtime so newly-added user-owned tables cannot
 * silently disappear from the reconciliation assessment.
 */
@Service
public class UserAccountReconciliationPreflightService {
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final int MAX_REFERENCES = 400;

    private static final String USER_REFERENCES = """
        SELECT c.TABLE_NAME AS table_name,
               c.COLUMN_NAME AS column_name,
               CASE WHEN EXISTS (
                   SELECT 1 FROM information_schema.STATISTICS s
                    WHERE s.TABLE_SCHEMA = c.TABLE_SCHEMA
                      AND s.TABLE_NAME = c.TABLE_NAME
                      AND s.COLUMN_NAME = c.COLUMN_NAME
                      AND s.SEQ_IN_INDEX = 1
               ) THEN 1 ELSE 0 END AS leading_index
          FROM information_schema.COLUMNS c
          JOIN information_schema.TABLES t
            ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
         WHERE c.TABLE_SCHEMA = DATABASE()
           AND t.TABLE_TYPE = 'BASE TABLE'
           AND (c.COLUMN_NAME = 'user_id' OR RIGHT(c.COLUMN_NAME, 8) = '_user_id' OR EXISTS (
               SELECT 1 FROM information_schema.KEY_COLUMN_USAGE k
                WHERE k.TABLE_SCHEMA = c.TABLE_SCHEMA
                  AND k.TABLE_NAME = c.TABLE_NAME
                  AND k.COLUMN_NAME = c.COLUMN_NAME
                  AND k.REFERENCED_TABLE_SCHEMA = DATABASE()
                  AND k.REFERENCED_TABLE_NAME = 'users'
                  AND k.REFERENCED_COLUMN_NAME = 'id'
           ))
           AND (
              NOT (c.COLUMN_NAME IN ('discord_user_id', 'patreon_user_id',
                       'telegram_user_id', 'disc_user_id')
                  OR RIGHT(c.COLUMN_NAME, 16) = '_discord_user_id'
                  OR (c.TABLE_NAME IN ('form_submissions', 'portaria_account_release')
                      AND c.COLUMN_NAME='user_id'))
              OR EXISTS(SELECT 1 FROM information_schema.KEY_COLUMN_USAGE site_fk
                        WHERE site_fk.TABLE_SCHEMA=c.TABLE_SCHEMA
                          AND site_fk.TABLE_NAME=c.TABLE_NAME
                          AND site_fk.COLUMN_NAME=c.COLUMN_NAME
                          AND site_fk.REFERENCED_TABLE_SCHEMA=DATABASE()
                          AND site_fk.REFERENCED_TABLE_NAME='users'
                          AND site_fk.REFERENCED_COLUMN_NAME='id')
           )
         ORDER BY c.TABLE_NAME, c.COLUMN_NAME
        """;

    private final UserCommunityStatusRepository memberships;
    private final JdbcTemplate jdbc;

    public UserAccountReconciliationPreflightService(
        UserCommunityStatusRepository memberships, JdbcTemplate jdbc
    ) {
        this.memberships = memberships;
        this.jdbc = jdbc;
    }

    public record CommunityImpact(
        Integer communityId,
        boolean websiteMembership,
        boolean discordMembership,
        boolean containsRestriction,
        boolean containsSpecialRole,
        boolean needsDuplicateReview
    ) {}

    public record DomainImpact(
        String domain,
        boolean websiteHasRecords,
        boolean discordHasRecords,
        boolean bothHaveRecords,
        boolean includesHistoricalReferences
    ) {}

    public record Findings(
        String status,
        List<String> nextActions,
        List<CommunityImpact> communities,
        List<DomainImpact> domains
    ) {}

    @Transactional(readOnly = true)
    public Findings inspect(User website, User discordOwner) {
        if (website == null || discordOwner == null || website.getId() == null
            || discordOwner.getId() == null || website.getId().equals(discordOwner.getId())) {
            throw new IllegalArgumentException("Reconciliation requires two distinct verified users");
        }

        Map<Integer, Scope> scopes = new LinkedHashMap<>();
        boolean[] uncertainScope = {false};
        collectMemberships(memberships.findByUser(website), true, scopes, uncertainScope);
        collectMemberships(memberships.findByUser(discordOwner), false, scopes, uncertainScope);

        List<CommunityImpact> communities = scopes.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> {
                Scope scope = entry.getValue();
                return new CommunityImpact(entry.getKey(), scope.web, scope.discord,
                    scope.restricted, scope.special, scope.duplicates);
            }).toList();

        Map<String, DomainAccumulator> domains = new LinkedHashMap<>();
        List<Map<String, Object>> references = jdbc.queryForList(USER_REFERENCES);
        boolean unscanned = uncertainScope[0] || references.size() > MAX_REFERENCES;
        int examined = 0;
        for (Map<String, Object> reference : references) {
            if (++examined > MAX_REFERENCES) break;
            Object tableObject = reference.get("table_name");
            Object columnObject = reference.get("column_name");
            if (!(tableObject instanceof String table) || !(columnObject instanceof String column)
                || !SAFE_IDENTIFIER.matcher(table).matches()
                || !SAFE_IDENTIFIER.matcher(column).matches()) {
                // Invalid metadata is never interpolated into a SQL identifier.
                unscanned = true;
                continue;
            }
            String category = category(table);
            String normalizedColumn = column.toLowerCase(Locale.ROOT);
            // Only explicit authorship/audit columns are historical; arbitrary
            // *owner_user_id or *recipient_user_id references may carry grants,
            // ownership or balances and must not be silently ignored.
            boolean historical = historicalActorColumn(normalizedColumn);
            // Challenge ownership is an ephemeral recovery token, not a
            // transferable Community record. Its replay/state protection
            // remains in the existing OAuth challenge workflow.
            boolean knownChallenge = "user_discord_link_challenges".equalsIgnoreCase(table)
                && "requesting_user_id".equals(normalizedColumn);
            boolean unknownRelationship = !"user_id".equals(normalizedColumn)
                && !historical && !knownChallenge;
            String query = "SELECT (EXISTS(SELECT 1 FROM `" + table + "` WHERE `" + column
                + "` = ? LIMIT 1)) + 2 * (EXISTS(SELECT 1 FROM `" + table
                + "` WHERE `" + column + "` = ? LIMIT 1))";
            Number mask = jdbc.queryForObject(query, Number.class, website.getId(), discordOwner.getId());
            if (mask == null) {
                unscanned = true;
                continue;
            }
            int presence = mask.intValue();
            if (presence < 0 || presence > 3) {
                unscanned = true;
                continue;
            }
            if (presence == 0) continue;
            if (unknownRelationship) unscanned = true;
            DomainAccumulator acc = domains.computeIfAbsent(category, key -> new DomainAccumulator());
            acc.web |= (presence & 1) != 0;
            acc.discord |= (presence & 2) != 0;
            acc.historical |= historical;
            // Same reference column on both users means a reconciliation policy
            // must be checked. It does NOT prove conflicting data in one tenant.
            acc.both |= presence == 3 && !historical;
        }

        List<DomainImpact> data = domains.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> new DomainImpact(entry.getKey(), entry.getValue().web,
                entry.getValue().discord, entry.getValue().both, entry.getValue().historical))
            .toList();

        boolean restricted = communities.stream().anyMatch(CommunityImpact::containsRestriction);
        // A domain not yet classified by a migration policy is never
        // implicitly safe, even when populated on only one account.
        boolean unknownDomain = domains.containsKey("OTHER");
        if (unknownDomain) unscanned = true;
        boolean privileged = communities.stream().anyMatch(CommunityImpact::containsSpecialRole)
            || domains.containsKey("AUTHORIZATION");
        boolean overlap = communities.stream().anyMatch(impact ->
            (impact.websiteMembership() && impact.discordMembership()) || impact.needsDuplicateReview());
        boolean dualDomain = domains.values().stream().anyMatch(acc -> acc.both);
        boolean websiteOperationalData = domains.entrySet().stream().anyMatch(entry ->
            entry.getValue().web && !Set.of("IDENTITY", "CREDENTIALS", "PROVENANCE")
                .contains(entry.getKey()));
        Set<String> actions = new LinkedHashSet<>();
        String status;
        if (unscanned || restricted || privileged) {
            status = "REVIEW_REQUIRED";
            if (unscanned) actions.add("COMPLETE_REFERENCE_INVENTORY");
            if (restricted) actions.add("PRESERVE_COMMUNITY_RESTRICTIONS");
            if (privileged) actions.add("REVIEW_ACCESS_GRANTS");
            actions.add("REQUEST_IDENTITY_REVIEW");
        } else if (overlap || dualDomain || websiteOperationalData) {
            status = "RESOLUTION_REQUIRED";
            if (overlap) actions.add("RECONCILE_COMMUNITY_MEMBERSHIP");
            if (dualDomain || websiteOperationalData) actions.add("RECONCILE_DOMAIN_DATA");
            actions.add("CHECK_ACCOUNT_CONFLICTS");
        } else {
            status = "PRECHECK_REQUIRED";
            actions.add("CHECK_ACCOUNT_CONFLICTS");
        }
        return new Findings(status, List.copyOf(actions), communities, data);
    }

    private void collectMemberships(
        List<UserCommunityStatus> records, boolean website, Map<Integer, Scope> scopes,
        boolean[] uncertain
    ) {
        for (UserCommunityStatus row : records) {
            if (row.getCommunity() == null || row.getCommunity().getId() == null) {
                uncertain[0] = true;
                continue;
            }
            Scope scope = scopes.computeIfAbsent(row.getCommunity().getId(), ignored -> new Scope());
            if (website) {
                if (scope.web) scope.duplicates = true;
                scope.web = true;
            } else {
                if (scope.discord) scope.duplicates = true;
                scope.discord = true;
            }
            scope.restricted |= Boolean.TRUE.equals(row.getBanned());
            scope.special |= Boolean.TRUE.equals(row.getIsVip())
                || Boolean.TRUE.equals(row.getIsPartner());
        }
    }

    private static boolean historicalActorColumn(String column) {
        return Set.of("applied_by", "revoked_by", "created_by", "updated_by",
            "reviewed_by", "approved_by", "deleted_by", "initiated_by_user_id",
            "created_by_user_id", "updated_by_user_id", "approved_by_user_id",
            "revoked_by_user_id", "applied_by_user_id", "performed_by_user_id",
            "actor_user_id").contains(column);
    }

    private static String category(String table) {
        String name = table.toLowerCase(Locale.ROOT);
        if (Set.of("user_discord", "user_social_identities", "user_identity_links").contains(name)) {
            return "IDENTITY";
        }
        if (name.contains("challenge") || name.contains("consolidation") || name.contains("audit")) {
            return "PROVENANCE";
        }
        if (name.equals("user_community_status") || name.contains("membership")) return "COMMUNITY";
        if (name.contains("ban") || name.contains("warn") || name.contains("moderation")
            || name.contains("user_notes")) return "MODERATION";
        if (name.contains("role") || name.contains("permission") || name.contains("access")
            || name.contains("staff") || name.contains("team_assignment")) return "AUTHORIZATION";
        if (name.contains("token") || name.contains("credential") || name.contains("session")) {
            return "CREDENTIALS";
        }
        if (name.contains("economy") || name.contains("inventory") || name.contains("payment")
            || name.contains("sale") || name.contains("cash") || name.contains("wallet")) {
            return "ASSETS";
        }
        if (name.contains("xp") || name.contains("level") || name.contains("records")) {
            return "PROGRESSION";
        }
        return "OTHER";
    }

    private static final class Scope {
        boolean web;
        boolean discord;
        boolean restricted;
        boolean special;
        boolean duplicates;
    }

    private static final class DomainAccumulator {
        boolean web;
        boolean discord;
        boolean both;
        boolean historical;
    }
}
