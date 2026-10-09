package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.CommunityProvisioningDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityTeamRole;
import com.Brafurries.API.entity.community.CommunityTeamRoleAssignment;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityTeamRoleAssignmentRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;

@ExtendWith(MockitoExtension.class)
class CommunityProvisioningReadServiceTest {
    @Mock CommunityAuthorizationService authorization;
    @Mock CommunityTeamRoleRepository roles;
    @Mock CommunityTeamRoleAssignmentRepository assignments;
    @Mock UserRepository users;
    @Mock UserCommunityStatusRepository memberships;
    @Mock UserDiscordRepository userDiscord;
    @Mock CommunityNetworkAvailabilityService networkAvailability;
    @Mock Authentication auth;

    private CommunityProvisioningReadService service;
    private Community community;
    private User actor;

    @BeforeEach
    void setUp() {
        service = new CommunityProvisioningReadService(
            authorization,
            roles,
            assignments,
            users,
            memberships,
            userDiscord,
            networkAvailability,
            new CommunityMembershipStatusResolver()
        );
        community = new Community();
        community.setId(10);
        community.setName("BraFurries");

        actor = new User();
        actor.setId(1);
        actor.setEmail("admin@example.com");
        lenient().when(networkAvailability.isPortariaEnabled(any(Community.class))).thenReturn(true);
    }

    @Test
    void listRolesUsesTeamViewAndTheAuthorizedCommunityOnly() {
        CommunityTeamRole root = role(100, null, true);
        CommunityTeamRole child = role(101, root, false);

        when(authorization.requireCapability(auth, 10, CommunityCapability.TEAM_VIEW))
            .thenReturn(context(Set.of(CommunityCapability.TEAM_VIEW)));
        when(roles.findByCommunityOrderByNameAsc(community)).thenReturn(List.of(child, root));

        var result = service.listRoles(auth, 10);

        assertEquals(List.of(101, 100), result.stream().map(item -> item.id()).toList());
        assertEquals(100, result.getFirst().parentRoleId());
        assertFalse(result.getFirst().active());
        verify(roles).findByCommunityOrderByNameAsc(community);
        verifyNoMoreInteractions(roles);
    }

    @Test
    void listMembersReturnsOnlySummaryFieldsAndCommunityRoleIds() {
        User member = new User();
        member.setId(7);
        member.setDisplayName("Fox");
        member.setUsername("fox");
        member.setProfileImageUrl("https://cdn.example/fox.webp");

        UserCommunityStatus membership = new UserCommunityStatus();
        membership.setUser(member);
        membership.setCommunity(community);
        membership.setApproved(true);
        membership.setBanned(false);
        membership.setIsPresent(true);
        membership.setApprovalRequired(true);

        when(authorization.requireCapability(auth, 10, CommunityCapability.MEMBERS_VIEW))
            .thenReturn(context(Set.of(CommunityCapability.MEMBERS_VIEW)));
        when(users.searchCommunityMembers(eq(10), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(member)));
        when(memberships.findAllByCommunityIdAndUserIdIn(eq(10), anyCollection()))
            .thenReturn(List.of(membership));
        when(assignments.findByCommunityIdAndUserIdInOrderByUserIdAscRoleIdAsc(eq(10), anyCollection()))
            .thenReturn(List.of(
                new CommunityTeamRoleAssignment(10, 2, 7),
                new CommunityTeamRoleAssignment(10, 3, 7)
            ));

        CommunityMemberSummaryResponse result = service.listMembers(auth, 10, 1, 20, "   ");

        assertEquals(1, result.items().size());
        CommunityMemberSummary summary = result.items().getFirst();
        assertEquals(7, summary.userId());
        assertEquals("Fox", summary.displayName());
        assertEquals("fox", summary.username());
        assertEquals("active", summary.status());
        assertEquals(List.of(2, 3), summary.roleIds());
        assertEquals(1, result.pagination().page());
        assertEquals(20, result.pagination().pageSize());
    }

