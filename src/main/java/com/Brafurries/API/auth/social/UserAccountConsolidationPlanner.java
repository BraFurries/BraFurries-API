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
 * For a sparse Google account and a historical Discord account, the Discord
 * owner is the preferred canonical user: Community/XP/moderation history
 * remains attached to its original user_id, while verified Google subjects
 * can eventually be moved through a separate audited, locked transaction.
 * This planner NEVER updates the database or authorizes a merge by itself.
 */
@Component
public class UserAccountConsolidationPlanner {
    private static final Set<String> LEGACY_PRESERVED_DOMAINS = Set.of(
        "COMMUNITY", "MODERATION", "PROGRESSION", "ASSETS", "IDENTITY");
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
        if ("REVIEW_REQUIRED".equals(findings.status())) {
            return new Plan("REVIEW_REQUIRED", "UNDECIDED", List.of(),
                findings.nextActions());
        }
        List<String> conflicts = new ArrayList<>();
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
                conflicts.add("REVIEW_ACCESS_GRANTS");
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
