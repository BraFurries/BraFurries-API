package com.Brafurries.API.user;

import com.Brafurries.API.entity.user.UserCommunityStatus;
import org.springframework.stereotype.Component;

@Component
public class CommunityMembershipStatusResolver {

    public CommunityMembershipStatus resolve(
        UserCommunityStatus membership,
        boolean portariaEnabled
    ) {
        if (membership == null) {
            return CommunityMembershipStatus.UNKNOWN;
        }
        if (Boolean.TRUE.equals(membership.getBanned())) {
            return CommunityMembershipStatus.BANNED;
        }
        if (Boolean.FALSE.equals(membership.getIsPresent())) {
            return CommunityMembershipStatus.LEFT;
        }
        if (membership.getIsPresent() == null || membership.getBanned() == null) {
            return CommunityMembershipStatus.UNKNOWN;
        }
        if (!portariaEnabled) {
            return CommunityMembershipStatus.ACTIVE;
        }
        if (Boolean.FALSE.equals(membership.getApprovalRequired())) {
            return CommunityMembershipStatus.ACTIVE;
        }
        if (!Boolean.TRUE.equals(membership.getApprovalRequired())) {
            return CommunityMembershipStatus.UNKNOWN;
        }
        if (Boolean.FALSE.equals(membership.getApproved())) {
            return CommunityMembershipStatus.PENDING;
        }
        if (Boolean.TRUE.equals(membership.getApproved())) {
            return CommunityMembershipStatus.ACTIVE;
        }
        return CommunityMembershipStatus.UNKNOWN;
    }
}
