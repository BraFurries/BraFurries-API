package com.Brafurries.API.user;

import java.util.Optional;

public enum CommunityCapability {
    COMMUNITY_ADMIN(false, false, true),

    TEAM_VIEW(false, false, false),
    TEAM_ASSIGN_MEMBERS(true, true, false),
    TEAM_MANAGE_STRUCTURE(true, true, false),
    TEAM_MANAGE_DEMANDS(true, true, false),

    MEMBERS_VIEW(false, true, true),
    MEMBER_DETAILS_VIEW(false, true, true),

    MEMBER_NOTES_VIEW(false, false, true),
    MEMBER_NOTES_MANAGE(false, false, true);

    private final boolean hierarchicalTeamScope;
    private final boolean roleGrantAllowed;
    private final boolean directGrantAllowed;

    CommunityCapability(
        boolean hierarchicalTeamScope,
        boolean roleGrantAllowed,
        boolean directGrantAllowed
    ) {
        this.hierarchicalTeamScope = hierarchicalTeamScope;
        this.roleGrantAllowed = roleGrantAllowed;
        this.directGrantAllowed = directGrantAllowed;
    }

    public boolean hierarchicalTeamScope() {
        return hierarchicalTeamScope;
    }

    public boolean roleGrantAllowed() {
        return roleGrantAllowed;
    }

    public boolean directGrantAllowed() {
        return directGrantAllowed;
    }

    public static Optional<CommunityCapability> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code.trim()));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
