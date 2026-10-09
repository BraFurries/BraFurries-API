package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.Brafurries.API.admin.dto.UserIdentityDtos.DiscordAccount;
import com.Brafurries.API.admin.dto.UserIdentityDtos.IdentityLinkView;
import com.Brafurries.API.admin.dto.UserIdentityDtos.UserIdentityResponse;
import com.Brafurries.API.admin.dto.UserIdentityDtos.UserSummary;
import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserBan;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserIdentityLinkSource;
import com.Brafurries.API.entity.user.UserIdentityLinkStatus;
import com.Brafurries.API.entity.user.UserWarning;
import com.Brafurries.API.repository.user.UserBanRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserNoteRepository;
import com.Brafurries.API.repository.user.UserWarningRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserIdentityModerationServiceTest {

    @Mock ConfirmedIdentityClusterService clusters;
    @Mock UserIdentityLinkService identities;
    @Mock UserDiscordRepository discord;
    @Mock UserWarningRepository warnings;
    @Mock UserBanRepository bans;
    @Mock UserNoteRepository notes;

    @Test
    void aggregatesConfirmedClusterOnlyInsideRequestedCommunityAndPreservesSource() {
        User userA = user(10, "A");
        User userB = user(20, "B");
        User suspected = user(30, "Suspected");
        UserSummary summaryA = summary(userA, "111");
        UserSummary summaryB = summary(userB, "222");
        IdentityLinkView suspectedLink = link(userB, suspected, UserIdentityLinkStatus.SUSPECTED);
        when(clusters.resolveConfirmedUserIds(20)).thenReturn(Set.of(10, 20));
        when(identities.getIdentity(20, Set.of(10, 20))).thenReturn(new UserIdentityResponse(
            20, List.of(summaryA, summaryB), List.of(), List.of(suspectedLink)
        ));
        when(discord.findByUserIdIn(any())).thenReturn(List.of(discord(userA, 111L), discord(userB, 222L)));

        Community target = community(5, "Target");
        Community other = community(6, "Other");
        UserWarning targetWarning = warning(7, userB, target, "Target warning");
        UserWarning otherWarning = warning(8, userA, other, "Other warning");
        when(warnings.findByUserIdInAndCommunityId(any(), eq(5))).thenReturn(List.of(targetWarning));
        when(bans.findByUserIdInAndCommunityId(any(), eq(5))).thenReturn(List.of(ban(9, userA, target)));
        when(notes.findByUserIdIn(List.of(20))).thenReturn(List.of());

        var result = service().getHistory(20, 5);

        assertEquals(5, result.communityId());
        assertEquals(List.of(10, 20), result.confirmedUserIds());
        assertEquals(1, result.warnings().size());
        assertEquals("Target warning", result.warnings().getFirst().reason());
        assertFalse(result.warnings().stream().anyMatch(item -> item.id().equals(otherWarning.getId())));
        assertEquals(20, result.warnings().getFirst().sourceUserId());
        assertEquals(List.of("222"), result.warnings().getFirst().sourceDiscordIds());
        assertEquals(10, result.bans().getFirst().sourceUserId());
        assertEquals(30, result.suspectedLinks().getFirst().userB().userId());
        verify(warnings).findByUserIdInAndCommunityId(Set.of(10, 20), 5);
        verify(bans).findByUserIdInAndCommunityId(Set.of(10, 20), 5);
        verify(warnings, never()).findByUserIdIn(any());
        verify(bans, never()).findByUserIdIn(any());
    }

    @Test
    void suspectedEdgeNeverAddsUserToModerationCluster() {
        User userA = user(10, "A");
        User suspected = user(30, "Suspected");
        when(clusters.resolveConfirmedUserIds(10)).thenReturn(Set.of(10));
        when(identities.getIdentity(10, Set.of(10))).thenReturn(new UserIdentityResponse(
            10,
            List.of(summary(userA, "111")),
            List.of(),
            List.of(link(userA, suspected, UserIdentityLinkStatus.SUSPECTED))
        ));
        when(discord.findByUserIdIn(Set.of(10))).thenReturn(List.of(discord(userA, 111L)));
        when(warnings.findByUserIdInAndCommunityId(Set.of(10), 5)).thenReturn(List.of());
        when(bans.findByUserIdInAndCommunityId(Set.of(10), 5)).thenReturn(List.of());
        when(notes.findByUserIdIn(List.of(10))).thenReturn(List.of());

        var result = service().getHistory(10, 5);

        assertEquals(List.of(10), result.confirmedUserIds());
        assertEquals(30, result.suspectedLinks().getFirst().userB().userId());
        verify(warnings).findByUserIdInAndCommunityId(Set.of(10), 5);
    }

    private UserIdentityModerationService service() {
        return new UserIdentityModerationService(clusters, identities, discord, warnings, bans, notes);
    }

    private UserSummary summary(User user, String discordId) {
        return new UserSummary(user.getId(), "user" + user.getId(), user.getDisplayName(), null,
            List.of(new DiscordAccount(discordId, "discord", "Discord")));
    }

    private IdentityLinkView link(User a, User b, UserIdentityLinkStatus status) {
        return new IdentityLinkView(
            1L, summary(a, String.valueOf(a.getId())), summary(b, String.valueOf(b.getId())), status,
            UserIdentityLinkSource.ADMIN, "motivo", 99, LocalDateTime.now(), null, null, null
        );
    }

    private UserWarning warning(int id, User user, Community community, String reason) {
        UserWarning warning = new UserWarning();
        warning.setId(id);
        warning.setUser(user);
        warning.setCommunity(community);
        warning.setDate(LocalDate.of(2026, 9, 1));
        warning.setReason(reason);
        warning.setExpired(false);
        warning.setAppliedBy(user);
        return warning;
    }

    private UserBan ban(int id, User user, Community community) {
        UserBan ban = new UserBan();
        ban.setId(id);
        ban.setUser(user);
        ban.setCommunity(community);
        ban.setDate(LocalDate.of(2026, 8, 1));
        ban.setReason("Ban");
        ban.setCanAppeal(true);
        ban.setRegisteredAt(LocalDateTime.now());
        return ban;
    }

    private Community community(int id, String name) {
        Community community = new Community();
        community.setId(id);
        community.setName(name);
        return community;
    }

    private User user(int id, String name) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(name);
        return user;
    }

    private UserDiscord discord(User user, long id) {
        UserDiscord account = new UserDiscord();
        account.setUser(user);
        account.setDiscordUserId(id);
        return account;
    }
}
