package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.social.UserAccountReconciliationPreflightService.CommunityImpact;
import com.Brafurries.API.auth.social.UserAccountReconciliationPreflightService.DomainImpact;
import com.Brafurries.API.auth.social.UserAccountReconciliationPreflightService.Findings;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserAccountConsolidationPlannerTest {
    private final UserAccountConsolidationPlanner planner = new UserAccountConsolidationPlanner();

    private DomainImpact domain(String name, boolean web, boolean discord, boolean both) {
        return new DomainImpact(name, web, discord, both, false);
    }

    @Test
    void historicalDiscordAccountCanBeCanonicalWhenWebsiteAccountHasOnlyIdentityProof() {
        var scan = new Findings("PRECHECK_REQUIRED",
            List.of("CHECK_ACCOUNT_CONFLICTS"),
            List.of(new CommunityImpact(1, false, true, false, false, false)),
            List.of(domain("IDENTITY", true, true, false),
                domain("COMMUNITY", false, true, false),
                domain("ASSETS", false, true, false),
                domain("PROGRESSION", false, true, false),
                domain("CREDENTIALS", true, false, false)));

        var proposal = planner.propose(scan);

        assertEquals("AUTO_MERGE_CANDIDATE", proposal.status());
        assertEquals("DISCORD_HISTORY_OWNER", proposal.preferredCanonicalAccount());
        assertTrue(proposal.preservationPolicies().contains("PRESERVE_DISCORD_ASSETS_IN_PLACE"));
        assertTrue(proposal.preservationPolicies().contains("INVALIDATE_BOTH_ACCOUNT_SESSIONS"));
        assertTrue(proposal.nextActions().contains("VERIFY_CANONICAL_ACCOUNT_AND_TRANSACTION_POLICY"));
    }

    @Test
    void ownEconomyDataInWebsiteAccountRequiresExplicitConflictPolicy() {
        var scan = new Findings("RESOLUTION_REQUIRED", List.of(),
            List.of(), List.of(domain("ASSETS", true, true, true)));

        var proposal = planner.propose(scan);

        assertEquals("RESOLUTION_REQUIRED", proposal.status());
        assertEquals("UNDECIDED", proposal.preferredCanonicalAccount());
        assertTrue(proposal.nextActions().contains("RECONCILE_ASSETS_ON_WEBSITE"));
    }

    @Test
    void duplicateCommunityMembershipIsRecoverableButNotMergeReady() {
        var scan = new Findings("RESOLUTION_REQUIRED", List.of(),
            List.of(new CommunityImpact(1, true, true, false, false, false)),
            List.of(domain("COMMUNITY", true, true, true)));

        var proposal = planner.propose(scan);

        assertEquals("RESOLUTION_REQUIRED", proposal.status());
        assertTrue(proposal.nextActions().contains("RECONCILE_COMMUNITY_MEMBERSHIP"));
    }

    @Test
    void privilegesOrUnknownReferencesRequireReviewWithoutExposingMergeOperation() {
        var scan = new Findings("REVIEW_REQUIRED",
            List.of("COMPLETE_REFERENCE_INVENTORY", "REQUEST_IDENTITY_REVIEW"),
            List.of(), List.of(domain("OTHER", true, false, false)));

        var proposal = planner.propose(scan);

        assertEquals("REVIEW_REQUIRED", proposal.status());
        assertEquals("UNDECIDED", proposal.preferredCanonicalAccount());
        assertEquals(scan.nextActions(), proposal.nextActions());
    }

    @Test
    void existingDiscordVipAndBanAreRetainedWithoutCopyingRoles() {
        var scan = new Findings("PRECHECK_REQUIRED", List.of("CHECK_ACCOUNT_CONFLICTS"),
            List.of(new CommunityImpact(1, false, true, true, true, false)),
            List.of(domain("COMMUNITY", false, true, false),
                domain("AUTHORIZATION", false, true, false),
                domain("MODERATION", false, true, false)));

        var proposal = planner.propose(scan);

        assertEquals("AUTO_MERGE_CANDIDATE", proposal.status());
        assertEquals("DISCORD_HISTORY_OWNER", proposal.preferredCanonicalAccount());
        assertTrue(proposal.preservationPolicies().contains("PRESERVE_DISCORD_AUTHORIZATION_IN_PLACE"));
        assertTrue(proposal.preservationPolicies().contains("PRESERVE_DISCORD_MODERATION_IN_PLACE"));
    }

    @Test
    void preflightConflictCanNeverBecomeAutoMergeJustBecauseDomainsAreEmpty() {
        var scan = new Findings("RESOLUTION_REQUIRED",
            List.of("TRANSFER_WEBSITE_COMMUNITY_DATA"),
            List.of(new CommunityImpact(42, true, false, false, false, false)),
            List.of());

        var proposal = planner.propose(scan);

        assertEquals("RESOLUTION_REQUIRED", proposal.status());
        assertEquals("UNDECIDED", proposal.preferredCanonicalAccount());
        assertTrue(proposal.nextActions().contains("TRANSFER_WEBSITE_COMMUNITY_DATA"));
    }

    @Test
    void unknownPreflightStateRequiresReviewInsteadOfOptimisticMerge() {
        var scan = new Findings("UNEXPECTED_STATE", List.of("REQUEST_IDENTITY_REVIEW"),
            List.of(), List.of());

        var proposal = planner.propose(scan);

        assertEquals("REVIEW_REQUIRED", proposal.status());
        assertEquals("UNDECIDED", proposal.preferredCanonicalAccount());
    }

    @Test
    void missingVerifiedPreflightIsNeverAnImplicitApproval() {
        assertThrows(IllegalArgumentException.class, () -> planner.propose(null));
    }
}
