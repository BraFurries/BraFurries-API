package com.Brafurries.API.admin;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.repository.community.CommunityRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminDiscordMemberServiceTest {

    @Mock UserRepository users;
    @Mock CommunityRepository communities;
    @Mock UserCommunityStatusRepository memberships;
    @Mock UserDiscordRepository discordAccounts;
    @Mock AdminDiscordMemberClient client;

    @Test
    void failsClosedWhenLegacyUserHasMultipleDiscordLinks() {
        Community community = new Community();
        community.setId(5);
        CommunityDiscord discordCommunity = new CommunityDiscord();
        discordCommunity.setGuildId(99L);
        discordCommunity.setActive(true);
        community.setDiscord(discordCommunity);
        UserDiscord first = new UserDiscord();
        UserDiscord second = new UserDiscord();

        when(users.existsById(1)).thenReturn(true);
        when(communities.findById(5)).thenReturn(Optional.of(community));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(1, 5))
            .thenReturn(List.of(new UserCommunityStatus()));
        when(discordAccounts.findByUserIdIn(List.of(1))).thenReturn(List.of(first, second));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service().getMember(1, 5));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
    }

    private AdminDiscordMemberService service() {
        return new AdminDiscordMemberService(users, communities, memberships, discordAccounts, client);
    }
}
