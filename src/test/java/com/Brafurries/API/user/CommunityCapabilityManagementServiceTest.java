package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.CommunityCapabilityManagementDtos.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityTeamRole;
import com.Brafurries.API.entity.community.CommunityTeamRoleCapability;
import com.Brafurries.API.entity.community.CommunityTeamRoleCapabilityId;
import com.Brafurries.API.entity.community.CommunityUserCapabilityGrant;
import com.Brafurries.API.entity.community.CommunityUserCapabilityGrantId;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleCapabilityRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleRepository;
import com.Brafurries.API.repository.community.CommunityUserCapabilityGrantRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommunityCapabilityManagementServiceTest {
    @Mock CommunityAuthorizationService authorization;
    @Mock UserCommunityStatusRepository memberships;
    @Mock CommunityTeamRoleRepository roles;
    @Mock CommunityUserCapabilityGrantRepository directGrants;
    @Mock CommunityTeamRoleCapabilityRepository roleCapabilities;
    @Mock CommunityAuditLogRepository audit;
    @Mock Authentication auth;

    private CommunityCapabilityManagementService service;
    private Community community;
    private User owner;
    private User admin;
    private User member;

    @BeforeEach
    void setUp() {
        service = new CommunityCapabilityManagementService(
            authorization,
            memberships,
            roles,
            directGrants,
            roleCapabilities,
            audit
        );
        owner = user(1, "owner@example.com");
        admin = user(2, "admin@example.com");
        member = user(3, "member@example.com");
        community = new Community();
        community.setId(10);
        community.setName("BraFurries");
        community.setOwnerUser(owner);
    }

    @Test
    void ownerCanGrantCommunityAdminToEligibleMember() {
        when(authorization.requireOwnerForCommunityAdminManagement(auth, 10))
            .thenReturn(context(owner, true, false));
        UserCommunityStatus membership = membership(member, true, false, true);
        when(memberships.findAllForAssignmentByUserIdAndCommunityId(3, 10))
            .thenReturn(List.of(membership));
        when(authorization.isAuthorizationEligibleMembership(membership)).thenReturn(true);

        CommunityUserCapabilityGrantId id =
            new CommunityUserCapabilityGrantId(10, 3, "COMMUNITY_ADMIN");
        when(directGrants.existsById(id)).thenReturn(false);
        when(directGrants.findByCommunityIdAndUserIdOrderByCapabilityAsc(10, 3))
            .thenReturn(List.of(new CommunityUserCapabilityGrant(10, 3, "COMMUNITY_ADMIN", 1)));

        DirectCapabilityGrantsResponse response =
            service.grantDirect(auth, 10, 3, "COMMUNITY_ADMIN");

        assertEquals(Set.of("COMMUNITY_ADMIN"), response.capabilities());
        verify(directGrants).saveAndFlush(any(CommunityUserCapabilityGrant.class));

        ArgumentCaptor<CommunityAuditLog> auditCaptor = ArgumentCaptor.forClass(CommunityAuditLog.class);
        verify(audit).save(auditCaptor.capture());
        assertEquals("COMMUNITY_CAPABILITY_GRANTED", auditCaptor.getValue().getAction());
        assertEquals("USER", auditCaptor.getValue().getTargetType());
        assertEquals("3", auditCaptor.getValue().getTargetId());
        assertEquals("{\"capability\":\"COMMUNITY_ADMIN\"}", auditCaptor.getValue().getMetadata());
    }

    @Test
    void communityAdminCannotGrantOrRevokeCommunityAdmin() {
        ResponseStatusException forbidden = new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner only");
        when(authorization.requireOwnerForCommunityAdminManagement(auth, 10)).thenThrow(forbidden);

        ResponseStatusException grantError = assertThrows(
            ResponseStatusException.class,
            () -> service.grantDirect(auth, 10, 3, "COMMUNITY_ADMIN")
        );
        assertEquals(HttpStatus.FORBIDDEN, grantError.getStatusCode());

        ResponseStatusException revokeError = assertThrows(
            ResponseStatusException.class,
            () -> service.revokeDirect(auth, 10, 3, "COMMUNITY_ADMIN")
        );
        assertEquals(HttpStatus.FORBIDDEN, revokeError.getStatusCode());

        verifyNoInteractions(memberships, directGrants, audit);
    }

    @Test
    void communityAdminCanGrantNormalDirectCapability() {
        when(authorization.resolve(auth, 10)).thenReturn(context(admin, false, true));
        UserCommunityStatus membership = membership(member, true, false, true);
        when(memberships.findAllForAssignmentByUserIdAndCommunityId(3, 10))
            .thenReturn(List.of(membership));
        when(authorization.isAuthorizationEligibleMembership(membership)).thenReturn(true);

        CommunityUserCapabilityGrantId id =
            new CommunityUserCapabilityGrantId(10, 3, "MEMBER_DETAILS_VIEW");
        when(directGrants.existsById(id)).thenReturn(false);
        when(directGrants.findByCommunityIdAndUserIdOrderByCapabilityAsc(10, 3))
            .thenReturn(List.of(new CommunityUserCapabilityGrant(10, 3, "MEMBER_DETAILS_VIEW", 2)));

        DirectCapabilityGrantsResponse response =
            service.grantDirect(auth, 10, 3, "MEMBER_DETAILS_VIEW");

        assertEquals(Set.of("MEMBER_DETAILS_VIEW"), response.capabilities());
        verify(audit).save(argThat(entry ->
            "COMMUNITY_CAPABILITY_GRANTED".equals(entry.getAction())
                && entry.getActorUser().getId().equals(2)
        ));
    }

    @Test
    void directHierarchicalTeamGrantIsRejectedByCanonicalPolicy() {
        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.grantDirect(auth, 10, 3, "TEAM_MANAGE_STRUCTURE")
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verifyNoInteractions(authorization, memberships, directGrants, audit);
    }

    @Test
    void currentOwnerCannotReceiveLatentDirectGrant() {
        when(authorization.resolve(auth, 10)).thenReturn(context(owner, true, false));
        UserCommunityStatus ownerMembership = membership(owner, true, false, true);
        when(memberships.findAllForAssignmentByUserIdAndCommunityId(1, 10))
            .thenReturn(List.of(ownerMembership));
        when(authorization.isAuthorizationEligibleMembership(ownerMembership)).thenReturn(true);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.grantDirect(auth, 10, 1, "MEMBERS_VIEW")
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        verify(directGrants, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void inactiveMemberCannotReceiveNewGrantButExistingGrantCanBeRevoked() {
        when(authorization.resolve(auth, 10)).thenReturn(context(admin, false, true));
        UserCommunityStatus left = membership(member, true, false, false);
        when(memberships.findAllForAssignmentByUserIdAndCommunityId(3, 10))
            .thenReturn(List.of(left));
        when(authorization.isAuthorizationEligibleMembership(left)).thenReturn(false);

        ResponseStatusException grantError = assertThrows(
            ResponseStatusException.class,
            () -> service.grantDirect(auth, 10, 3, "MEMBERS_VIEW")
        );
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, grantError.getStatusCode());

        CommunityUserCapabilityGrantId id =
            new CommunityUserCapabilityGrantId(10, 3, "MEMBERS_VIEW");
        when(directGrants.existsById(id)).thenReturn(true);
        when(directGrants.findByCommunityIdAndUserIdOrderByCapabilityAsc(10, 3))
            .thenReturn(List.of());

        DirectCapabilityGrantsResponse response =
            service.revokeDirect(auth, 10, 3, "MEMBERS_VIEW");

        assertTrue(response.capabilities().isEmpty());
        verify(directGrants).deleteById(id);
        verify(audit).save(argThat(entry ->
            "COMMUNITY_CAPABILITY_REVOKED".equals(entry.getAction())
        ));
    }

    @Test
    void ambiguousMembershipFailsClosedBeforeDirectMutation() {
        when(authorization.resolve(auth, 10)).thenReturn(context(admin, false, true));
        when(memberships.findAllForAssignmentByUserIdAndCommunityId(3, 10))
            .thenReturn(List.of(
                membership(member, true, false, true),
                membership(member, true, false, true)
            ));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.grantDirect(auth, 10, 3, "MEMBERS_VIEW")
        );

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verifyNoInteractions(directGrants, audit);
    }

    @Test
    void idempotentDirectPutDoesNotRewriteGrantorOrDuplicateAudit() {
        when(authorization.resolve(auth, 10)).thenReturn(context(admin, false, true));
        UserCommunityStatus membership = membership(member, true, false, true);
        when(memberships.findAllForAssignmentByUserIdAndCommunityId(3, 10))
            .thenReturn(List.of(membership));
        when(authorization.isAuthorizationEligibleMembership(membership)).thenReturn(true);

        CommunityUserCapabilityGrantId id =
            new CommunityUserCapabilityGrantId(10, 3, "MEMBERS_VIEW");
        when(directGrants.existsById(id)).thenReturn(true);
        when(directGrants.findByCommunityIdAndUserIdOrderByCapabilityAsc(10, 3))
            .thenReturn(List.of(new CommunityUserCapabilityGrant(10, 3, "MEMBERS_VIEW", 1)));

        service.grantDirect(auth, 10, 3, "MEMBERS_VIEW");

        verify(directGrants, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    void roleCannotReceiveCommunityAdminOrMemberNotesCapability() {
        for (String code : List.of("COMMUNITY_ADMIN", "MEMBER_NOTES_VIEW", "MEMBER_NOTES_MANAGE")) {
            ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.grantRole(auth, 10, 5, code)
            );
            assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatusCode());
        }
        verifyNoInteractions(authorization, roles, roleCapabilities, audit);
    }

    @Test
    void communityAdminCanGrantHierarchicalCapabilityToRole() {
        when(authorization.resolve(auth, 10)).thenReturn(context(admin, false, true));
        CommunityTeamRole role = role(5, false);
        when(roles.findForAssignmentByIdAndCommunity(5, community)).thenReturn(java.util.Optional.of(role));

        CommunityTeamRoleCapabilityId id =
            new CommunityTeamRoleCapabilityId(10, 5, "TEAM_MANAGE_STRUCTURE");
        when(roleCapabilities.existsById(id)).thenReturn(false);
        when(roleCapabilities.findByCommunityIdAndRoleIdOrderByCapabilityAsc(10, 5))
            .thenReturn(List.of(
                new CommunityTeamRoleCapability(10, 5, "TEAM_MANAGE_STRUCTURE", 2)
            ));

        RoleCapabilityGrantsResponse response =
            service.grantRole(auth, 10, 5, "TEAM_MANAGE_STRUCTURE");

        assertEquals(Set.of("TEAM_MANAGE_STRUCTURE"), response.capabilities());
        verify(roleCapabilities).saveAndFlush(any(CommunityTeamRoleCapability.class));
        verify(audit).save(argThat(entry ->
            "TEAM_ROLE_CAPABILITY_GRANTED".equals(entry.getAction())
                && "TEAM_ROLE".equals(entry.getTargetType())
                && "5".equals(entry.getTargetId())
        ));
    }

    @Test
    void plainTeamManagerCannotConfigureCapabilities() {
        when(authorization.resolve(auth, 10)).thenReturn(context(member, false, false));

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.grantRole(auth, 10, 5, "TEAM_MANAGE_STRUCTURE")
        );

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verifyNoInteractions(roles, roleCapabilities, audit);
    }

    @Test
    void catalogKeepsPrivilegePlacementRulesInApi() {
        when(authorization.resolve(auth, 10)).thenReturn(context(admin, false, true));

        CapabilityCatalogResponse response = service.catalog(auth, 10);

        CapabilityDefinition adminDefinition = definition(response, "COMMUNITY_ADMIN");
        assertTrue(adminDefinition.directGrantAllowed());
        assertFalse(adminDefinition.roleGrantAllowed());

        CapabilityDefinition teamView = definition(response, "TEAM_VIEW");
        assertFalse(teamView.directGrantAllowed());
        assertFalse(teamView.roleGrantAllowed());
        assertFalse(teamView.hierarchicalTeamScope());

        CapabilityDefinition structure = definition(response, "TEAM_MANAGE_STRUCTURE");
        assertFalse(structure.directGrantAllowed());
        assertTrue(structure.roleGrantAllowed());
        assertTrue(structure.hierarchicalTeamScope());

        CapabilityDefinition notes = definition(response, "MEMBER_NOTES_MANAGE");
        assertTrue(notes.directGrantAllowed());
        assertFalse(notes.roleGrantAllowed());
    }

    private CommunityAuthorizationService.CommunityAccessContext context(
        User actor,
        boolean isOwner,
        boolean isAdmin
    ) {
        return new CommunityAuthorizationService.CommunityAccessContext(
            community,
            actor,
            isOwner,
            isAdmin,
            true,
            Set.of(),
            List.of(),
            Map.of(),
            Set.of()
        );
    }

    private UserCommunityStatus membership(
        User target,
        boolean approved,
        boolean banned,
        boolean present
    ) {
        UserCommunityStatus membership = new UserCommunityStatus();
        membership.setUser(target);
        membership.setCommunity(community);
        membership.setApproved(approved);
        membership.setBanned(banned);
        membership.setIsPresent(present);
        return membership;
    }

    private User user(int id, String email) {
        User value = new User();
        value.setId(id);
        value.setEmail(email);
        return value;
    }

    private CommunityTeamRole role(int id, boolean active) {
        CommunityTeamRole role = new CommunityTeamRole();
        role.setId(id);
        role.setCommunity(community);
        role.setName("Role " + id);
        role.setActive(active);
        return role;
    }

    private CapabilityDefinition definition(CapabilityCatalogResponse response, String code) {
        return response.capabilities().stream()
            .filter(item -> code.equals(item.code()))
            .findFirst()
            .orElseThrow();
    }
}
