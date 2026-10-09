package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.dto.CommunityCoreDtos.CommunityResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommunityCoreServiceTest {
    @Mock CommunityRepository communities;
    @Mock CommunityDiscordRepository discord;
    @Mock UserRepository users;
    @Mock UserCommunityStatusRepository memberships;
    @Mock CommunityNetworkAvailabilityService availability;
    @Mock Authentication auth;

    private CommunityCoreService service;
    private User user;
    private final CommunityMembershipStatusResolver statusResolver = new CommunityMembershipStatusResolver();

    @BeforeEach
    void setUp() {
        service = new CommunityCoreService(
            communities,
            discord,
            users,
            memberships,
            availability,
            statusResolver
        );
        user = user(1, "owner@example.com");
        lenient().when(auth.getName()).thenReturn("OWNER@EXAMPLE.COM");
        lenient().when(users.findByEmail("owner@example.com")).thenReturn(Optional.of(user));
        lenient().when(availability.isPortariaEnabled(any(Long.class))).thenReturn(true);
    }

    @Test
    void inactiveCommunityDoesNotAppearEvenWhenOwned() {
        Community inactive = community(10, "Inactive", user);
        when(communities.findOwnedByUserId(1)).thenReturn(List.of(inactive));
        when(memberships.findByUser(user)).thenReturn(List.of());
        when(discord.findByCommunityIds(any())).thenReturn(List.of(link(inactive, 100L, false)));

        assertTrue(service.list(auth).isEmpty());
    }

    @Test
    void directGetCannotBypassInactiveNetwork() {
        Community inactive = community(10, "Inactive", user);
        when(communities.findById(10)).thenReturn(Optional.of(inactive));
        when(availability.requireActiveDiscord(inactive)).thenThrow(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Community não encontrada")
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.get(auth, 10)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(memberships);
    }

    @Test
    void listsOwnedThenUnresolvedThenOtherAccessibleByNameAndId() {
        Community owned = community(30, "Zulu", user);
        Community unresolvedB = community(20, "Beta", null);
        Community unresolvedA = community(10, "Alpha", null);
        Community other = community(40, "Able", user(99, "other@example.com"));

        UserCommunityStatus memberA = activeMembership(unresolvedA);
        UserCommunityStatus memberB = activeMembership(unresolvedB);
        UserCommunityStatus memberOther = activeMembership(other);

        when(communities.findOwnedByUserId(1)).thenReturn(List.of(owned));
        when(memberships.findByUser(user)).thenReturn(List.of(memberB, memberA, memberOther));
        when(discord.findByCommunityIds(any())).thenReturn(List.of(
            link(owned, 300L, true),
            link(unresolvedB, 200L, true),
            link(unresolvedA, 100L, true),
            link(other, 400L, true)
        ));

        List<CommunityResponse> result = service.list(auth);

        assertEquals(List.of(30, 10, 20, 40), result.stream().map(CommunityResponse::communityId).toList());
        assertTrue(result.getFirst().ownedByMe());
        assertFalse(result.get(1).ownerAssigned());
    }

    @Test
    void legacyUnknownMembershipIsNotGuessedAsAccessible() {
        Community community = community(10, "Legacy", null);
        UserCommunityStatus legacy = new UserCommunityStatus();
        legacy.setUser(user);
        legacy.setCommunity(community);
        legacy.setBanned(false);
        legacy.setIsPresent(true);
        legacy.setApproved(true);
        legacy.setApprovalRequired(null);

        when(communities.findOwnedByUserId(1)).thenReturn(List.of());
        when(memberships.findByUser(user)).thenReturn(List.of(legacy));
        when(discord.findByCommunityIds(any())).thenReturn(List.of(link(community, 100L, true)));

        assertTrue(service.list(auth).isEmpty());
    }

    private UserCommunityStatus activeMembership(Community community) {
        UserCommunityStatus status = new UserCommunityStatus();
        status.setUser(user);
        status.setCommunity(community);
        status.setBanned(false);
        status.setIsPresent(true);
        status.setApproved(false);
        status.setApprovalRequired(false);
        return status;
    }

    private CommunityDiscord link(Community community, long guildId, boolean active) {
        CommunityDiscord link = new CommunityDiscord();
        link.setCommunity(community);
        link.setGuildId(guildId);
        link.setName(community.getName());
        link.setActive(active);
        link.setUsersQuantity(1);
        return link;
    }

    private Community community(int id, String name, User owner) {
        Community community = new Community();
        community.setId(id);
        community.setName(name);
        community.setOwnerUser(owner);
        return community;
    }

    private User user(int id, String email) {
        User value = new User();
        value.setId(id);
        value.setEmail(email);
        return value;
    }
}
