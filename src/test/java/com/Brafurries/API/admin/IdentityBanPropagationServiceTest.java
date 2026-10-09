package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.IdentityBanPropagationDtos.PropagationRequest;
import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserBan;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.repository.user.UserBanRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityBanPropagationServiceTest {

    @Mock ConfirmedIdentityClusterService clusters;
    @Mock UserBanRepository bans;
    @Mock UserDiscordRepository discordAccounts;
    @Mock IdentityBanPropagationClient client;

    private IdentityBanPropagationService service;

    @BeforeEach
    void setUp() {
        service = new IdentityBanPropagationService(
            clusters,
            bans,
            discordAccounts,
            client
        );
    }

    @Test
    void propagatesEveryActiveBanToAllDiscordAccountsInResultingCluster() {
        when(clusters.resolveConfirmedUserIds(10)).thenReturn(Set.of(10, 20, 30));
        UserBan ban = activeBan(77, 5, 555L, "ban ativo");
        when(bans.findActiveBansWithDiscordByUserIds(any(), eq(LocalDate.now())))
            .thenReturn(List.of(ban));
        when(discordAccounts.findByUserIdIn(any())).thenReturn(List.of(
            discord(10, 111L),
            discord(20, 222L),
            discord(30, 333L)
        ));

        service.propagateForConfirmedCluster(10);

        ArgumentCaptor<PropagationRequest> request = ArgumentCaptor.forClass(PropagationRequest.class);
        verify(client).propagate(eq(555L), request.capture());
        assertEquals(77, request.getValue().banId());
        assertEquals("ban ativo", request.getValue().reason());
        assertEquals(List.of("111", "222", "333"),
            request.getValue().identities().stream()
                .map(identity -> identity.discordUserId())
                .toList());
    }

    @Test
    void skipsCommunitiesWithoutActiveDiscordIntegration() {
        when(clusters.resolveConfirmedUserIds(10)).thenReturn(Set.of(10, 20));
        UserBan ban = activeBan(77, 5, 555L, "ban ativo");
        ban.getCommunity().getDiscord().setActive(false);
        when(bans.findActiveBansWithDiscordByUserIds(any(), eq(LocalDate.now())))
            .thenReturn(List.of(ban));
        when(discordAccounts.findByUserIdIn(any())).thenReturn(List.of(
            discord(10, 111L),
            discord(20, 222L)
        ));

        service.propagateForConfirmedCluster(10);

        verify(client, never()).propagate(any(), any());
    }

    @Test
    void noActiveBanMeansNoDiscordCall() {
        when(clusters.resolveConfirmedUserIds(10)).thenReturn(Set.of(10, 20));
        when(bans.findActiveBansWithDiscordByUserIds(any(), eq(LocalDate.now())))
            .thenReturn(List.of());

        service.propagateForConfirmedCluster(10);

        verify(discordAccounts, never()).findByUserIdIn(any());
        verify(client, never()).propagate(any(), any());
    }

    private UserBan activeBan(int banId, int communityId, long guildId, String reason) {
        Community community = new Community();
        community.setId(communityId);
        CommunityDiscord discord = new CommunityDiscord();
        discord.setCommunity(community);
        discord.setGuildId(guildId);
        discord.setActive(true);
        community.setDiscord(discord);

        User owner = new User();
        owner.setId(10);

        UserBan ban = new UserBan();
        ban.setId(banId);
        ban.setUser(owner);
        ban.setCommunity(community);
        ban.setReason(reason);
        return ban;
    }

    private UserDiscord discord(int userId, long discordUserId) {
        User user = new User();
        user.setId(userId);
        UserDiscord account = new UserDiscord();
        account.setUser(user);
        account.setDiscordUserId(discordUserId);
        return account;
    }
}
