package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.social.UserAccountReconciliationPreflightService.DomainImpact;
import com.Brafurries.API.auth.social.UserAccountReconciliationPreflightService.Findings;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Policy proposal, not an execution permission.
 *
 * For a sparse website account (local password and/or verified OAuth) and a historical Discord account, the Discord
 * owner is the preferred canonical user: Community/XP/moderation history
 * remains attached to its original user_id, while locally-owned login credentials and independently verified OAuth
 * subjects can be transferred by the guarded consolidation transaction.
 * This planner NEVER updates the database or authorizes a merge by itself.
 */
@Component
public class UserAccountConsolidationPlanner {
    private static final Set<String> LEGACY_PRESERVED_DOMAINS = Set.of(
        "COMMUNITY", "MODERATION", "PROGRESSION", "ASSETS", "IDENTITY",
        "AUTHORIZATION");
    private static final Set<String> WEBSITE_RECOVERABLE_DOMAINS = Set.of(
        "IDENTITY", "CREDENTIALS", "PROVENANCE");

    public record Plan(
        String status,
        String preferredCanonicalAccount,
        List<String> preservationPolicies,
        List<String> nextActions
    ) {}

    public Plan propose(Findings findings) {
        if (findings == null) {
            throw new IllegalArgumentException("Verified-account preflight is required");
        }
        if (!"PRECHECK_REQUIRED".equals(findings.status())
            && !"RESOLUTION_REQUIRED".equals(findings.status())) {
            return new Plan("REVIEW_REQUIRED", "UNDECIDED", List.of(),
                findings.nextActions());
        }
        // The preflight may have detected user-owned rows in a community
        // even if a future domain has not yet been mapped to transfer rules.
        // Never replace its blocking verdict with an optimistic auto-merge.
        List<String> conflicts = new ArrayList<>();
        if ("RESOLUTION_REQUIRED".equals(findings.status())) {
            conflicts.addAll(findings.nextActions());
            if (conflicts.isEmpty()) conflicts.add("RECONCILE_WEBSITE_OWNERSHIP");
        }
        List<String> policies = new ArrayList<>();
        boolean overlappingCommunity = findings.communities().stream().anyMatch(c ->
            c.websiteMembership() && c.discordMembership() || c.needsDuplicateReview());
        if (overlappingCommunity) conflicts.add("RECONCILE_COMMUNITY_MEMBERSHIP");

        for (DomainImpact domain : findings.domains()) {
            if (!LEGACY_PRESERVED_DOMAINS.contains(domain.domain())
                && !WEBSITE_RECOVERABLE_DOMAINS.contains(domain.domain())) {
                conflicts.add("REVIEW_UNCLASSIFIED_DOMAIN");
                continue;
            }
            if ("AUTHORIZATION".equals(domain.domain())) {
                if (domain.websiteHasRecords()) {
                    // Never transfer roles/permissions from the absorbed user
                    // into the canonical Discord account without review.
                    conflicts.add("REVIEW_ACCESS_GRANTS");
                } else if (domain.discordHasRecords()) {
                    // Existing grants already belong to the canonical owner;
                    // no authorization is copied or elevated.
                    policies.add("PRESERVE_DISCORD_AUTHORIZATION_IN_PLACE");
                }
                continue;
            }
            if (domain.websiteHasRecords()
                && !WEBSITE_RECOVERABLE_DOMAINS.contains(domain.domain())) {
                conflicts.add("RECONCILE_" + domain.domain() + "_ON_WEBSITE");
                continue;
            }
            if (domain.bothHaveRecords() && !"IDENTITY".equals(domain.domain())
                && !"CREDENTIALS".equals(domain.domain())
                && !"PROVENANCE".equals(domain.domain())) {
                conflicts.add("RECONCILE_" + domain.domain() + "_ON_BOTH");
                continue;
            }
            if (domain.discordHasRecords() && LEGACY_PRESERVED_DOMAINS.contains(domain.domain())) {
                policies.add("PRESERVE_DISCORD_" + domain.domain() + "_IN_PLACE");
            }
            if ("CREDENTIALS".equals(domain.domain())) {
                policies.add("REVOKE_OLD_SESSIONS_AND_RECOVERY_TOKENS");
            }
        }
        if (!conflicts.isEmpty()) {
            return new Plan("RESOLUTION_REQUIRED", "UNDECIDED", List.copyOf(policies),
                conflicts.stream().distinct().toList());
        }

        policies.add("KEEP_LEGACY_AUDIT_AND_MODERATION_ACTOR_REFERENCES");
        policies.add("REVALIDATE_ALL_COMMUNITIES_UNDER_LOCK");
        policies.add("TRANSFER_ONLY_VERIFIED_GOOGLE_SUBJECTS");
        policies.add("INVALIDATE_BOTH_ACCOUNT_SESSIONS");
        return new Plan("AUTO_MERGE_CANDIDATE", "DISCORD_HISTORY_OWNER",
            policies.stream().distinct().toList(),
            List.of("VERIFY_CANONICAL_ACCOUNT_AND_TRANSACTION_POLICY"));
    }
}