    @Test
    void listMembersKeepsLegacyAmbiguityOutOfDetailedFields() {
        User member = new User();
        member.setId(8);
        member.setUsername("legacy");

        UserCommunityStatus first = membership(member, true, false, true);
        UserCommunityStatus second = membership(member, true, false, false);

        when(authorization.requireCapability(auth, 10, CommunityCapability.MEMBERS_VIEW))
            .thenReturn(context(Set.of(CommunityCapability.MEMBERS_VIEW)));
        when(users.searchCommunityMembers(eq(10), eq("legacy"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(member)));
        when(memberships.findAllByCommunityIdAndUserIdIn(eq(10), anyCollection()))
            .thenReturn(List.of(first, second));
        when(assignments.findByCommunityIdAndUserIdInOrderByUserIdAscRoleIdAsc(eq(10), anyCollection()))
            .thenReturn(List.of());

        CommunityMemberSummaryResponse result =
            service.listMembers(auth, 10, 0, 500, " legacy ");

        assertEquals("ambiguous", result.items().getFirst().status());
        assertEquals("legacy", result.items().getFirst().displayName());
        assertEquals(1, result.pagination().page());
        assertEquals(100, result.pagination().pageSize());
    }

    @Test
    void emptyMembersPageDoesNotReadMembershipOrAssignments() {
        when(authorization.requireCapability(auth, 10, CommunityCapability.MEMBERS_VIEW))
            .thenReturn(context(Set.of(CommunityCapability.MEMBERS_VIEW)));
        when(users.searchCommunityMembers(eq(10), isNull(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        CommunityMemberSummaryResponse result = service.listMembers(auth, 10, 1, 20, null);

        assertTrue(result.items().isEmpty());
        verifyNoInteractions(memberships, assignments);
    }

    @Test
    void resolvesExactDiscordIdWithinTheAuthorizedCommunity() {
        when(authorization.requireCapability(auth, 10, CommunityCapability.MEMBERS_VIEW))
            .thenReturn(context(Set.of(CommunityCapability.MEMBERS_VIEW)));
        when(users.searchCommunityMembersByDiscordId(eq(10), eq(223456789012345678L), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        var result = service.listMembers(auth, 10, 1, 20, "223456789012345678");

        assertTrue(result.items().isEmpty());
        verify(users).searchCommunityMembersByDiscordId(eq(10), eq(223456789012345678L), any(Pageable.class));
        verify(users, never()).searchCommunityMembers(anyInt(), any(), any(Pageable.class));
    }

    @Test
    void rejectsOutOfRangeDiscordIdWithoutBroadTextFallback() {
        when(authorization.requireCapability(auth, 10, CommunityCapability.MEMBERS_VIEW))
            .thenReturn(context(Set.of(CommunityCapability.MEMBERS_VIEW)));
        when(users.searchCommunityMembersByDiscordId(eq(10), eq(Long.MIN_VALUE), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        service.listMembers(auth, 10, 1, 20, "99999999999999999999");

        verify(users).searchCommunityMembersByDiscordId(eq(10), eq(Long.MIN_VALUE), any(Pageable.class));
        verify(users, never()).searchCommunityMembers(anyInt(), any(), any(Pageable.class));
    }

    private CommunityAuthorizationService.CommunityAccessContext context(
        Set<CommunityCapability> capabilities
    ) {
        return new CommunityAuthorizationService.CommunityAccessContext(
            community,
            actor,
            true,
            false,
            true,
            capabilities,
            List.of(),
            Map.of(),
            Set.of()
        );
    }

    private CommunityTeamRole role(int id, CommunityTeamRole parent, boolean active) {
        CommunityTeamRole value = new CommunityTeamRole();
        value.setId(id);
        value.setCommunity(community);
        value.setParentRole(parent);
        value.setName("Role " + id);
        value.setActive(active);
        return value;
    }

    private UserCommunityStatus membership(
        User user,
        Boolean approved,
        Boolean banned,
        Boolean present
    ) {
        UserCommunityStatus value = new UserCommunityStatus();
        value.setUser(user);
        value.setCommunity(community);
        value.setApproved(approved);
        value.setBanned(banned);
        value.setIsPresent(present);
        value.setApprovalRequired(true);
        return value;
    }
}
