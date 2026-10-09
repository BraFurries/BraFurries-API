package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.Brafurries.API.entity.user.UserCommunityStatus;
import org.junit.jupiter.api.Test;

class CommunityMembershipStatusResolverTest {
    private final CommunityMembershipStatusResolver resolver = new CommunityMembershipStatusResolver();

    @Test
    void resolvesPrecedenceAndPortariaRulesWithoutGuessingLegacyNulls() {
        assertEquals(CommunityMembershipStatus.BANNED, resolver.resolve(membership(true, true, true, true), false));
        assertEquals(CommunityMembershipStatus.LEFT, resolver.resolve(membership(true, false, false, true), true));
        assertEquals(CommunityMembershipStatus.UNKNOWN, resolver.resolve(membership(true, false, null, true), true));
        assertEquals(CommunityMembershipStatus.ACTIVE, resolver.resolve(membership(false, false, true, null), false));
        assertEquals(CommunityMembershipStatus.ACTIVE, resolver.resolve(membership(false, false, true, false), true));
        assertEquals(CommunityMembershipStatus.PENDING, resolver.resolve(membership(false, false, true, true), true));
        assertEquals(CommunityMembershipStatus.ACTIVE, resolver.resolve(membership(true, false, true, true), true));
        assertEquals(CommunityMembershipStatus.UNKNOWN, resolver.resolve(membership(true, false, true, null), true));
    }

    @Test
    void memberWhoJoinedWithoutPortariaRemainsActiveWhenPortariaLaterTurnsOn() {
        UserCommunityStatus membership = membership(false, false, true, false);

        assertEquals(CommunityMembershipStatus.ACTIVE, resolver.resolve(membership, false));
        assertEquals(CommunityMembershipStatus.ACTIVE, resolver.resolve(membership, true));
    }

    private UserCommunityStatus membership(
        Boolean approved,
        Boolean banned,
        Boolean present,
        Boolean approvalRequired
    ) {
        UserCommunityStatus value = new UserCommunityStatus();
        value.setApproved(approved);
        value.setBanned(banned);
        value.setIsPresent(present);
        value.setApprovalRequired(approvalRequired);
        return value;
    }
}
