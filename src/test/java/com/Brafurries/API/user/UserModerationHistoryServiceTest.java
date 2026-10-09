package com.Brafurries.API.user;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserWarningRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserModerationHistoryServiceTest {
    @Mock UserRepository users;
    @Mock UserCommunityStatusRepository memberships;
    @Mock UserWarningRepository warnings;

    @Test
    void scopesRecordsToTheAuthenticatedUserAndCommunityAndNormalizesPagination() {
        User user = new User(); user.setId(7);
        UserWarningRepository.MemberModerationProjection projection = mock(UserWarningRepository.MemberModerationProjection.class);
        when(projection.getId()).thenReturn(4);
        when(projection.getRecordType()).thenReturn("ban");
        when(projection.getReason()).thenReturn("reason");
        when(projection.getOccurredAt()).thenReturn(LocalDate.of(2026, 1, 3));
        when(projection.getStatus()).thenReturn("revoked");
        when(projection.getCanAppeal()).thenReturn(1);
        when(projection.getValidUntil()).thenReturn(LocalDate.of(2026, 2, 1));
        when(projection.getRevokedAt()).thenReturn(LocalDateTime.of(2026, 1, 5, 0, 0));
        when(projection.getRevocationReason()).thenReturn("reviewed");
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 5)).thenReturn(List.of(activeMembership(5)));
        when(warnings.findMemberCommunityModerationRecords(7, 5, LocalDate.now(), PageRequest.of(0, 100)))
            .thenReturn(new PageImpl<>(List.of(projection), PageRequest.of(0, 100), 1));

        var response = service().getLoggedUserModeration(" Member@Example.Com ", 5, 0, 1000);

        assertEquals(1, response.page());
        assertEquals(100, response.pageSize());
        assertEquals("ban", response.items().getFirst().type());
        assertEquals(Boolean.TRUE, response.items().getFirst().canAppeal());
        assertEquals("reviewed", response.items().getFirst().revocationReason());
    }

    @Test
    void preservesNullAppealFlags() {
        User user = new User(); user.setId(7);
        UserWarningRepository.MemberModerationProjection projection = mock(UserWarningRepository.MemberModerationProjection.class);
        when(projection.getId()).thenReturn(1);
        when(projection.getRecordType()).thenReturn("warn");
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 5)).thenReturn(List.of(activeMembership(5)));
        when(warnings.findMemberCommunityModerationRecords(7, 5, LocalDate.now(), PageRequest.of(0, 10)))
            .thenReturn(new PageImpl<>(List.of(projection), PageRequest.of(0, 10), 1));

        var response = service().getLoggedUserModeration("member@example.com", 5, 1, 10);

        assertNull(response.items().getFirst().canAppeal());
    }

    @Test
    void returnsTheGenericNotFoundWhenTheMemberDoesNotBelongToTheCommunity() {
        User user = new User(); user.setId(7);
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 5)).thenReturn(List.of());

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service().getLoggedUserModeration("member@example.com", 5, 1, 10)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        assertEquals("Contexto comunitário não encontrado", error.getReason());
    }

    @Test
    void inactiveCommunityModerationFailsClosed() {
        User user = new User();
        user.setId(7);
        UserCommunityStatus membership = activeMembership(5);
        membership.getCommunity().getDiscord().setActive(false);
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 5))
            .thenReturn(List.of(membership));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service().getLoggedUserModeration("member@example.com", 5, 1, 10)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
    }

    private UserCommunityStatus activeMembership(int communityId) {
        Community community = new Community();
        community.setId(communityId);
        CommunityDiscord discord = new CommunityDiscord();
        discord.setGuildId(1000L + communityId);
        discord.setActive(true);
        discord.setCommunity(community);
        community.setDiscord(discord);
        UserCommunityStatus membership = new UserCommunityStatus();
        membership.setCommunity(community);
        return membership;
    }

    private UserModerationHistoryService service() {
        return new UserModerationHistoryService(users, memberships, warnings);
    }
}
