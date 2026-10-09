package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.community.CommunityTeamRole;
import com.Brafurries.API.entity.community.CommunityTeamRoleAssignment;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleAssignmentRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.dto.CommunityTeamAssignmentDtos.AssignmentResponse;
import com.Brafurries.API.user.dto.CommunityTeamAssignmentDtos.TeamMemberRef;
import com.Brafurries.API.user.dto.CommunityTeamAssignmentDtos.TeamMemberCandidateResponse;
import com.Brafurries.API.user.dto.CommunityTeamRoleDtos.RoleResponse;
import java.time.LocalDateTime;
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
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommunityTeamAssignmentServiceTest {
    @Mock CommunityDiscordRepository communities;
    @Mock CommunityTeamRoleRepository roles;
    @Mock CommunityTeamRoleAssignmentRepository assignments;
    @Mock UserCommunityStatusRepository memberships;
    @Mock UserRepository users;
    @Mock CommunityMemberService memberService;
    @Mock CommunityAuthorizationService communityAuthorization;
    @Mock CommunityAuditLogRepository auditRepository;
    @Mock Authentication auth;

    private CommunityTeamAssignmentService service;
    private Community community;

    @BeforeEach
    void setUp() {
        community = new Community();
        community.setId(1);
        community.setName("BraFurries");

        service = new CommunityTeamAssignmentService(
            communities,
            roles,
            assignments,
            memberships,
            users,
            memberService,
            communityAuthorization,
            auditRepository
        );

        CommunityDiscord discord = new CommunityDiscord();
        discord.setGuildId(10L);
        discord.setCommunity(community);
        lenient().when(communities.findByGuildId(10L)).thenReturn(Optional.of(discord));
        CommunityAuthorizationService.CommunityAccessContext actor = accessContext(community);
        lenient().when(communityAuthorization.resolve(any(), anyInt())).thenReturn(actor);
        lenient().when(communityAuthorization.requireCapability(any(), anyInt(), any())).thenReturn(actor);
        lenient().when(communityAuthorization.requireTeamTarget(any(), anyInt(), any(), anyInt())).thenReturn(actor);
        lenient().when(communityAuthorization.isAuthorizationEligibleMembership(any()))
            .thenAnswer(invocation -> {
                UserCommunityStatus membership = invocation.getArgument(0);
                return Boolean.TRUE.equals(membership.getApproved())
                    && !Boolean.TRUE.equals(membership.getBanned())
                    && Boolean.TRUE.equals(membership.getIsPresent());
            });
    }

    @Test
    void assignsActiveEligibleMemberWithinAuthorizedCommunity() {
        CommunityTeamRole role = role(3, true);
        UserCommunityStatus membership = membership(7, false, true);
        when(roles.findForAssignmentByIdAndCommunity(3, community)).thenReturn(Optional.of(role));
        when(memberships.findAllForAssignmentByUserIdAndCommunityId(7, 1))
            .thenReturn(List.of(membership));
        CommunityTeamRoleAssignment stored = new CommunityTeamRoleAssignment(1, 3, 7);
        when(assignments.findByCommunityIdAndRoleIdAndUserId(1, 3, 7))
            .thenReturn(Optional.empty(), Optional.of(stored));

        AssignmentResponse response = service.assign(auth, "10", 3, 7);

        assertEquals(3, response.roleId());
        assertEquals(7, response.userId());
        verify(assignments).insertIfAbsent(1, 3, 7);
        verify(communityAuthorization).requireTeamTarget(
            auth, 1, CommunityCapability.TEAM_ASSIGN_MEMBERS, 3
        );
    }

    @Test
    void duplicateAssignmentIsIdempotent() {
        CommunityTeamRole role = role(3, true);
        when(roles.findForAssignmentByIdAndCommunity(3, community)).thenReturn(Optional.of(role));
        when(assignments.findByCommunityIdAndRoleIdAndUserId(1, 3, 7))
            .thenReturn(Optional.of(new CommunityTeamRoleAssignment(1, 3, 7)));

        AssignmentResponse response = service.assign(auth, "10", 3, 7);

        assertEquals(3, response.roleId());
        assertEquals(7, response.userId());
        verify(assignments, never()).insertIfAbsent(anyInt(), anyInt(), anyInt());
    }

    @Test
    void existingAssignmentStaysIdempotentAfterRoleBecomesInactiveAndMemberLeaves() {
        CommunityTeamRole inactive = role(3, false);
        CommunityTeamRoleAssignment existing = new CommunityTeamRoleAssignment(1, 3, 7);
        when(roles.findForAssignmentByIdAndCommunity(3, community)).thenReturn(Optional.of(inactive));
        when(assignments.findByCommunityIdAndRoleIdAndUserId(1, 3, 7))
            .thenReturn(Optional.of(existing));

        AssignmentResponse response = service.assign(auth, "10", 3, 7);

        assertEquals(3, response.roleId());
        assertEquals(7, response.userId());
        verifyNoInteractions(memberships);
        verify(assignments, never()).insertIfAbsent(anyInt(), anyInt(), anyInt());
    }

    @Test
    void concurrentDuplicateInsertUsesIdempotentUpsertAndReloadsWinner() {
        CommunityTeamRole role = role(3, true);
        CommunityTeamRoleAssignment winner = new CommunityTeamRoleAssignment(1, 3, 7);
        when(roles.findForAssignmentByIdAndCommunity(3, community)).thenReturn(Optional.of(role));
        when(memberships.findAllForAssignmentByUserIdAndCommunityId(7, 1))
            .thenReturn(List.of(membership(7, false, true)));
        when(assignments.findByCommunityIdAndRoleIdAndUserId(1, 3, 7))
            .thenReturn(Optional.empty(), Optional.of(winner));

        AssignmentResponse response = service.assign(auth, "10", 3, 7);

        assertEquals(3, response.roleId());
        assertEquals(7, response.userId());
        verify(assignments).insertIfAbsent(1, 3, 7);
    }

    @Test
    void rejectsRoleOutsideAuthorizedCommunityBeforeMembershipLookup() {
        when(roles.findForAssignmentByIdAndCommunity(99, community)).thenReturn(Optional.empty());

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.assign(auth, "10", 99, 7)
        );

        assertEquals(404, exception.getStatusCode().value());
        verifyNoInteractions(memberships, assignments);
    }

    @Test
    void rejectsInactiveRoleAndIneligibleMembershipForNewAssignments() {
        CommunityTeamRole inactive = role(3, false);
        when(roles.findForAssignmentByIdAndCommunity(3, community)).thenReturn(Optional.of(inactive));

        ResponseStatusException inactiveError = assertThrows(
            ResponseStatusException.class,
            () -> service.assign(auth, "10", 3, 7)
        );
        assertEquals(422, inactiveError.getStatusCode().value());
        verifyNoInteractions(memberships);

        CommunityTeamRole active = role(4, true);
        when(roles.findForAssignmentByIdAndCommunity(4, community)).thenReturn(Optional.of(active));
        when(memberships.findAllForAssignmentByUserIdAndCommunityId(8, 1))
            .thenReturn(List.of(membership(8, true, true)));
        when(memberships.findAllForAssignmentByUserIdAndCommunityId(9, 1))
            .thenReturn(List.of(membership(9, false, false)));

        ResponseStatusException banned = assertThrows(
            ResponseStatusException.class,
            () -> service.assign(auth, "10", 4, 8)
        );
        ResponseStatusException left = assertThrows(
            ResponseStatusException.class,
            () -> service.assign(auth, "10", 4, 9)
        );

        assertEquals(422, banned.getStatusCode().value());
        assertEquals(422, left.getStatusCode().value());
        verify(assignments, never()).insertIfAbsent(anyInt(), anyInt(), anyInt());
    }

    @Test
    void missingMemberInCommunityIsRejected() {
        CommunityTeamRole role = role(3, true);
        when(roles.findForAssignmentByIdAndCommunity(3, community)).thenReturn(Optional.of(role));
        when(memberships.findAllForAssignmentByUserIdAndCommunityId(99, 1))
            .thenReturn(List.of());

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.assign(auth, "10", 3, 99)
        );

        assertEquals(404, exception.getStatusCode().value());
        verify(assignments, never()).insertIfAbsent(anyInt(), anyInt(), anyInt());
    }

    @Test
    void duplicateMembershipStateFailsClosed() {
        CommunityTeamRole role = role(3, true);
        when(roles.findForAssignmentByIdAndCommunity(3, community)).thenReturn(Optional.of(role));
        when(memberships.findAllForAssignmentByUserIdAndCommunityId(7, 1))
            .thenReturn(List.of(
                membership(7, false, true),
                membership(7, false, true)
            ));

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.assign(auth, "10", 3, 7)
        );

        assertEquals(409, exception.getStatusCode().value());
        verify(assignments, never()).insertIfAbsent(anyInt(), anyInt(), anyInt());
    }

    @Test
    void rejectsRemovingMemberOutsideAuthorizedCommunity() {
        when(roles.findByIdAndCommunity(3, community)).thenReturn(Optional.of(role(3, true)));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(99, 1))
            .thenReturn(List.of());

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.remove(auth, "10", 3, 99)
        );

        assertEquals(404, exception.getStatusCode().value());
        verify(assignments, never()).deleteByCommunityIdAndRoleIdAndUserId(anyInt(), anyInt(), anyInt());
    }

    @Test
    void listsCanonicalMembersForRoleUsingOnlyTenantScopedAssignments() {
        when(roles.findByIdAndCommunity(3, community)).thenReturn(Optional.of(role(3, true)));
        when(assignments.findByCommunityIdAndRoleIdOrderByUserIdAsc(1, 3))
            .thenReturn(List.of(
                new CommunityTeamRoleAssignment(1, 3, 7),
                new CommunityTeamRoleAssignment(1, 3, 8)
            ));
        when(memberService.listByUserIds(community, List.of(7, 8))).thenReturn(List.of());

        assertTrue(service.listMembers(auth, "10", 3).isEmpty());

        verify(memberService).listByUserIds(community, List.of(7, 8));
        verify(assignments).findByCommunityIdAndRoleIdOrderByUserIdAsc(1, 3);
    }

    @Test
    void canonicalAssignUsesStrictTeamScopeEligibilityAndAudits() {
        User actorUser = new User();
        actorUser.setId(50);
        CommunityAuthorizationService.CommunityAccessContext actor =
            new CommunityAuthorizationService.CommunityAccessContext(
                community,
                actorUser,
                false,
                false,
                true,
                Set.of(CommunityCapability.TEAM_ASSIGN_MEMBERS),
                List.of(2),
                Map.of(),
                Set.of(2, 3)
            );
        CommunityTeamRole role = role(3, true);
        UserCommunityStatus membership = membership(7, false, true);
        CommunityTeamRoleAssignment stored = new CommunityTeamRoleAssignment(1, 3, 7);

        when(communityAuthorization.requireTeamTarget(
            auth,
            1,
            CommunityCapability.TEAM_ASSIGN_MEMBERS,
            3
        )).thenReturn(actor);
        when(roles.findForAssignmentByIdAndCommunity(3, community)).thenReturn(Optional.of(role));
        when(assignments.findByCommunityIdAndRoleIdAndUserId(1, 3, 7))
            .thenReturn(Optional.empty(), Optional.of(stored));
        when(memberships.findAllForAssignmentByUserIdAndCommunityId(7, 1))
            .thenReturn(List.of(membership));
        when(communityAuthorization.isAuthorizationEligibleMembership(membership))
            .thenReturn(true);

        AssignmentResponse response = service.assignCommunity(auth, 1, 3, 7);

        assertEquals(3, response.roleId());
        assertEquals(7, response.userId());
        verify(assignments).insertIfAbsent(1, 3, 7);
        verify(auditRepository).save(argThat(entry ->
            "TEAM_MEMBER_ASSIGNED".equals(entry.getAction())
                && "TEAM_ROLE_ASSIGNMENT".equals(entry.getTargetType())
                && "3:7".equals(entry.getTargetId())
        ));
    }

    @Test
    void canonicalAssignFailsClosedForAmbiguousLegacyMembershipState() {
        User actorUser = new User();
        actorUser.setId(50);
        CommunityAuthorizationService.CommunityAccessContext actor =
            new CommunityAuthorizationService.CommunityAccessContext(
                community,
                actorUser,
                false,
                false,
                true,
                Set.of(CommunityCapability.TEAM_ASSIGN_MEMBERS),
                List.of(2),
                Map.of(),
                Set.of(2, 3)
            );
        CommunityTeamRole role = role(3, true);
        UserCommunityStatus membership = membership(7, false, null);

        when(communityAuthorization.requireTeamTarget(
            auth,
            1,
            CommunityCapability.TEAM_ASSIGN_MEMBERS,
            3
        )).thenReturn(actor);
        when(roles.findForAssignmentByIdAndCommunity(3, community)).thenReturn(Optional.of(role));
        when(assignments.findByCommunityIdAndRoleIdAndUserId(1, 3, 7))
            .thenReturn(Optional.empty());
        when(memberships.findAllForAssignmentByUserIdAndCommunityId(7, 1))
            .thenReturn(List.of(membership));
        when(communityAuthorization.isAuthorizationEligibleMembership(membership))
            .thenReturn(false);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.assignCommunity(auth, 1, 3, 7)
        );

        assertEquals(422, error.getStatusCode().value());
        verify(assignments, never()).insertIfAbsent(anyInt(), anyInt(), anyInt());
        verifyNoInteractions(auditRepository);
    }

    @Test
    void teamAssignMemberCandidateSearchNeedsRoleScopeWithoutMembersView() {
        User actorUser = new User();
        actorUser.setId(50);
        CommunityAuthorizationService.CommunityAccessContext actor =
            new CommunityAuthorizationService.CommunityAccessContext(
                community,
                actorUser,
                false,
                false,
                true,
                Set.of(CommunityCapability.TEAM_ASSIGN_MEMBERS, CommunityCapability.TEAM_VIEW),
                List.of(2),
                Map.of(),
                Set.of(2, 3)
            );
        User candidate = new User();
        candidate.setId(7);
        candidate.setDisplayName("Fox");
        candidate.setUsername("fox");

        when(communityAuthorization.requireTeamTarget(
            auth,
            1,
            CommunityCapability.TEAM_ASSIGN_MEMBERS,
            3
        )).thenReturn(actor);
        when(roles.findByIdAndCommunity(3, community))
            .thenReturn(Optional.of(role(3, true)));
        when(users.searchEligibleCommunityTeamCandidates(
            eq(1),
            eq("fox"),
            any(Pageable.class)
        )).thenReturn(new PageImpl<>(List.of(candidate)));

        TeamMemberCandidateResponse response =
            service.listMemberCandidatesCommunity(auth, 1, 3, 0, 500, " fox ");

        assertEquals(1, response.items().size());
        assertEquals(7, response.items().getFirst().userId());
        assertEquals("Fox", response.items().getFirst().displayName());
        assertEquals(1, response.pagination().page());
        assertEquals(100, response.pagination().pageSize());
        verify(communityAuthorization, never()).requireCapability(
            auth,
            1,
            CommunityCapability.MEMBERS_VIEW
        );
    }

    @Test
    void canonicalMembersViewReturnsMinimalMemberReferences() {
        User actorUser = new User();
        actorUser.setId(50);
        CommunityAuthorizationService.CommunityAccessContext actor =
            new CommunityAuthorizationService.CommunityAccessContext(
                community,
                actorUser,
                false,
                false,
                true,
                Set.of(CommunityCapability.MEMBERS_VIEW, CommunityCapability.TEAM_VIEW),
                List.of(2),
                Map.of(),
                Set.of(2, 3)
            );
        UserCommunityStatus target = membership(7, false, true);
        target.getUser().setUsername("fox");
        target.getUser().setProfileImageUrl("https://cdn.example/fox.webp");

        when(communityAuthorization.resolve(auth, 1)).thenReturn(actor);
        when(roles.findByIdAndCommunity(3, community)).thenReturn(Optional.of(role(3, true)));
        when(assignments.findByCommunityIdAndRoleIdOrderByUserIdAsc(1, 3))
            .thenReturn(List.of(new CommunityTeamRoleAssignment(1, 3, 7)));
        when(memberships.findAllByCommunityIdAndUserIdIn(eq(1), anyCollection()))
            .thenReturn(List.of(target));

        List<TeamMemberRef> members = service.listMembersCommunity(auth, 1, 3);

        assertEquals(1, members.size());
        assertEquals(7, members.getFirst().userId());
        assertEquals("Membro 7", members.getFirst().displayName());
        assertEquals("fox", members.getFirst().username());
        assertEquals("https://cdn.example/fox.webp", members.getFirst().profileImageUrl());
        verify(communityAuthorization, never()).requireTeamTarget(
            auth,
            1,
            CommunityCapability.TEAM_ASSIGN_MEMBERS,
            3
        );
        verifyNoInteractions(memberService);
    }

    @Test
    void canonicalAssignmentManagerCanReadMembersOnlyThroughTargetScope() {
        User actorUser = new User();
        actorUser.setId(50);
        CommunityAuthorizationService.CommunityAccessContext actor =
            new CommunityAuthorizationService.CommunityAccessContext(
                community,
                actorUser,
                false,
                false,
                true,
                Set.of(CommunityCapability.TEAM_ASSIGN_MEMBERS, CommunityCapability.TEAM_VIEW),
                List.of(2),
                Map.of(),
                Set.of(2, 3)
            );
        UserCommunityStatus target = membership(7, false, true);

        when(communityAuthorization.resolve(auth, 1)).thenReturn(actor);
        when(communityAuthorization.requireTeamTarget(
            auth,
            1,
            CommunityCapability.TEAM_ASSIGN_MEMBERS,
            3
        )).thenReturn(actor);
        when(roles.findByIdAndCommunity(3, community)).thenReturn(Optional.of(role(3, true)));
        when(assignments.findByCommunityIdAndRoleIdOrderByUserIdAsc(1, 3))
            .thenReturn(List.of(new CommunityTeamRoleAssignment(1, 3, 7)));
        when(memberships.findAllByCommunityIdAndUserIdIn(eq(1), anyCollection()))
            .thenReturn(List.of(target));

        List<TeamMemberRef> members = service.listMembersCommunity(auth, 1, 3);

        assertEquals(List.of(7), members.stream().map(TeamMemberRef::userId).toList());
        verify(communityAuthorization).requireTeamTarget(
            auth,
            1,
            CommunityCapability.TEAM_ASSIGN_MEMBERS,
            3
        );
    }

    @Test
    void canonicalTeamViewAloneCannotReadMemberReferences() {
        User actorUser = new User();
        actorUser.setId(50);
        CommunityAuthorizationService.CommunityAccessContext actor =
            new CommunityAuthorizationService.CommunityAccessContext(
                community,
                actorUser,
                false,
                false,
                true,
                Set.of(CommunityCapability.TEAM_VIEW),
                List.of(2),
                Map.of(),
                Set.of(2, 3)
            );

        when(communityAuthorization.resolve(auth, 1)).thenReturn(actor);
        doThrow(new ResponseStatusException(
            org.springframework.http.HttpStatus.FORBIDDEN,
            "scope"
        )).when(communityAuthorization).requireTeamTarget(
            auth,
            1,
            CommunityCapability.TEAM_ASSIGN_MEMBERS,
            3
        );

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.listMembersCommunity(auth, 1, 3)
        );

        assertEquals(403, exception.getStatusCode().value());
        verifyNoInteractions(assignments, memberships);
    }

    @Test
    void canonicalRemoveAuditsOnlyWhenAssignmentWasDeleted() {
        User actorUser = new User();
        actorUser.setId(50);
        CommunityAuthorizationService.CommunityAccessContext actor =
            new CommunityAuthorizationService.CommunityAccessContext(
                community,
                actorUser,
                false,
                false,
                true,
                Set.of(CommunityCapability.TEAM_ASSIGN_MEMBERS),
                List.of(2),
                Map.of(),
                Set.of(2, 3)
            );
        UserCommunityStatus target = membership(7, false, false);

        when(communityAuthorization.requireTeamTarget(
            auth,
            1,
            CommunityCapability.TEAM_ASSIGN_MEMBERS,
            3
        )).thenReturn(actor);
        when(roles.findByIdAndCommunity(3, community)).thenReturn(Optional.of(role(3, false)));
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 1))
            .thenReturn(List.of(target));
        when(assignments.deleteByCommunityIdAndRoleIdAndUserId(1, 3, 7))
            .thenReturn(1L, 0L);

        service.removeCommunity(auth, 1, 3, 7);
        service.removeCommunity(auth, 1, 3, 7);

        verify(auditRepository, times(1)).save(argThat(entry ->
            "TEAM_MEMBER_REMOVED".equals(entry.getAction())
        ));
    }

    @Test
    void existingAssignmentsRemainReadableAndRemovableAfterMemberLeaves() {
        UserCommunityStatus left = membership(7, true, false);
        when(memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(7, 1))
            .thenReturn(List.of(left));
        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(1, 7))
            .thenReturn(List.of(new CommunityTeamRoleAssignment(1, 3, 7)));
        when(roles.findByCommunityAndIdInOrderByNameAsc(community, List.of(3)))
            .thenReturn(List.of(role(3, false)));

        List<RoleResponse> roleResponses = service.listRoles(auth, "10", 7);

        assertEquals(List.of(3), roleResponses.stream().map(RoleResponse::id).toList());

        when(roles.findByIdAndCommunity(3, community)).thenReturn(Optional.of(role(3, false)));
        service.remove(auth, "10", 3, 7);
        verify(assignments).deleteByCommunityIdAndRoleIdAndUserId(1, 3, 7);
    }

    private CommunityTeamRole role(int id, boolean active) {
        CommunityTeamRole role = new CommunityTeamRole();
        role.setId(id);
        role.setCommunity(community);
        role.setName("Cargo " + id);
        role.setActive(active);
        return role;
    }

    private static CommunityAuthorizationService.CommunityAccessContext accessContext(Community community) {
        User actor = new User(); actor.setId(99);
        return new CommunityAuthorizationService.CommunityAccessContext(
            community, actor, true, true, true,
            Set.of(CommunityCapability.values()), List.of(), Map.of(), Set.of()
        );
    }

    private UserCommunityStatus membership(int userId, boolean banned, Boolean present) {
        User user = new User();
        user.setId(userId);
        user.setDisplayName("Membro " + userId);

        UserCommunityStatus membership = new UserCommunityStatus();
        membership.setId(userId);
        membership.setUser(user);
        membership.setCommunity(community);
        membership.setMemberSince(LocalDateTime.of(2026, 1, 1, 0, 0));
        membership.setApproved(true);
        membership.setBanned(banned);
        membership.setIsPresent(present);
        membership.setIsVip(false);
        membership.setIsPartner(false);
        membership.setBirthdayMentionable(false);
        return membership;
    }
}
