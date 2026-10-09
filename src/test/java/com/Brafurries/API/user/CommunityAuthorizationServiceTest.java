package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.CommunityAccessDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityTeamRole;
import com.Brafurries.API.entity.community.CommunityTeamRoleAssignment;
import com.Brafurries.API.entity.community.CommunityTeamRoleCapability;
import com.Brafurries.API.entity.community.CommunityUserCapabilityGrant;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleAssignmentRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleCapabilityRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleRepository;
import com.Brafurries.API.repository.community.CommunityUserCapabilityGrantRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommunityAuthorizationServiceTest {
    @Mock CommunityRepository communities;
    @Mock UserRepository users;
    @Mock UserCommunityStatusRepository memberships;
    @Mock CommunityTeamRoleRepository roles;
    @Mock CommunityTeamRoleAssignmentRepository assignments;
    @Mock CommunityTeamRoleCapabilityRepository roleCapabilities;
    @Mock CommunityUserCapabilityGrantRepository directGrants;
    @Mock CommunityNetworkAvailabilityService networkAvailability;
    @Mock Authentication auth;

    private CommunityAuthorizationService service;
    private User user;
    private Community community;

    @BeforeEach
    void setUp() {
        service = new CommunityAuthorizationService(
            communities,
            users,
            memberships,
            roles,
            assignments,
            roleCapabilities,
            directGrants,
            networkAvailability,
            new CommunityMembershipStatusResolver()
        );
        user = user(1, "member@example.com");
        community = community(10, "BraFurries", null);
        when(auth.getName()).thenReturn("MEMBER@EXAMPLE.COM");
        when(users.findByEmail("member@example.com")).thenReturn(Optional.of(user));
        lenient().when(networkAvailability.isPortariaEnabled(any(Community.class))).thenReturn(true);
    }

    @Test
    void ownerHasAllOperationalCapabilitiesAndUnrestrictedTeamScopesWithoutMembership() {
        community.setOwnerUser(user);
        CommunityTeamRole root = role(1, null, true);
        CommunityTeamRole child = role(2, root, true);
        stubCommunity(List.of(root, child), null);

        CommunityAccessResponse response = service.getAccess(auth, 10);

        assertTrue(response.owner());
        assertFalse(response.communityAdmin());
        assertFalse(response.activeMember());
        assertTrue(response.canManageCommunityAdmins());
        assertEquals(Set.of(
            "COMMUNITY_ADMIN",
            "TEAM_VIEW",
            "TEAM_ASSIGN_MEMBERS",
            "TEAM_MANAGE_STRUCTURE",
            "TEAM_MANAGE_DEMANDS",
            "MEMBERS_VIEW",
            "MEMBER_DETAILS_VIEW",
            "MEMBER_NOTES_VIEW",
            "MEMBER_NOTES_MANAGE"
        ), response.capabilities());

        TeamCapabilityScope structure = scope(response, "TEAM_MANAGE_STRUCTURE");
        assertTrue(structure.unrestricted());
        assertEquals(List.of(), structure.sourceRoleIds());
        assertEquals(List.of(1, 2), structure.targetRoleIds());
        verifyNoInteractions(assignments, directGrants, roleCapabilities);
    }

    @Test
    void inactiveNetworkBlocksDirectCommunityAdministrationEvenForOwner() {
        community.setOwnerUser(user);
        when(communities.findById(10)).thenReturn(Optional.of(community));
        when(networkAvailability.isPortariaEnabled(community)).thenThrow(
            new ResponseStatusException(HttpStatus.NOT_FOUND, "Community não encontrada")
        );

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.requireCapability(auth, 10, CommunityCapability.COMMUNITY_ADMIN)
        );

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verifyNoInteractions(assignments, directGrants, roleCapabilities);
    }

    @Test
    void communityAdminMustComeFromDirectGrantAndCannotManageOtherAdmins() {
        CommunityTeamRole root = role(1, null, true);
        stubCommunity(List.of(root), eligibleMembership());
        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(10, 1)).thenReturn(List.of());
        when(directGrants.findByCommunityIdAndUserId(10, 1)).thenReturn(List.of(
            new CommunityUserCapabilityGrant(10, 1, "COMMUNITY_ADMIN", 99)
        ));

        CommunityAccessResponse response = service.getAccess(auth, 10);

        assertFalse(response.owner());
        assertTrue(response.communityAdmin());
        assertTrue(response.activeMember());
        assertFalse(response.canManageCommunityAdmins());
        assertTrue(response.capabilities().containsAll(Set.of(
            "TEAM_MANAGE_STRUCTURE",
            "MEMBER_DETAILS_VIEW",
            "MEMBER_NOTES_MANAGE"
        )));
        assertTrue(scope(response, "TEAM_MANAGE_STRUCTURE").unrestricted());

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.requireOwnerForCommunityAdminManagement(auth, 10)
        );
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    @Test
    void roleCannotGrantCommunityAdminOrNotesAndDirectGrantCannotCreateTeamHierarchyScope() {
        CommunityTeamRole source = role(2, null, true);
        stubCommunity(List.of(source), eligibleMembership());
        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(10, 1))
            .thenReturn(List.of(new CommunityTeamRoleAssignment(10, 2, 1)));
        when(directGrants.findByCommunityIdAndUserId(10, 1)).thenReturn(List.of(
            new CommunityUserCapabilityGrant(10, 1, "TEAM_MANAGE_STRUCTURE", 99),
            new CommunityUserCapabilityGrant(10, 1, "MEMBER_NOTES_VIEW", 99)
        ));
        when(roleCapabilities.findByCommunityIdAndRoleIdIn(eq(10), any())).thenReturn(List.of(
            new CommunityTeamRoleCapability(10, 2, "COMMUNITY_ADMIN", 99),
            new CommunityTeamRoleCapability(10, 2, "MEMBER_NOTES_MANAGE", 99)
        ));

        CommunityAccessResponse response = service.getAccess(auth, 10);

        assertFalse(response.communityAdmin());
        assertTrue(response.capabilities().contains("TEAM_VIEW"));
        assertTrue(response.capabilities().contains("MEMBER_NOTES_VIEW"));
        assertFalse(response.capabilities().contains("MEMBER_NOTES_MANAGE"));
        assertFalse(response.capabilities().contains("TEAM_MANAGE_STRUCTURE"));
        assertTrue(response.teamScopes().isEmpty());
    }

    @Test
    void memberDetailsImpliesMembersViewForDirectAndRoleDerivedAccess() {
        CommunityTeamRole source = role(2, null, true);
        stubCommunity(List.of(source), eligibleMembership());
        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(10, 1))
            .thenReturn(List.of(new CommunityTeamRoleAssignment(10, 2, 1)));
        when(directGrants.findByCommunityIdAndUserId(10, 1)).thenReturn(List.of(
            new CommunityUserCapabilityGrant(10, 1, "MEMBER_DETAILS_VIEW", 99)
        ));
        when(roleCapabilities.findByCommunityIdAndRoleIdIn(eq(10), any())).thenReturn(List.of());

        CommunityAccessResponse response = service.getAccess(auth, 10);

        assertTrue(response.capabilities().contains("MEMBER_DETAILS_VIEW"));
        assertTrue(response.capabilities().contains("MEMBERS_VIEW"));
        assertTrue(response.capabilities().contains("TEAM_VIEW"));
    }

    @Test
    void roleDerivedMemberDetailsAlsoImpliesMembersView() {
        CommunityTeamRole source = role(2, null, true);
        stubCommunity(List.of(source), eligibleMembership());
        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(10, 1))
            .thenReturn(List.of(new CommunityTeamRoleAssignment(10, 2, 1)));
        when(directGrants.findByCommunityIdAndUserId(10, 1)).thenReturn(List.of());
        when(roleCapabilities.findByCommunityIdAndRoleIdIn(eq(10), any())).thenReturn(List.of(
            new CommunityTeamRoleCapability(10, 2, "MEMBER_DETAILS_VIEW", 99)
        ));

        CommunityAccessResponse response = service.getAccess(auth, 10);

        assertTrue(response.capabilities().contains("MEMBER_DETAILS_VIEW"));
        assertTrue(response.capabilities().contains("MEMBERS_VIEW"));
    }

    @Test
    void hierarchicalCapabilityTargetsStrictDescendantsThroughInactiveIntermediaries() {
        CommunityTeamRole root = role(1, null, true);
        CommunityTeamRole source = role(2, root, true);
        CommunityTeamRole child = role(3, source, false);
        CommunityTeamRole grandchild = role(4, child, true);
        CommunityTeamRole sibling = role(5, root, true);
        CommunityTeamRole deepInactive = role(6, grandchild, false);
        stubCommunity(List.of(root, source, child, grandchild, sibling, deepInactive), eligibleMembership());

        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(10, 1))
            .thenReturn(List.of(new CommunityTeamRoleAssignment(10, 2, 1)));
        when(directGrants.findByCommunityIdAndUserId(10, 1)).thenReturn(List.of());
        when(roleCapabilities.findByCommunityIdAndRoleIdIn(eq(10), any())).thenReturn(List.of(
            new CommunityTeamRoleCapability(10, 2, "TEAM_MANAGE_STRUCTURE", 99)
        ));

        CommunityAccessResponse response = service.getAccess(auth, 10);
        TeamCapabilityScope scope = scope(response, "TEAM_MANAGE_STRUCTURE");

        assertFalse(scope.unrestricted());
        assertEquals(List.of(2), scope.sourceRoleIds());
        assertEquals(List.of(3, 4, 6), scope.targetRoleIds());
        assertFalse(scope.targetRoleIds().contains(2));
        assertFalse(scope.targetRoleIds().contains(1));
        assertFalse(scope.targetRoleIds().contains(5));
        assertTrue(response.capabilities().contains("TEAM_VIEW"));
    }

    @Test
    void multipleSourceRolesUnionTheirStrictDescendantScopes() {
        CommunityTeamRole root = role(1, null, true);
        CommunityTeamRole sourceA = role(2, root, true);
        CommunityTeamRole childA = role(3, sourceA, true);
        CommunityTeamRole sourceB = role(5, root, true);
        CommunityTeamRole childB = role(6, sourceB, true);
        stubCommunity(List.of(root, sourceA, childA, sourceB, childB), eligibleMembership());

        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(10, 1))
            .thenReturn(List.of(
                new CommunityTeamRoleAssignment(10, 2, 1),
                new CommunityTeamRoleAssignment(10, 5, 1)
            ));
        when(directGrants.findByCommunityIdAndUserId(10, 1)).thenReturn(List.of());
        when(roleCapabilities.findByCommunityIdAndRoleIdIn(eq(10), any())).thenReturn(List.of(
            new CommunityTeamRoleCapability(10, 2, "TEAM_ASSIGN_MEMBERS", 99),
            new CommunityTeamRoleCapability(10, 5, "TEAM_ASSIGN_MEMBERS", 99)
        ));

        TeamCapabilityScope scope = scope(service.getAccess(auth, 10), "TEAM_ASSIGN_MEMBERS");

        assertEquals(List.of(2, 5), scope.sourceRoleIds());
        assertEquals(List.of(3, 6), scope.targetRoleIds());
    }

    @Test
    void structureMoveMustStayInsideTheSameSourceScope() {
        CommunityTeamRole root = role(1, null, true);
        CommunityTeamRole sourceA = role(2, root, true);
        CommunityTeamRole childA = role(3, sourceA, true);
        CommunityTeamRole sourceB = role(5, root, true);
        CommunityTeamRole childB = role(6, sourceB, true);
        stubCommunity(List.of(root, sourceA, childA, sourceB, childB), eligibleMembership());

        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(10, 1))
            .thenReturn(List.of(
                new CommunityTeamRoleAssignment(10, 2, 1),
                new CommunityTeamRoleAssignment(10, 5, 1)
            ));
        when(directGrants.findByCommunityIdAndUserId(10, 1)).thenReturn(List.of());
        when(roleCapabilities.findByCommunityIdAndRoleIdIn(eq(10), any())).thenReturn(List.of(
            new CommunityTeamRoleCapability(10, 2, "TEAM_MANAGE_STRUCTURE", 99),
            new CommunityTeamRoleCapability(10, 5, "TEAM_MANAGE_STRUCTURE", 99)
        ));

        assertDoesNotThrow(() -> service.requireTeamMove(
            auth,
            10,
            CommunityCapability.TEAM_MANAGE_STRUCTURE,
            3,
            2
        ));

        ResponseStatusException crossScope = assertThrows(
            ResponseStatusException.class,
            () -> service.requireTeamMove(
                auth,
                10,
                CommunityCapability.TEAM_MANAGE_STRUCTURE,
                3,
                6
            )
        );
        assertEquals(HttpStatus.FORBIDDEN, crossScope.getStatusCode());
    }

    @Test
    void scopedStructureCanCreateBelowSourceButNotAtRootOrAboveScope() {
        CommunityTeamRole root = role(1, null, true);
        CommunityTeamRole source = role(2, root, true);
        CommunityTeamRole child = role(3, source, true);
        stubCommunity(List.of(root, source, child), eligibleMembership());

        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(10, 1))
            .thenReturn(List.of(new CommunityTeamRoleAssignment(10, 2, 1)));
        when(directGrants.findByCommunityIdAndUserId(10, 1)).thenReturn(List.of());
        when(roleCapabilities.findByCommunityIdAndRoleIdIn(eq(10), any())).thenReturn(List.of(
            new CommunityTeamRoleCapability(10, 2, "TEAM_MANAGE_STRUCTURE", 99)
        ));

        assertDoesNotThrow(() -> service.requireTeamCreateParent(
            auth,
            10,
            CommunityCapability.TEAM_MANAGE_STRUCTURE,
            2
        ));
        assertDoesNotThrow(() -> service.requireTeamCreateParent(
            auth,
            10,
            CommunityCapability.TEAM_MANAGE_STRUCTURE,
            3
        ));

        assertEquals(
            HttpStatus.FORBIDDEN,
            assertThrows(
                ResponseStatusException.class,
                () -> service.requireTeamCreateParent(
                    auth,
                    10,
                    CommunityCapability.TEAM_MANAGE_STRUCTURE,
                    null
                )
            ).getStatusCode()
        );
        assertEquals(
            HttpStatus.FORBIDDEN,
            assertThrows(
                ResponseStatusException.class,
                () -> service.requireTeamCreateParent(
                    auth,
                    10,
                    CommunityCapability.TEAM_MANAGE_STRUCTURE,
                    1
                )
            ).getStatusCode()
        );
    }

    @Test
    void demandRoleSetMustFitInsideOneSourceScope() {
        CommunityTeamRole root = role(1, null, true);
        CommunityTeamRole sourceA = role(2, root, true);
        CommunityTeamRole childA = role(3, sourceA, true);
        CommunityTeamRole sourceB = role(5, root, true);
        CommunityTeamRole childB = role(6, sourceB, true);
        stubCommunity(List.of(root, sourceA, childA, sourceB, childB), eligibleMembership());

        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(10, 1))
            .thenReturn(List.of(
                new CommunityTeamRoleAssignment(10, 2, 1),
                new CommunityTeamRoleAssignment(10, 5, 1)
            ));
        when(directGrants.findByCommunityIdAndUserId(10, 1)).thenReturn(List.of());
        when(roleCapabilities.findByCommunityIdAndRoleIdIn(eq(10), any())).thenReturn(List.of(
            new CommunityTeamRoleCapability(10, 2, "TEAM_MANAGE_DEMANDS", 99),
            new CommunityTeamRoleCapability(10, 5, "TEAM_MANAGE_DEMANDS", 99)
        ));

        assertDoesNotThrow(() -> service.requireTeamRoleSet(
            auth,
            10,
            CommunityCapability.TEAM_MANAGE_DEMANDS,
            Set.of(3)
        ));

        ResponseStatusException crossScope = assertThrows(
            ResponseStatusException.class,
            () -> service.requireTeamRoleSet(
                auth,
                10,
                CommunityCapability.TEAM_MANAGE_DEMANDS,
                Set.of(3, 6)
            )
        );
        assertEquals(HttpStatus.FORBIDDEN, crossScope.getStatusCode());

        ResponseStatusException globalDemand = assertThrows(
            ResponseStatusException.class,
            () -> service.requireTeamRoleSet(
                auth,
                10,
                CommunityCapability.TEAM_MANAGE_DEMANDS,
                Set.of()
            )
        );
        assertEquals(HttpStatus.FORBIDDEN, globalDemand.getStatusCode());
    }

    @Test
    void inactiveAssignedRoleGrantsNoTeamAccess() {
        CommunityTeamRole source = role(2, null, false);
        stubCommunity(List.of(source), eligibleMembership());
        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(10, 1))
            .thenReturn(List.of(new CommunityTeamRoleAssignment(10, 2, 1)));
        when(directGrants.findByCommunityIdAndUserId(10, 1)).thenReturn(List.of());

        CommunityAccessResponse response = service.getAccess(auth, 10);

        assertTrue(response.activeRoleIds().isEmpty());
        assertFalse(response.capabilities().contains("TEAM_VIEW"));
        verifyNoInteractions(roleCapabilities);
    }

    @Test
    void notesManageDirectGrantImpliesNotesView() {
        stubCommunity(List.of(), eligibleMembership());
        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(10, 1)).thenReturn(List.of());
        when(directGrants.findByCommunityIdAndUserId(10, 1)).thenReturn(List.of(
            new CommunityUserCapabilityGrant(10, 1, "MEMBER_NOTES_MANAGE", 99)
        ));

        CommunityAccessResponse response = service.getAccess(auth, 10);

        assertTrue(response.capabilities().contains("MEMBER_NOTES_MANAGE"));
        assertTrue(response.capabilities().contains("MEMBER_NOTES_VIEW"));
    }

    @Test
    void unknownCapabilityCodesFailClosed() {
        CommunityTeamRole source = role(2, null, true);
        stubCommunity(List.of(source), eligibleMembership());
        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(10, 1))
            .thenReturn(List.of(new CommunityTeamRoleAssignment(10, 2, 1)));
        when(directGrants.findByCommunityIdAndUserId(10, 1)).thenReturn(List.of(
            new CommunityUserCapabilityGrant(10, 1, "FUTURE_UNKNOWN", 99)
        ));
        when(roleCapabilities.findByCommunityIdAndRoleIdIn(eq(10), any())).thenReturn(List.of(
            new CommunityTeamRoleCapability(10, 2, "ALSO_UNKNOWN", 99)
        ));

        CommunityAccessResponse response = service.getAccess(auth, 10);

        assertEquals(Set.of("TEAM_VIEW"), response.capabilities());
    }

    @Test
    void malformedHierarchyCycleFailsClosedForThatScopedCapability() {
        CommunityTeamRole source = role(2, null, true);
        CommunityTeamRole child = role(3, source, true);
        source.setParentRole(child);
        stubCommunity(List.of(source, child), eligibleMembership());

        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(10, 1))
            .thenReturn(List.of(new CommunityTeamRoleAssignment(10, 2, 1)));
        when(directGrants.findByCommunityIdAndUserId(10, 1)).thenReturn(List.of());
        when(roleCapabilities.findByCommunityIdAndRoleIdIn(eq(10), any())).thenReturn(List.of(
            new CommunityTeamRoleCapability(10, 2, "TEAM_MANAGE_STRUCTURE", 99)
        ));

        TeamCapabilityScope scope = scope(service.getAccess(auth, 10), "TEAM_MANAGE_STRUCTURE");

        assertEquals(List.of(2), scope.sourceRoleIds());
        assertTrue(scope.targetRoleIds().isEmpty());
    }

    @Test
    void explicitTeamTargetCheckAllowsDescendantButRejectsSelfAndAncestor() {
        CommunityTeamRole root = role(1, null, true);
        CommunityTeamRole source = role(2, root, true);
        CommunityTeamRole child = role(3, source, true);
        stubCommunity(List.of(root, source, child), eligibleMembership());
        when(assignments.findByCommunityIdAndUserIdOrderByRoleIdAsc(10, 1))
            .thenReturn(List.of(new CommunityTeamRoleAssignment(10, 2, 1)));
        when(directGrants.findByCommunityIdAndUserId(10, 1)).thenReturn(List.of());
        when(roleCapabilities.findByCommunityIdAndRoleIdIn(eq(10), any())).thenReturn(List.of(
            new CommunityTeamRoleCapability(10, 2, "TEAM_MANAGE_STRUCTURE", 99)
        ));

        assertDoesNotThrow(() -> service.requireTeamTarget(
            auth,
            10,
            CommunityCapability.TEAM_MANAGE_STRUCTURE,
            3
        ));

        ResponseStatusException selfError = assertThrows(
            ResponseStatusException.class,
            () -> service.requireTeamTarget(
                auth,
                10,
                CommunityCapability.TEAM_MANAGE_STRUCTURE,
                2
            )
        );
        assertEquals(HttpStatus.FORBIDDEN, selfError.getStatusCode());

        ResponseStatusException ancestorError = assertThrows(
            ResponseStatusException.class,
            () -> service.requireTeamTarget(
                auth,
                10,
                CommunityCapability.TEAM_MANAGE_STRUCTURE,
                1
            )
        );
        assertEquals(HttpStatus.FORBIDDEN, ancestorError.getStatusCode());

        ResponseStatusException unknownError = assertThrows(
            ResponseStatusException.class,
            () -> service.requireTeamTarget(
                auth,
                10,
                CommunityCapability.TEAM_MANAGE_STRUCTURE,
                999
            )
        );
        assertEquals(HttpStatus.NOT_FOUND, unknownError.getStatusCode());
    }

    @Test
    void bannedLeftUnapprovedOrAmbiguousMemberGetsNoCommunityAuthorization() {
        for (UserCommunityStatus membership : java.util.Arrays.asList(
            membership(true, true, true),
            membership(true, false, false),
            membership(false, false, true),
            membership(true, null, true),
            membership(true, false, null)
        )) {
            reset(communities, memberships, roles, assignments, roleCapabilities, directGrants);
            when(communities.findById(10)).thenReturn(Optional.of(community));
            when(memberships.findByUserAndCommunity(user, community)).thenReturn(Optional.of(membership));

            ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.getAccess(auth, 10)
            );
            assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        }
    }

    private void stubCommunity(List<CommunityTeamRole> communityRoles, UserCommunityStatus membership) {
        when(communities.findById(10)).thenReturn(Optional.of(community));
        when(memberships.findByUserAndCommunity(user, community)).thenReturn(Optional.ofNullable(membership));
        when(roles.findByCommunityOrderByNameAsc(community)).thenReturn(communityRoles);
    }

    private UserCommunityStatus eligibleMembership() {
        return membership(true, false, true);
    }

    private UserCommunityStatus membership(Boolean approved, Boolean banned, Boolean present) {
        UserCommunityStatus membership = new UserCommunityStatus();
        membership.setUser(user);
        membership.setCommunity(community);
        membership.setApproved(approved);
        membership.setBanned(banned);
        membership.setIsPresent(present);
        membership.setApprovalRequired(true);
        return membership;
    }

    private User user(int id, String email) {
        User value = new User();
        value.setId(id);
        value.setEmail(email);
        return value;
    }

    private Community community(int id, String name, User owner) {
        Community value = new Community();
        value.setId(id);
        value.setName(name);
        value.setOwnerUser(owner);
        return value;
    }

    private CommunityTeamRole role(int id, CommunityTeamRole parent, boolean active) {
        CommunityTeamRole role = new CommunityTeamRole();
        role.setId(id);
        role.setCommunity(community);
        role.setParentRole(parent);
        role.setName("Role " + id);
        role.setActive(active);
        return role;
    }

    private TeamCapabilityScope scope(CommunityAccessResponse response, String capability) {
        return response.teamScopes().stream()
            .filter(item -> capability.equals(item.capability()))
            .findFirst()
            .orElseThrow();
    }
}
