package com.Brafurries.API.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.Brafurries.API.admin.ConfirmedIdentityClusterService;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.repository.user.UserBanRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserWarningRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InternalIdentityServiceTest {

    @Mock ConfirmedIdentityClusterService clusters;
    @Mock UserRepository users;
    @Mock UserDiscordRepository discord;
    @Mock UserWarningRepository warnings;
    @Mock UserBanRepository bans;

    @Test
    void exposesConfirmedIdentitiesAndCommunityScopedModerationForCoddy() {
        User a = user(1);
        User b = user(2);
        when(users.existsById(1)).thenReturn(true);
        when(clusters.resolveConfirmedUserIds(1)).thenReturn(Set.of(1, 2));
        when(discord.findByUserIdIn(Set.of(1, 2))).thenReturn(List.of(discord(a, 111L), discord(b, 222L)));
        when(warnings.countByUserIdInAndCommunityId(Set.of(1, 2), 50)).thenReturn(3L);
        when(warnings.countByUserIdInAndCommunityIdAndExpiredFalse(Set.of(1, 2), 50)).thenReturn(2L);
        when(bans.countActiveBansByUserIdsAndCommunityId(any(), org.mockito.ArgumentMatchers.eq(50), any(LocalDate.class)))
            .thenReturn(1L);

        var result = service().getByUserId(1, 50);

        assertEquals(List.of(1, 2), result.confirmedIdentities().stream().map(identity -> identity.userId()).toList());
        assertEquals(List.of("111"), result.confirmedIdentities().getFirst().discordUserIds());
        assertEquals(1, result.otherAccountCount());
        assertEquals(50, result.moderation().communityId());
        assertEquals(3, result.moderation().warningCount());
        assertEquals(2, result.moderation().activeWarningCount());
        assertEquals(1, result.moderation().activeBanCount());
    }

    @Test
    void resolvesByDiscordIdAndDoesNotAggregateModerationWithoutCommunity() {
        User user = user(7);
        when(discord.findByDiscordUserId(777L)).thenReturn(Optional.of(discord(user, 777L)));
        when(clusters.resolveConfirmedUserIds(7)).thenReturn(Set.of(7));
        when(discord.findByUserIdIn(Set.of(7))).thenReturn(List.of(discord(user, 777L)));

        var result = service().getByDiscordUserId(777L, null);

        assertEquals(7, result.requestedUserId());
        assertNull(result.moderation());
        verify(warnings, never()).countByUserIdInAndCommunityId(any(), any());
        verify(bans, never()).countActiveBansByUserIdsAndCommunityId(any(), any(), any());
    }

    private InternalIdentityService service() {
        return new InternalIdentityService(clusters, users, discord, warnings, bans);
    }

    private User user(int id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private UserDiscord discord(User user, long discordId) {
        UserDiscord link = new UserDiscord();
        link.setUser(user);
        link.setDiscordUserId(discordId);
        return link;
    }
}
