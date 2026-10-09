package com.Brafurries.API.user;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserTelegram;
import com.Brafurries.API.repository.user.UserBirthdayRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTelegramRepository;
import com.Brafurries.API.user.dto.MemberDataState;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberProfileServiceTest {
    @Mock UserRepository users;
    @Mock UserBirthdayRepository birthdays;
    @Mock UserDiscordRepository discords;
    @Mock UserTelegramRepository telegrams;
    @Mock UserCommunityStatusRepository memberships;

    @Test
    void normalizesTheAuthenticatedEmailAndDoesNotExposeExternalIds() {
        User user = user(9);
        UserDiscord discord = new UserDiscord();
        discord.setUsername("fox");
        discord.setDisplayName("Fox");
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(birthdays.findAllByUserId(9)).thenReturn(List.of());
        when(discords.findAllByUserIdOrderByIdAsc(9)).thenReturn(List.of(discord));
        when(telegrams.findAllByUserIdOrderByIdAsc(9)).thenReturn(List.of());
        when(memberships.findByUser(user)).thenReturn(List.of());

        var response = service().getLoggedUserProfile(" Member@Example.Com ");

        assertEquals("fox", response.discord().username());
        assertEquals(MemberDataState.NOT_FOUND, response.telegram().state());
        assertEquals(0, response.communityCount());
    }

    @Test
    void marksDuplicateExternalAccountsAndMembershipsAsAmbiguous() {
        User user = user(9);
        Community community = community(5, "BraFurries");
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(birthdays.findAllByUserId(9)).thenReturn(List.of());
        when(discords.findAllByUserIdOrderByIdAsc(9)).thenReturn(List.of(new UserDiscord(), new UserDiscord()));
        when(telegrams.findAllByUserIdOrderByIdAsc(9)).thenReturn(List.of());
        when(memberships.findByUser(user)).thenReturn(List.of(membership(1, community), membership(2, community)));

        var response = service().getLoggedUserProfile("member@example.com");

        assertEquals(MemberDataState.AMBIGUOUS, response.discord().state());
        assertNull(response.discord().username());
        assertEquals(1, response.communityCount());
        assertEquals(MemberDataState.AMBIGUOUS, response.communities().getFirst().membershipState());
        assertEquals(MemberDataState.AMBIGUOUS, response.firstKnownCommunity().state());
    }

    @Test
    void identifiesFirstCommunityOnlyWhenTheDateIsUnique() {
        User user = user(9);
        Community first = community(1, "Primeira");
        Community later = community(2, "Posterior");
        UserCommunityStatus firstMembership = membership(1, first);
        firstMembership.setMemberSince(LocalDateTime.of(2025, 1, 1, 0, 0));
        UserCommunityStatus laterMembership = membership(2, later);
        laterMembership.setMemberSince(LocalDateTime.of(2026, 1, 1, 0, 0));
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(birthdays.findAllByUserId(9)).thenReturn(List.of());
        when(discords.findAllByUserIdOrderByIdAsc(9)).thenReturn(List.of());
        when(telegrams.findAllByUserIdOrderByIdAsc(9)).thenReturn(List.of());
        when(memberships.findByUser(user)).thenReturn(List.of(laterMembership, firstMembership));

        var response = service().getLoggedUserProfile("member@example.com");

        assertEquals(MemberDataState.AVAILABLE, response.firstKnownCommunity().state());
        assertEquals(1, response.firstKnownCommunity().communityId());
    }

    @Test
    void reportsMissingBirthdayAndAvailableTelegramWithoutInventingValues() {
        User user = user(9);
        UserTelegram telegram = new UserTelegram();
        telegram.setUsername("foxgram");
        telegram.setDisplayName("Fox Gram");
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(birthdays.findAllByUserId(9)).thenReturn(List.of());
        when(discords.findAllByUserIdOrderByIdAsc(9)).thenReturn(List.of());
        when(telegrams.findAllByUserIdOrderByIdAsc(9)).thenReturn(List.of(telegram));
        when(memberships.findByUser(user)).thenReturn(List.of());

        var response = service().getLoggedUserProfile("member@example.com");

        assertNull(response.birthday());
        assertEquals(MemberDataState.NOT_FOUND, response.firstKnownCommunity().state());
        assertEquals(MemberDataState.AVAILABLE, response.telegram().state());
        assertEquals("foxgram", response.telegram().username());
    }

    @Test
    void marksMultipleTelegramRowsAmbiguousAndKeepsDistinctCommunitiesOnceEach() {
        User user = user(9);
        Community first = community(1, "Primeira");
        Community second = community(2, "Segunda");
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(birthdays.findAllByUserId(9)).thenReturn(List.of());
        when(discords.findAllByUserIdOrderByIdAsc(9)).thenReturn(List.of());
        when(telegrams.findAllByUserIdOrderByIdAsc(9)).thenReturn(List.of(new UserTelegram(), new UserTelegram()));
        when(memberships.findByUser(user)).thenReturn(List.of(membership(1, first), membership(2, second)));

        var response = service().getLoggedUserProfile("member@example.com");

        assertEquals(MemberDataState.AMBIGUOUS, response.telegram().state());
        assertNull(response.telegram().username());
        assertEquals(2, response.communityCount());
        assertEquals(List.of(1, 2), response.communities().stream().map(community -> community.communityId()).toList());
    }

    @Test
    void omitsInactiveCommunitiesFromProfile() {
        User user = user(9);
        Community active = community(1, "Ativa");
        Community inactive = community(2, "Inativa");
        inactive.getDiscord().setActive(false);
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(birthdays.findAllByUserId(9)).thenReturn(List.of());
        when(discords.findAllByUserIdOrderByIdAsc(9)).thenReturn(List.of());
        when(telegrams.findAllByUserIdOrderByIdAsc(9)).thenReturn(List.of());
        when(memberships.findByUser(user)).thenReturn(List.of(membership(1, active), membership(2, inactive)));

        var response = service().getLoggedUserProfile("member@example.com");

        assertEquals(1, response.communityCount());
        assertEquals(1, response.communities().getFirst().communityId());
    }

    @Test
    void doesNotChooseFirstCommunityWhenTwoCommunitiesTieForTheEarliestDate() {
        User user = user(9);
        Community first = community(1, "Primeira");
        Community second = community(2, "Segunda");
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(birthdays.findAllByUserId(9)).thenReturn(List.of());
        when(discords.findAllByUserIdOrderByIdAsc(9)).thenReturn(List.of());
        when(telegrams.findAllByUserIdOrderByIdAsc(9)).thenReturn(List.of());
        when(memberships.findByUser(user)).thenReturn(List.of(membership(1, first), membership(2, second)));

        var response = service().getLoggedUserProfile("member@example.com");

        assertEquals(MemberDataState.AMBIGUOUS, response.firstKnownCommunity().state());
        assertNull(response.firstKnownCommunity().communityId());
    }

    private MemberProfileService service() {
        return new MemberProfileService(users, birthdays, discords, telegrams, memberships);
    }

    private User user(int id) { User user = new User(); user.setId(id); return user; }
    private Community community(int id, String name) {
        Community community = new Community();
        community.setId(id);
        community.setName(name);
        CommunityDiscord discord = new CommunityDiscord();
        discord.setCommunity(community);
        discord.setGuildId(1000L + id);
        discord.setActive(true);
        community.setDiscord(discord);
        return community;
    }
    private UserCommunityStatus membership(int id, Community community) {
        UserCommunityStatus membership = new UserCommunityStatus();
        membership.setId(id); membership.setCommunity(community); membership.setMemberSince(LocalDateTime.of(2026, 1, 1, 0, 0)); return membership;
    }
}
