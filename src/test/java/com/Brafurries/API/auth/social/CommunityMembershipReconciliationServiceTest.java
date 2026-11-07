package com.Brafurries.API.auth.social;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommunityMembershipReconciliationServiceTest {
    @Mock UserCommunityStatusRepository memberships;

    private CommunityMembershipReconciliationService service() {
        return new CommunityMembershipReconciliationService(memberships);
    }

    private User user(int id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    private UserCommunityStatus status(User user, int communityId, int recordId) {
        Community community = new Community();
        community.setId(communityId);
        UserCommunityStatus status = new UserCommunityStatus();
        status.setId(recordId);
        status.setUser(user);
        status.setCommunity(community);
        status.setMemberSince(LocalDateTime.of(2024, 5, 1, 12, 0));
        status.setLastJoinDate(LocalDateTime.of(2024, 6, 1, 12, 0));
        status.setApproved(true);
        status.setApprovedAt(LocalDateTime.of(2024, 5, 2, 12, 0));
        status.setBanned(false);
        status.setIsVip(false);
        status.setIsPartner(false);
        status.setBirthdayMentionable(false);
        status.setInviteLinkUsed("original-invite");
        return status;
    }

    @Test
    void websiteOnlyNeutralMembershipCanTransferPreservingPrimaryKeyAndPortariaState() {
        User website = user(10), discord = user(20);
        UserCommunityStatus original = status(website, 1, 305);
        when(memberships.findAllForAccountReconciliation(10)).thenReturn(List.of(original));
        when(memberships.findAllForAccountReconciliation(20)).thenReturn(List.of());

        List<UserCommunityStatus> prepared = service().prepareLockedTransfer(website, discord);

        assertEquals(1, prepared.size());
        service().transferPrepared(prepared, website, discord);
        assertEquals(305, original.getId());
        assertEquals(20, original.getUser().getId());
        assertEquals(1, original.getCommunity().getId());
        assertEquals(LocalDateTime.of(2024, 5, 1, 12, 0), original.getMemberSince());
        assertEquals(LocalDateTime.of(2024, 6, 1, 12, 0), original.getLastJoinDate());
        assertTrue(original.getApproved());
        assertEquals(LocalDateTime.of(2024, 5, 2, 12, 0), original.getApprovedAt());
        assertEquals("original-invite", original.getInviteLinkUsed());
        verify(memberships).save(original);
        verify(memberships).flush();
    }

    @Test
    void duplicateInSameCommunityIsNotAutoMergedOrDeleted() {
        User website = user(10), discord = user(20);
        when(memberships.findAllForAccountReconciliation(10))
            .thenReturn(List.of(status(website, 1, 1)));
        when(memberships.findAllForAccountReconciliation(20))
            .thenReturn(List.of(status(discord, 1, 2)));

        ResponseStatusException conflict = assertThrows(ResponseStatusException.class,
            () -> service().prepareLockedTransfer(website, discord));

        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertEquals("COMMUNITY_MEMBERSHIP_RECONCILIATION_REQUIRED", conflict.getReason());
        verify(memberships, never()).save(any());
    }

    @Test
    void inheritedDiscordRestrictionsAndVipStayOnCanonicalButSiteRoleRequiresReview() {
        User website = user(10), discord = user(20);
        UserCommunityStatus existing = status(discord, 42, 2);
        existing.setIsVip(true);
        existing.setBanned(true);
        UserCommunityStatus websiteOnly = status(website, 1, 1);
        when(memberships.findByUser(website)).thenReturn(List.of(websiteOnly));
        when(memberships.findByUser(discord)).thenReturn(List.of(existing));

        var preview = service().preview(website, discord);
        assertTrue(preview.transferReady());
        assertEquals("TRANSFER_CANDIDATE", preview.communities().getFirst().action());
        assertEquals("PRESERVE_CANONICAL", preview.communities().getLast().action());

        websiteOnly.setIsVip(true);
        var blocked = service().preview(website, discord);
        assertFalse(blocked.transferReady());
        assertTrue(blocked.nextActions().contains("REVIEW_MEMBERSHIP_GRANTS"));
        verify(memberships, never()).save(any());
    }

    @Test
    void siteBanCannotDisappearDuringReconciliation() {
        User website = user(10), discord = user(20);
        UserCommunityStatus banned = status(website, 7, 3);
        banned.setBanned(true);
        when(memberships.findAllForAccountReconciliation(10)).thenReturn(List.of(banned));
        when(memberships.findAllForAccountReconciliation(20)).thenReturn(List.of());
        var conflict = assertThrows(ResponseStatusException.class,
            () -> service().prepareLockedTransfer(website, discord));
        assertEquals("COMMUNITY_MEMBERSHIP_RECONCILIATION_REQUIRED", conflict.getReason());
        assertEquals(10, banned.getUser().getId());
    }

    @Test
    void incompleteLegacyRowsMustBeReviewedBeforeAnyOwnershipChange() {
        User website = user(10), discord = user(20);
        UserCommunityStatus incomplete = status(website, 5, 33);
        incomplete.setApproved(null);
        when(memberships.findAllForAccountReconciliation(10)).thenReturn(List.of(incomplete));
        when(memberships.findAllForAccountReconciliation(20)).thenReturn(List.of());
        var conflict = assertThrows(ResponseStatusException.class,
            () -> service().prepareLockedTransfer(website, discord));
        assertEquals("COMMUNITY_MEMBERSHIP_RECONCILIATION_REQUIRED", conflict.getReason());
        verify(memberships, never()).flush();
    }

    @Test
    void rejectsNullOrSameAccountWithoutScanningTenants() {
        User same = user(10);
        assertThrows(IllegalArgumentException.class, () -> service().preview(same, same));
        assertThrows(IllegalArgumentException.class, () ->
            service().prepareLockedTransfer(null, same));
        verifyNoInteractions(memberships);
    }
}
