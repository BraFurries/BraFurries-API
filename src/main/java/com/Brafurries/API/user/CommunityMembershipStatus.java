package com.Brafurries.API.user;

public enum CommunityMembershipStatus {
    ACTIVE,
    PENDING,
    BANNED,
    LEFT,
    UNKNOWN;

    public String apiValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
