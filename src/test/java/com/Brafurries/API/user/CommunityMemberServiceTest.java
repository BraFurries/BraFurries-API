package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleAssignmentRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.user.dto.CommunityMemberDtos.CommunityMember;
import com.Brafurries.API.user.dto.CommunityMemberDtos.CommunityMemberDetail;
import com.Brafurries.API.user.dto.CommunityMemberDtos.CommunityMembersResponse;
import com.Brafurries.API.user.dto.MemberDataState;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommunityMemberServiceTest {
    @Mock CommunityDiscordRepository communities;
    @Mock UserRepository users;
    @Mock UserCommunityStatusRepository memberships;
    @Mock CommunityTeamRoleAssignmentRepository assignments;
    @Mock CommunityAuthorizationService communityAuthorization;
    @Mock UserDiscordRepository userDiscord;
    @Mock CommunityNetworkAvailabilityService networkAvailability;
    @Mock Authentication auth;

    private CommunityMemberService service;
    private Community community;

    @BeforeEach
    void setUp() {
        service = new CommunityMemberService(
            communities,
            users,
            memberships,
            assignments,
            communityAuthorization,
            userDiscord,
            networkAvailability,
            new CommunityMembershipStatusResolver()
        );
        community = new Community();
        community.setId(1);
        community.setName("BraFurries");

        CommunityDiscord discord = new CommunityDiscord();
        discord.setGuildId(10L);
        discord.setCommunity(community);
        discord.setActive(true);
        lenient().when(communities.findByGuildId(10L)).thenReturn(Optional.of(discord));
        lenient().when(networkAvailability.isPortariaEnabled(any(Community.class))).thenReturn(true);
        User actor = user(1, "Admin", "admin");
        CommunityAuthorizationService.CommunityAccessContext context =
            new CommunityAuthorizationService.CommunityAccessContext(
                community,
                actor,
                true,
                true,
                true,
                Set.of(CommunityCapability.values()),
                List.of(),
                Map.of(),
                Set.of()
            );
        lenient().when(communityAuthorization.requireCapability(any(), anyInt(), any()))
            .thenReturn(context);
    }

    @Test
    void listsOnlyMembersFromResolvedCommunityAndKeepsInternalUserId() {
        User user = user(7, "Fox", "fox");
        UserCommunityStatus membership = membership(1, user, true, false, true);
        when(users.searchCommunityMembers(eq(1), eq("fox"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));
        when(memberships.findAllByCommunityIdAndUserIdIn(eq(1), anyCollection()))
            .thenReturn(List.of(membership));

        CommunityMembersResponse response = service.list(auth, "10", 1, 20, " fox ");

        assertEquals(1, response.items().size());
        CommunityMember item = response.items().getFirst();
        assertEquals(7, item.userId());
        assertEquals("Fox", item.displayName());
        assertEquals("active", item.status());
        assertEquals(MemberDataState.AVAILABLE, item.membershipDataState());
        verify(users).searchCommunityMembers(eq(1), eq("fox"), any(Pageable.class));
        verify(communityAuthorization).requireCapability(
            auth, 1, CommunityCapability.MEMBERS_VIEW
        );
    }

    @Test
    void legacyGuildMemberListUsesExactDiscordIdScopedToItsCommunity() {
        when(users.searchCommunityMembersByDiscordId(eq(1), eq(223456789012345678L), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        var result = service.list(auth, "10", 1, 20, "223456789012345678");

        assertTrue(result.items().isEmpty());
        verify(users).searchCommunityMembersByDiscordId(eq(1), eq(223456789012345678L), any(Pageable.class));
        verify(communityAuthorization).requireCapability(
            auth, 1, CommunityCapability.MEMBERS_VIEW
        );
    }

    @Test
    void duplicateHistoricalMembershipRowsDoNotDuplicatePersonOrGuessScalarState() {
        User user = user(7, "Fox", "fox");
        UserCommunityStatus older = membership(1, user, true, false, false);
        older.setMemberSince(LocalDateTime.of(2025, 1, 1, 0, 0));
        UserCommunityStatus newer = membership(2, user, true, false, true);
        newer.setMemberSince(LocalDateTime.of(2026, 1, 1, 0, 0));

        when(users.searchCommunityMembers(eq(1), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));
        when(memberships.findAllByCommunityIdAndUserIdIn(eq(1), anyCollection()))
            .thenReturn(List.of(older, newer));

        CommunityMember item = service.list(auth, "10", 1, 20, null).items().getFirst();

        assertEquals(7, item.userId());
        assertEquals(MemberDataState.AMBIGUOUS, item.membershipDataState());
        assertEquals(2, item.membershipRecordCount());
        assertEquals("ambiguous", item.status());
        assertNull(item.present());
        assertNull(item.banned());
        assertEquals(LocalDateTime.of(2025, 1, 1, 0, 0), item.memberSince());
    }

    @Test
    void preservesBannedAndLeftMembershipStates() {
        User bannedUser = user(7, "Banido", "banido");
        User leftUser = user(8, "Ausente", "ausente");
        UserCommunityStatus banned = membership(1, bannedUser, true, true, true);
        UserCommunityStatus left = membership(2, leftUser, true, false, false);

        when(users.searchCommunityMembers(eq(1), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(bannedUser, leftUser), PageRequest.of(0, 20), 2));
        when(memberships.findAllByCommunityIdAndUserIdIn(eq(1), anyCollection()))
            .thenReturn(List.of(banned, left));

        List<CommunityMember> items = service.list(auth, "10", 1, 20, null).items();

        assertEquals("banned", items.get(0).status());
        assertEquals("left", items.get(1).status());
    }

    @Test
    void communityDisplayNamePrecedesPlatformNameWithoutOverwritingIt() {
        User user = user(7, "Platform Fox", "fox");
        UserCommunityStatus membership = membership(1, user, true, false, true);
        membership.setDisplayName("Guild Fox");
        when(users.searchCommunityMembers(eq(1), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(user), PageRequest.of(0, 20), 1));
        when(memberships.findAllByCommunityIdAndUserIdIn(eq(1), anyCollection()))
            .thenReturn(List.of(membership));

        CommunityMember item = service.list(auth, "10", 1, 20, null).items().getFirst();

        assertEquals("Guild Fox", item.displayName());
        assertEquals("Platform Fox", user.getDisplayName());
    }

    @Test
    void detailReturnsNotFoundWithoutLeakingGlobalUserFromAnotherCommunity() {
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(99, 1))
            .thenReturn(List.of());

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.get(auth, "10", 99)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(users);
    }

    @Test
    void canonicalDetailRequiresMemberDetailsCapabilityAndStaysInsideCommunity() {
        User actor = user(1, "Admin", "admin");
        User target = user(7, "Fox", "fox");
        UserCommunityStatus membership = membership(1, target, true, false, true);
        membership.setLastJoinDate(LocalDateTime.of(2026, 9, 30, 20, 0));
        membership.setApprovedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
        membership.setLeftAt(null);
        membership.setIsVip(true);
        membership.setIsPartner(false);

        CommunityAuthorizationService.CommunityAccessContext context =
            new CommunityAuthorizationService.CommunityAccessContext(
                community,
                actor,
                false,
                false,
                true,
                java.util.Set.of(CommunityCapability.MEMBER_DETAILS_VIEW),
                List.of(),
                java.util.Map.of(),
                java.util.Set.of()
            );

        when(communityAuthorization.requireCapability(
            auth,
            1,
            CommunityCapability.MEMBER_DETAILS_VIEW
        )).thenReturn(context);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 1))
            .thenReturn(List.of(membership));
        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(1, 7))
            .thenReturn(List.of(
                new com.Brafurries.API.entity.community.CommunityTeamRoleAssignment(1, 2, 7),
                new com.Brafurries.API.entity.community.CommunityTeamRoleAssignment(1, 3, 7)
            ));

        CommunityMemberDetail detail = service.getCommunityDetail(auth, 1, 7);

        assertEquals(7, detail.userId());
        assertEquals("Fox", detail.displayName());
        assertEquals("active", detail.status());
        assertEquals(LocalDateTime.of(2026, 9, 30, 20, 0), detail.lastJoinDate());
        assertEquals(LocalDateTime.of(2026, 1, 2, 0, 0), detail.approvedAt());
        assertTrue(detail.vip());
        assertEquals(List.of(2, 3), detail.roleIds());
        verify(communityAuthorization).requireCapability(
            auth,
            1,
            CommunityCapability.MEMBER_DETAILS_VIEW
        );
        verifyNoInteractions(communities);
    }

    @Test
    void canonicalDetailDoesNotLeakAUserOutsideTheAuthorizedCommunity() {
        User actor = user(1, "Admin", "admin");
        CommunityAuthorizationService.CommunityAccessContext context =
            new CommunityAuthorizationService.CommunityAccessContext(
                community,
                actor,
                false,
                false,
                true,
                java.util.Set.of(CommunityCapability.MEMBER_DETAILS_VIEW),
                List.of(),
                java.util.Map.of(),
                java.util.Set.of()
            );
        when(communityAuthorization.requireCapability(
            auth,
            1,
            CommunityCapability.MEMBER_DETAILS_VIEW
        )).thenReturn(context);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(99, 1))
            .thenReturn(List.of());

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.getCommunityDetail(auth, 1, 99)
        );

        assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
        verifyNoInteractions(assignments);
    }

    @Test
    void canonicalDetailKeepsLegacyAmbiguityExplicitInsteadOfGuessingState() {
        User actor = user(1, "Admin", "admin");
        User target = user(7, "Fox", "fox");
        UserCommunityStatus first = membership(1, target, true, false, true);
        first.setMemberSince(LocalDateTime.of(2025, 1, 1, 0, 0));
        UserCommunityStatus second = membership(2, target, true, false, false);
        second.setMemberSince(LocalDateTime.of(2026, 1, 1, 0, 0));

        CommunityAuthorizationService.CommunityAccessContext context =
            new CommunityAuthorizationService.CommunityAccessContext(
                community,
                actor,
                false,
                false,
                true,
                java.util.Set.of(CommunityCapability.MEMBER_DETAILS_VIEW),
                List.of(),
                java.util.Map.of(),
                java.util.Set.of()
            );
        when(communityAuthorization.requireCapability(
            auth,
            1,
            CommunityCapability.MEMBER_DETAILS_VIEW
        )).thenReturn(context);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 1))
            .thenReturn(List.of(first, second));
        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(1, 7))
            .thenReturn(List.of());

        CommunityMemberDetail detail = service.getCommunityDetail(auth, 1, 7);

        assertEquals(MemberDataState.AMBIGUOUS, detail.membershipDataState());
        assertEquals(2, detail.membershipRecordCount());
        assertEquals("ambiguous", detail.status());
        assertEquals(LocalDateTime.of(2025, 1, 1, 0, 0), detail.memberSince());
        assertNull(detail.lastJoinDate());
        assertNull(detail.approved());
        assertNull(detail.banned());
        assertNull(detail.present());
    }

    @Test
    void stopsBeforeMemberQueriesWhenGuildAuthorizationFails() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem acesso"))
            .when(communityAuthorization).requireCapability(
                auth, 1, CommunityCapability.MEMBERS_VIEW
            );

        assertThrows(ResponseStatusException.class, () -> service.list(auth, "10", 1, 20, null));

        verifyNoInteractions(users, memberships);
    }

    @Test
    void normalizesPaginationWithoutChangingTenantScope() {
        when(users.searchCommunityMembers(eq(1), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        CommunityMembersResponse response = service.list(auth, "10", 0, 500, " ");

        assertEquals(1, response.pagination().page());
        assertEquals(100, response.pagination().pageSize());
        assertEquals(0, response.pagination().totalElements());
    }

    private User user(int id, String displayName, String username) {
        User user = new User();
        user.setId(id);
        user.setDisplayName(displayName);
        user.setUsername(username);
        user.setEmail("secret-" + id + "@example.com");
        return user;
    }

    private UserCommunityStatus membership(
        int id,
        User user,
        boolean approved,
        boolean banned,
        boolean present
    ) {
        UserCommunityStatus membership = new UserCommunityStatus();
        membership.setId(id);
        membership.setUser(user);
        membership.setCommunity(community);
        membership.setMemberSince(LocalDateTime.of(2026, 1, 1, 0, 0));
        membership.setApproved(approved);
        membership.setBanned(banned);
        membership.setIsPresent(present);
        membership.setIsVip(false);
        membership.setIsPartner(false);
        membership.setBirthdayMentionable(false);
        membership.setApprovalRequired(true);
        return membership;
    }
}
