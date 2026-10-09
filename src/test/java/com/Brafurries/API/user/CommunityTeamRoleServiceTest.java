package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.community.CommunityTeamRole;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityRepository;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleAssignmentRepository;
import com.Brafurries.API.repository.community.CommunityTeamDemandRoleRepository;
import com.Brafurries.API.user.dto.CommunityTeamRoleDtos.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class CommunityTeamRoleServiceTest {
    @Mock CommunityDiscordRepository communities;
    @Mock CommunityRepository communityRepository;
    @Mock CommunityTeamRoleRepository roles;
    @Mock CommunityTeamDemandRoleRepository demandRoles;
    @Mock CommunityTeamRoleAssignmentRepository assignments;
    @Mock CommunityAuthorizationService communityAuthorization;
    @Mock CommunityAuditLogRepository auditRepository;
    @Mock Authentication auth;
    private CommunityTeamRoleService service;
    private Community community;

    @BeforeEach void setUp() {
        community = community(1);
        service = new CommunityTeamRoleService(
            communities,
            communityRepository,
            roles,
            demandRoles,
            assignments,
            communityAuthorization,
            auditRepository
        );
        CommunityDiscord discord = new CommunityDiscord(); discord.setCommunity(community);
        lenient().when(communities.findByGuildId(10L)).thenReturn(Optional.of(discord));
        lenient().when(communityRepository.findByIdForTeamStructureUpdate(1)).thenReturn(Optional.of(community));
        CommunityAuthorizationService.CommunityAccessContext actor = accessContext(community);
        lenient().when(communityAuthorization.requireCapability(any(), anyInt(), any())).thenReturn(actor);
        lenient().when(communityAuthorization.requireTeamCreateParent(any(), anyInt(), any(), nullable(Integer.class))).thenReturn(actor);
        lenient().when(communityAuthorization.requireTeamMove(any(), anyInt(), any(), anyInt(), nullable(Integer.class))).thenReturn(actor);
        lenient().when(communityAuthorization.requireTeamTarget(any(), anyInt(), any(), anyInt())).thenReturn(actor);
        lenient().when(roles.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(roles.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(demandRoles.countByCommunityIdAndRoleId(anyInt(), anyInt())).thenReturn(0L);
        lenient().when(assignments.countByCommunityIdAndRoleId(anyInt(), anyInt())).thenReturn(0L);
    }

    @Test void createsRootRoleForAuthorizedCommunity() {
        RoleResponse response = service.create(auth, "10", new CreateRoleRequest(" Dono ", " ", null));
        ArgumentCaptor<CommunityTeamRole> saved = ArgumentCaptor.forClass(CommunityTeamRole.class);
        verify(roles).save(saved.capture());
        assertEquals(community, saved.getValue().getCommunity());
        assertEquals("Dono", saved.getValue().getName());
        assertNull(saved.getValue().getParentRole());
        verify(communityAuthorization).requireTeamCreateParent(
            auth, 1, CommunityCapability.TEAM_MANAGE_STRUCTURE, null
        );
    }

    @Test void serializesTeamStructureMutationBeforeAuthorization() {
        service.create(auth, "10", new CreateRoleRequest("Dono", null, null));

        var order = inOrder(communityRepository, communityAuthorization);
        order.verify(communityRepository).findByIdForTeamStructureUpdate(1);
        order.verify(communityAuthorization).requireTeamCreateParent(
            auth, 1, CommunityCapability.TEAM_MANAGE_STRUCTURE, null
        );
    }

    @Test void createsRoleWithParentFromSameCommunity() {
        CommunityTeamRole parent = role(2, community, null);
        when(roles.findByIdAndCommunity(2, community)).thenReturn(Optional.of(parent));
        service.create(auth, "10", new CreateRoleRequest("Moderador", null, 2));
        ArgumentCaptor<CommunityTeamRole> saved = ArgumentCaptor.forClass(CommunityTeamRole.class);
        verify(roles).save(saved.capture());
        assertEquals(parent, saved.getValue().getParentRole());
    }

    @Test void rejectsParentFromAnotherCommunity() {
        when(roles.findByIdAndCommunity(2, community)).thenReturn(Optional.empty());
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> service.create(auth, "10", new CreateRoleRequest("Moderador", null, 2)));
        assertEquals(404, exception.getStatusCode().value());
    }

    @Test void rejectsSelfParentAndIndirectCycle() {
        CommunityTeamRole owner = role(1, community, null);
        CommunityTeamRole coordinator = role(2, community, owner);
        when(roles.findByIdAndCommunity(1, community)).thenReturn(Optional.of(owner));
        when(roles.findByIdAndCommunity(1, community)).thenReturn(Optional.of(owner));
        when(roles.findByIdAndCommunity(2, community)).thenReturn(Optional.of(coordinator));
        assertThrows(ResponseStatusException.class, () -> service.update(auth, "10", 1, new UpdateRoleRequest("Dono", null, 1)));
        assertThrows(ResponseStatusException.class, () -> service.update(auth, "10", 1, new UpdateRoleRequest("Dono", null, 2)));
    }

    @Test void rejectsThreeLevelIndirectCycle() {
        CommunityTeamRole owner = role(1, community, null);
        CommunityTeamRole coordinator = role(2, community, owner);
        CommunityTeamRole moderator = role(3, community, coordinator);
        when(roles.findByIdAndCommunity(1, community)).thenReturn(Optional.of(owner));
        when(roles.findByIdAndCommunity(3, community)).thenReturn(Optional.of(moderator));
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> service.update(auth, "10", 1, new UpdateRoleRequest("Dono", null, 3)));
        assertEquals(422, exception.getStatusCode().value());
    }

    @Test void rejectsInactiveParentForNewAssignments() {
        CommunityTeamRole parent = role(2, community, null); parent.setActive(false);
        when(roles.findByIdAndCommunity(2, community)).thenReturn(Optional.of(parent));
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> service.create(auth, "10", new CreateRoleRequest("Moderador", null, 2)));
        assertEquals(422, exception.getStatusCode().value());
    }

    @Test void allowsUpdatingNameWhenRetainingCurrentInactiveParent() {
        CommunityTeamRole inactiveParent = role(2, community, null); inactiveParent.setActive(false);
        CommunityTeamRole child = role(1, community, inactiveParent);
        when(roles.findByIdAndCommunity(1, community)).thenReturn(Optional.of(child));
        when(roles.findByIdAndCommunity(2, community)).thenReturn(Optional.of(inactiveParent));

        RoleResponse updated = service.update(auth, "10", 1,
            new UpdateRoleRequest("Moderador revisado", null, 2));

        assertEquals("Moderador revisado", updated.name());
        assertEquals(2, updated.parentRoleId());
    }

    @Test void rejectsSwitchingToAnotherInactiveParent() {
        CommunityTeamRole currentParent = role(2, community, null);
        CommunityTeamRole inactiveParent = role(3, community, null); inactiveParent.setActive(false);
        CommunityTeamRole child = role(1, community, currentParent);
        when(roles.findByIdAndCommunity(1, community)).thenReturn(Optional.of(child));
        when(roles.findByIdAndCommunity(3, community)).thenReturn(Optional.of(inactiveParent));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> service.update(auth, "10", 1, new UpdateRoleRequest("Moderador", null, 3)));

        assertEquals(422, exception.getStatusCode().value());
    }

    @Test void listsOnlyTheResolvedCommunity() {
        CommunityTeamRole local = role(1, community, null);
        when(roles.findByCommunityOrderByNameAsc(community)).thenReturn(List.of(local));
        assertEquals(List.of(1), service.list(auth, "10").stream().map(RoleResponse::id).toList());
        verify(roles, never()).findByIdAndCommunity(anyInt(), eq(community(2)));
    }

    @Test void editsRoleRemovesParentAndChangesActiveStateWithinCommunity() {
        CommunityTeamRole role = role(1, community, role(2, community, null));
        when(roles.findByIdAndCommunity(1, community)).thenReturn(Optional.of(role));
        RoleResponse edited = service.update(auth, "10", 1, new UpdateRoleRequest("Novo nome", "Descrição", null));
        assertEquals("Novo nome", edited.name());
        assertNull(edited.parentRoleId());
        RoleResponse inactive = service.updateActive(auth, "10", 1, new UpdateRoleActiveRequest(false));
        assertFalse(inactive.active());
    }

    @Test void updateReturnsPostFlushTimestamp() {
        CommunityTeamRole role = role(1, community, null);
        LocalDateTime postFlushTimestamp = LocalDateTime.of(2026, 9, 17, 15, 30);
        when(roles.findByIdAndCommunity(1, community)).thenReturn(Optional.of(role));
        when(roles.saveAndFlush(role)).thenAnswer(invocation -> {
            role.setUpdatedAt(postFlushTimestamp);
            return role;
        });

        RoleResponse updated = service.update(auth, "10", 1,
            new UpdateRoleRequest("Moderador", null, null));

        assertEquals(postFlushTimestamp, updated.updatedAt());
        verify(roles).saveAndFlush(role);
    }

    @Test void updateActiveReturnsPostFlushTimestamp() {
        CommunityTeamRole role = role(1, community, null);
        LocalDateTime postFlushTimestamp = LocalDateTime.of(2026, 9, 17, 15, 31);
        when(roles.findByIdAndCommunity(1, community)).thenReturn(Optional.of(role));
        when(roles.saveAndFlush(role)).thenAnswer(invocation -> {
            role.setUpdatedAt(postFlushTimestamp);
            return role;
        });

        RoleResponse updated = service.updateActive(auth, "10", 1, new UpdateRoleActiveRequest(false));

        assertEquals(postFlushTimestamp, updated.updatedAt());
        verify(roles).saveAndFlush(role);
    }

    @Test void rejectsRoleIdOutsideAuthorizedCommunity() {
        when(roles.findByIdAndCommunity(99, community)).thenReturn(Optional.empty());
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> service.updateActive(auth, "10", 99, new UpdateRoleActiveRequest(false)));
        assertEquals(404, exception.getStatusCode().value());
    }

    @Test void stopsBeforeRepositoryAccessWhenGuildAuthorizationFails() {
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Sem acesso"))
            .when(communityAuthorization).requireCapability(
                auth, 1, CommunityCapability.TEAM_VIEW
            );
        assertThrows(ResponseStatusException.class, () -> service.list(auth, "10"));
        verifyNoInteractions(roles);
    }

    @Test void legacyDeletionStillDetachesDemandLinksDuringMigrationWindow() {
        CommunityTeamRole role = role(1, community, null);
        when(roles.findByIdAndCommunity(1, community)).thenReturn(Optional.of(role));
        when(demandRoles.countByCommunityIdAndRoleId(1, 1)).thenReturn(2L);

        RoleDeletionImpactResponse impact = service.deletionImpact(auth, "10", 1);
        service.delete(auth, "10", 1);

        assertTrue(impact.canDelete());
        assertEquals(2, impact.demandCount());
        verify(demandRoles).deleteByCommunityIdAndRoleId(1, 1);
        verify(roles).delete(role);
    }

    @Test void blocksRoleDeletionWhenAssignmentsExist() {
        CommunityTeamRole role = role(1, community, null);
        when(roles.findByIdAndCommunity(1, community)).thenReturn(Optional.of(role));
        when(assignments.countByCommunityIdAndRoleId(1, 1)).thenReturn(2L);

        RoleDeletionImpactResponse impact = service.deletionImpact(auth, "10", 1);

        assertFalse(impact.canDelete());
        assertEquals(2L, impact.assignmentCount());

        ResponseStatusException exception = assertThrows(
            ResponseStatusException.class,
            () -> service.delete(auth, "10", 1)
        );

        assertEquals(409, exception.getStatusCode().value());
        verify(demandRoles, never()).deleteByCommunityIdAndRoleId(anyInt(), anyInt());
        verify(roles, never()).delete(role);
    }

    @Test void blocksRoleDeletionWhenChildrenStillExistAndRevalidatesOnDelete() {
        CommunityTeamRole role = role(1, community, null);
        when(roles.findByIdAndCommunity(1, community)).thenReturn(Optional.of(role));
        when(roles.countByParentRole(role)).thenReturn(0L, 1L);

        assertTrue(service.deletionImpact(auth, "10", 1).canDelete());
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.delete(auth, "10", 1));

        assertEquals(409, exception.getStatusCode().value());
        verify(roles, never()).delete(role);
    }

    @Test
    void canonicalCreateUsesStructurePlacementAuthorizationAndAuditsMutation() {
        User actorUser = new User();
        actorUser.setId(50);
        CommunityAuthorizationService.CommunityAccessContext actor = new CommunityAuthorizationService.CommunityAccessContext(
            community,
            actorUser,
            false,
            false,
            true,
            Set.of(CommunityCapability.TEAM_MANAGE_STRUCTURE),
            List.of(2),
            Map.of(),
            Set.of(2, 3)
        );
        CommunityTeamRole parent = role(2, community, null);

        when(communityAuthorization.requireTeamCreateParent(
            auth,
            1,
            CommunityCapability.TEAM_MANAGE_STRUCTURE,
            2
        )).thenReturn(actor);
        when(roles.findByIdAndCommunity(2, community)).thenReturn(Optional.of(parent));
        when(roles.save(any())).thenAnswer(invocation -> {
            CommunityTeamRole saved = invocation.getArgument(0);
            saved.setId(3);
            return saved;
        });

        RoleResponse response = service.createCommunity(
            auth,
            1,
            new CreateRoleRequest("Moderação", null, 2)
        );

        assertEquals(3, response.id());
        verify(communityAuthorization).requireTeamCreateParent(
            auth,
            1,
            CommunityCapability.TEAM_MANAGE_STRUCTURE,
            2
        );
        verify(auditRepository).save(argThat(entry ->
            "TEAM_ROLE_CREATED".equals(entry.getAction())
                && "TEAM_ROLE".equals(entry.getTargetType())
                && "3".equals(entry.getTargetId())
        ));
    }

    @Test
    void canonicalDeletionBlocksDemandLinksInsteadOfDetachingThem() {
        User actorUser = new User();
        actorUser.setId(50);
        CommunityAuthorizationService.CommunityAccessContext actor =
            new CommunityAuthorizationService.CommunityAccessContext(
                community,
                actorUser,
                false,
                false,
                true,
                Set.of(CommunityCapability.TEAM_MANAGE_STRUCTURE),
                List.of(2),
                Map.of(),
                Set.of(2, 3)
            );
        CommunityTeamRole role = role(3, community, null);

        when(communityAuthorization.requireTeamTarget(
            auth,
            1,
            CommunityCapability.TEAM_MANAGE_STRUCTURE,
            3
        )).thenReturn(actor);
        when(roles.findByIdAndCommunity(3, community)).thenReturn(Optional.of(role));
        when(demandRoles.countByCommunityIdAndRoleId(1, 3)).thenReturn(1L);

        RoleDeletionImpactResponse impact =
            service.deletionImpactCommunity(auth, 1, 3);

        assertFalse(impact.canDelete());
        assertEquals(1L, impact.demandCount());

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.deleteCommunity(auth, 1, 3)
        );

        assertEquals(409, error.getStatusCode().value());
        verify(demandRoles, never()).deleteByCommunityIdAndRoleId(1, 3);
        verify(roles, never()).delete(role);
    }

    @Test
    void canonicalUpdateDelegatesSameSourceMoveAuthorizationBeforeMutation() {
        doThrow(new ResponseStatusException(
            org.springframework.http.HttpStatus.FORBIDDEN,
            "cross-scope"
        )).when(communityAuthorization).requireTeamMove(
            auth,
            1,
            CommunityCapability.TEAM_MANAGE_STRUCTURE,
            3,
            6
        );

        assertThrows(
            ResponseStatusException.class,
            () -> service.updateCommunity(
                auth,
                1,
                3,
                new UpdateRoleRequest("Cargo", null, 6)
            )
        );

        verifyNoInteractions(roles, auditRepository);
    }

    @Test void translatesConcurrentChildForeignKeyViolationToConflict() {
        CommunityTeamRole role = role(1, community, null);
        when(roles.findByIdAndCommunity(1, community)).thenReturn(Optional.of(role));
        doThrow(new DataIntegrityViolationException("child FK")).when(roles).flush();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.delete(auth, "10", 1));

        assertEquals(409, exception.getStatusCode().value());
    }

    private static CommunityAuthorizationService.CommunityAccessContext accessContext(Community community) {
        User actor = new User(); actor.setId(99);
        return new CommunityAuthorizationService.CommunityAccessContext(
            community, actor, true, true, true,
            Set.of(CommunityCapability.values()), List.of(), Map.of(), Set.of()
        );
    }
    private static Community community(int id) { Community community = new Community(); community.setId(id); return community; }
    private static CommunityTeamRole role(int id, Community community, CommunityTeamRole parent) {
        CommunityTeamRole role = new CommunityTeamRole(); role.setId(id); role.setCommunity(community); role.setParentRole(parent); role.setName("Cargo " + id); role.setActive(true); return role;
    }
}
