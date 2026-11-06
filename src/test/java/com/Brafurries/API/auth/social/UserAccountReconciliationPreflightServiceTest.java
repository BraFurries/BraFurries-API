package com.Brafurries.API.auth.social;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserAccountReconciliationPreflightServiceTest {
    @Mock UserCommunityStatusRepository memberships;
    @Mock JdbcTemplate jdbc;

    private UserAccountReconciliationPreflightService service() {
        return new UserAccountReconciliationPreflightService(memberships, jdbc);
    }

    private User user(int id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    private UserCommunityStatus membership(int communityId, boolean banned, boolean partner) {
        Community c = new Community();
        c.setId(communityId);
        UserCommunityStatus status = new UserCommunityStatus();
        status.setCommunity(c);
        status.setBanned(banned);
        status.setIsPartner(partner);
        status.setIsVip(false);
        return status;
    }

    private Map<String, Object> reference(String table, String column, int indexed) {
        return Map.of("table_name", table, "column_name", column, "leading_index", indexed);
    }

    @Test
    void websiteOnlyCommunityMembershipStillNeedsTransferPolicyEvenWithoutOverlap() {
        User website = user(10), legacy = user(20);
        when(memberships.findByUser(website)).thenReturn(List.of(membership(1, false, false)));
        when(memberships.findByUser(legacy)).thenReturn(List.of(membership(2, false, false)));
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        var result = service().inspect(website, legacy);

        assertEquals("RESOLUTION_REQUIRED", result.status());
        assertEquals(2, result.communities().size());
        assertTrue(result.communities().stream().noneMatch(
            scope -> scope.websiteMembership() && scope.discordMembership()));
        assertTrue(result.nextActions().contains("TRANSFER_WEBSITE_COMMUNITY_DATA"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void sameCommunityOnBothAccountsRequestsReconciliationWithoutTreatingItAsFraud() {
        User website = user(10), legacy = user(20);
        when(memberships.findByUser(website)).thenReturn(List.of(membership(1, false, false)));
        when(memberships.findByUser(legacy)).thenReturn(List.of(membership(1, false, false)));
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        var result = service().inspect(website, legacy);

        assertEquals("RESOLUTION_REQUIRED", result.status());
        assertEquals(1, result.communities().size());
        assertTrue(result.communities().getFirst().websiteMembership());
        assertTrue(result.communities().getFirst().discordMembership());
        assertTrue(result.nextActions().contains("RECONCILE_COMMUNITY_MEMBERSHIP"));
    }

    @Test
    void anyCommunityRestrictionOrPartnerGrantIsFlaggedForSafePreservation() {
        User website = user(10), legacy = user(20);
        when(memberships.findByUser(website)).thenReturn(List.of(membership(1, true, false)));
        when(memberships.findByUser(legacy)).thenReturn(List.of(membership(1, false, true)));
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        var result = service().inspect(website, legacy);

        assertEquals("REVIEW_REQUIRED", result.status());
        assertTrue(result.nextActions().contains("PRESERVE_COMMUNITY_RESTRICTIONS"));
        assertTrue(result.nextActions().contains("REVIEW_ACCESS_GRANTS"));
        assertTrue(result.communities().getFirst().containsRestriction());
        assertTrue(result.communities().getFirst().containsSpecialRole());
    }

    @Test
    void discordOnlyVipOrBanIsPreservedWithoutArtificialConflict() {
        User website = user(10), legacy = user(20);
        when(memberships.findByUser(website)).thenReturn(List.of());
        when(memberships.findByUser(legacy))
            .thenReturn(List.of(membership(1, true, true)));
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
            reference("api_user_roles", "user_id", 1)));
        when(jdbc.queryForObject(anyString(), eq(Number.class), eq(10), eq(20)))
            .thenReturn(2);

        var result = service().inspect(website, legacy);

        assertEquals("PRECHECK_REQUIRED", result.status());
        assertTrue(result.communities().getFirst().containsRestriction());
        assertTrue(result.communities().getFirst().containsSpecialRole());
        assertEquals("AUTHORIZATION", result.domains().getFirst().domain());
        assertFalse(result.domains().getFirst().websiteHasRecords());
        assertFalse(result.nextActions().contains("REVIEW_ACCESS_GRANTS"));
        assertFalse(result.nextActions().contains("PRESERVE_COMMUNITY_RESTRICTIONS"));
    }

    @Test
    void websiteOnlyVipOrBanStillRequiresReviewAndIsNotSilentlyTransferred() {
        User website = user(10), legacy = user(20);
        when(memberships.findByUser(website))
            .thenReturn(List.of(membership(1, true, true)));
        when(memberships.findByUser(legacy)).thenReturn(List.of());
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        var result = service().inspect(website, legacy);

        assertEquals("REVIEW_REQUIRED", result.status());
        assertTrue(result.nextActions().contains("REVIEW_ACCESS_GRANTS"));
        assertTrue(result.nextActions().contains("PRESERVE_COMMUNITY_RESTRICTIONS"));
    }

    @Test
    void twoEconomyOwnershipReferencesAreDetectedWithoutAssumingBalancesCanBeSummed() {
        User website = user(10), legacy = user(20);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(reference("user_economy", "user_id", 1)));
        when(jdbc.queryForObject(anyString(), eq(Number.class), eq(10), eq(20))).thenReturn(3);

        var result = service().inspect(website, legacy);

        assertEquals("RESOLUTION_REQUIRED", result.status());
        assertEquals("ASSETS", result.domains().getFirst().domain());
        assertTrue(result.domains().getFirst().bothHaveRecords());
        assertTrue(result.nextActions().contains("RECONCILE_DOMAIN_DATA"));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void platformRoleReferenceTriggersPrivilegeReviewEvenOnOneAccount() {
        User website = user(10), legacy = user(20);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(reference("api_user_roles", "user_id", 1)));
        when(jdbc.queryForObject(anyString(), eq(Number.class), eq(10), eq(20))).thenReturn(1);

        var result = service().inspect(website, legacy);

        assertEquals("REVIEW_REQUIRED", result.status());
        assertTrue(result.nextActions().contains("REVIEW_ACCESS_GRANTS"));
        assertEquals("AUTHORIZATION", result.domains().getFirst().domain());
    }

    @Test
    void unindexedOrUnsafeMetadataDoesNotPretendThatTheAccountHasNoReferences() {
        User website = user(10), legacy = user(20);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
            reference("user_unindexed", "user_id", 0),
            reference("unsafe`table", "user_id", 1)));
        when(jdbc.queryForObject(anyString(), eq(Number.class), eq(10), eq(20))).thenReturn(0);

        var result = service().inspect(website, legacy);

        assertEquals("REVIEW_REQUIRED", result.status());
        assertTrue(result.nextActions().contains("COMPLETE_REFERENCE_INVENTORY"));
        assertTrue(result.domains().isEmpty());
        verify(jdbc).queryForObject(anyString(), eq(Number.class), eq(10), eq(20));
    }

    @Test
    void absentRowsInLegacyUnindexedTableDoNotCreateArtificialConflict() {
        User website = user(10), legacy = user(20);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
            reference("community_member_notes", "user_id", 0)));
        when(jdbc.queryForObject(anyString(), eq(Number.class), eq(10), eq(20))).thenReturn(0);

        var result = service().inspect(website, legacy);

        assertEquals("PRECHECK_REQUIRED", result.status());
        assertTrue(result.domains().isEmpty());
    }

    @Test
    void websiteModerationRowsRequireResolutionEvenWhenDiscordHasNone() {
        User website = user(10), legacy = user(20);
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
            reference("community_member_notes", "user_id", 0)));
        when(jdbc.queryForObject(anyString(), eq(Number.class), eq(10), eq(20))).thenReturn(1);

        var result = service().inspect(website, legacy);

        assertEquals("RESOLUTION_REQUIRED", result.status());
        assertTrue(result.nextActions().contains("RECONCILE_DOMAIN_DATA"));
    }

    @Test
    void historicalActorReferencesAreNotTreatedAsOwnershipConflicts() {
        User website = user(10), legacy = user(20);
        when(jdbc.queryForList(anyString())).thenReturn(
            List.of(reference("user_warnings", "applied_by", 1)));
        when(jdbc.queryForObject(anyString(), eq(Number.class), eq(10), eq(20))).thenReturn(3);

        var result = service().inspect(website, legacy);

        assertEquals("PRECHECK_REQUIRED", result.status());
        assertEquals("MODERATION", result.domains().getFirst().domain());
        assertTrue(result.domains().getFirst().includesHistoricalReferences());
        assertFalse(result.domains().getFirst().bothHaveRecords());
    }

    @Test
    void unknownOwnershipColumnCannotBeMisclassifiedAsHistoricalAudit() {
        User website = user(10), legacy = user(20);
        when(jdbc.queryForList(anyString())).thenReturn(
            List.of(reference("user_property_claims", "owner_user_id", 1)));
        when(jdbc.queryForObject(anyString(), eq(Number.class), eq(10), eq(20))).thenReturn(2);

        var result = service().inspect(website, legacy);

        assertEquals("REVIEW_REQUIRED", result.status());
        assertTrue(result.nextActions().contains("COMPLETE_REFERENCE_INVENTORY"));
        assertFalse(result.domains().getFirst().includesHistoricalReferences());
    }

    @Test
    void newUnknownDomainMustBeReconciledEvenIfOnlyOneUserHasRows() {
        User website = user(10), legacy = user(20);
        when(jdbc.queryForList(anyString())).thenReturn(
            List.of(reference("new_unclassified_feature", "user_id", 1)));
        when(jdbc.queryForObject(anyString(), eq(Number.class), eq(10), eq(20))).thenReturn(1);

        var result = service().inspect(website, legacy);

        assertEquals("REVIEW_REQUIRED", result.status());
        assertEquals("OTHER", result.domains().getFirst().domain());
        assertTrue(result.nextActions().contains("COMPLETE_REFERENCE_INVENTORY"));
    }

    @Test
    void emptyUnknownOwnershipColumnIsNotAnArtificialMergeConflict() {
        User website = user(10), legacy = user(20);
        when(jdbc.queryForList(anyString())).thenReturn(
            List.of(reference("unknown_future_feature", "owner_user_id", 1)));
        when(jdbc.queryForObject(anyString(), eq(Number.class), eq(10), eq(20))).thenReturn(0);

        var result = service().inspect(website, legacy);

        assertEquals("PRECHECK_REQUIRED", result.status());
        assertTrue(result.domains().isEmpty());
    }

    @Test
    void initiatorsOpenDiscordOAuthChallengeDoesNotMakeSparseAccountUnmergeable() {
        User website = user(10), legacy = user(20);
        when(jdbc.queryForList(anyString())).thenReturn(
            List.of(reference("user_discord_link_challenges", "requesting_user_id", 1)));
        when(jdbc.queryForObject(anyString(), eq(Number.class), eq(10), eq(20))).thenReturn(1);

        var result = service().inspect(website, legacy);

        assertEquals("PRECHECK_REQUIRED", result.status());
        assertEquals("PROVENANCE", result.domains().getFirst().domain());
    }

    @Test
    void cannotInspectSingleUserAsTwoSeparateAccountOwners() {
        User owner = user(10);
        assertThrows(IllegalArgumentException.class, () -> service().inspect(owner, owner));
        verifyNoInteractions(memberships, jdbc);
    }
}
