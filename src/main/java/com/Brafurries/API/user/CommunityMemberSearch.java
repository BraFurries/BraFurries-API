package com.Brafurries.API.user;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.user.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Both Community list contracts must route text and Discord snowflakes using the
 * same rules. Only the authorized Community's membership may make a User visible.
 */
final class CommunityMemberSearch {
    private CommunityMemberSearch() {}

    static Page<User> find(
        UserRepository users,
        Integer communityId,
        String rawSearch,
        Pageable pageable
    ) {
        String search = rawSearch == null || rawSearch.isBlank() ? null : rawSearch.trim();
        if (search != null && search.startsWith("@") && search.length() > 1) {
            search = search.substring(1);
        }
        if (search != null && search.matches("[0-9]{15,20}")) {
            long discordUserId;
            try {
                discordUserId = Long.parseLong(search);
                if (discordUserId <= 0) discordUserId = Long.MIN_VALUE;
            } catch (NumberFormatException ex) {
                // An overflowing snowflake-shaped input must not become a broad text match.
                discordUserId = Long.MIN_VALUE;
            }
            return users.searchCommunityMembersByDiscordId(communityId, discordUserId, pageable);
        }
        return users.searchCommunityMembers(communityId, search, pageable);
    }
}
