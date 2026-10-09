package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.Brafurries.API.entity.community.*;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.community.*;
import com.Brafurries.API.user.dto.CommunityTeamDemandDtos.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CommunityTeamDemandServiceTest {
    @Mock CommunityDiscordRepository communities;
    @Mock CommunityTeamDemandRepository demands;
    @Mock CommunityTeamDemandRoleRepository demandRoles;
    @Mock CommunityTeamRoleRepository roles;
    @Mock CommunityAuthorizationService communityAuthorization;
    @Mock CommunityAuditLogRepository auditRepository;
    @Mock Authentication auth;
    private CommunityTeamDemandService service;
    private Community community;

    @BeforeEach void setUp() {
        community = community(1);
        service = new CommunityTeamDemandService(
            communities,
            demands,
            demandRoles,
            roles,
            communityAuthorization,
            auditRepository
        );
        CommunityDiscord discord = new CommunityDiscord(); discord.setCommunity(community);
        lenient().when(communities.findByGuildId(10L)).thenReturn(Optional.of(discord));
        CommunityAuthorizationService.CommunityAccessContext actor = accessContext(community);
        lenient().when(communityAuthorization.requireCapability(any(), anyInt(), any())).thenReturn(actor);
        lenient().when(communityAuthorization.requireTeamRoleSet(any(), anyInt(), any(), anySet())).thenReturn(actor);
        lenient().when(demands.saveAndFlush(any())).thenAnswer(invocation -> {
            CommunityTeamDemand demand = invocation.getArgument(0);
            if (demand.getId() == null) demand.setId(1);
            return demand;
        });
        lenient().when(demandRoles.findByCommunityIdAndDemandIdOrderByRoleIdAsc(anyInt(), anyInt())).thenReturn(List.of());
    }

    @Test void createsDemandWithoutRoles() {
        DemandResponse response = service.create(auth, "10", new SaveDemandRequest(" Portaria ", " ", List.of()));
        assertEquals("Portaria", response.name());
        assertNull(response.description());
        verify(demandRoles).saveAll(List.of());
    }

    @Test void createsDemandWithOneOrSeveralRoles() {
        CommunityTeamRole first = role(2, community, true); CommunityTeamRole second = role(3, community, true);
        when(roles.findByIdAndCommunity(2, community)).thenReturn(Optional.of(first));
        when(roles.findByIdAndCommunity(3, community)).thenReturn(Optional.of(second));
        DemandResponse response = service.create(auth, "10", new SaveDemandRequest("Moderação", null, List.of(2, 3)));

        @SuppressWarnings("unchecked") ArgumentCaptor<List<CommunityTeamDemandRole>> links = ArgumentCaptor.forClass(List.class);
        verify(demandRoles).saveAll(links.capture());
        assertNotNull(response.id());
        assertEquals(List.of(response.id(), response.id()), links.getValue().stream().map(CommunityTeamDemandRole::getDemandId).toList());
        InOrder order = inOrder(demands, demandRoles);
        order.verify(demands).saveAndFlush(any(CommunityTeamDemand.class));
        order.verify(demandRoles).saveAll(any());
    }

    @Test void rejectsMissingOrCrossTenantRoles() {
        when(roles.findByIdAndCommunity(9, community)).thenReturn(Optional.empty());
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> service.create(auth, "10", new SaveDemandRequest("Portaria", null, List.of(9))));
        assertEquals(404, exception.getStatusCode().value());
    }

    @Test void rejectsNewInactiveRoleButPreservesExistingInactiveRole() {
        CommunityTeamRole inactive = role(2, community, false);
        when(roles.findByIdAndCommunity(2, community)).thenReturn(Optional.of(inactive));
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
            () -> service.create(auth, "10", new SaveDemandRequest("Portaria", null, List.of(2))));
        assertEquals(422, exception.getStatusCode().value());

        CommunityTeamDemand demand = demand(1, community);
        when(demands.findByIdAndCommunity(1, community)).thenReturn(Optional.of(demand));
        when(demandRoles.findByCommunityIdAndDemandIdOrderByRoleIdAsc(1, 1))
            .thenReturn(List.of(new CommunityTeamDemandRole(1, 1, 2)));
        DemandResponse response = service.update(auth, "10", 1, new SaveDemandRequest("Portaria", null, List.of(2)));
        assertEquals(List.of(2), response.roleIds());
    }

    @Test void updateReplacesLinksAndDeleteKeepsRoles() {
        CommunityTeamDemand demand = demand(1, community);
        CommunityTeamRole role = role(2, community, true);
        when(demands.findByIdAndCommunity(1, community)).thenReturn(Optional.of(demand));
        when(roles.findByIdAndCommunity(2, community)).thenReturn(Optional.of(role));
        service.update(auth, "10", 1, new SaveDemandRequest("Recepção", null, List.of(2)));
        verify(demandRoles).deleteByCommunityIdAndDemandId(1, 1);
        service.delete(auth, "10", 1);
        verify(demands).delete(demand);
    }

    @Test void updatesTimestampWhenOnlyRoleIdsChange() {
        CommunityTeamDemand demand = demand(1, community);
        LocalDateTime oldUpdatedAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        demand.setUpdatedAt(oldUpdatedAt);
        CommunityTeamRole role = role(2, community, true);
        when(demands.findByIdAndCommunity(1, community)).thenReturn(Optional.of(demand));
        when(roles.findByIdAndCommunity(2, community)).thenReturn(Optional.of(role));

        DemandResponse response = service.update(auth, "10", 1, new SaveDemandRequest("Demanda", null, List.of(2)));

        assertEquals("Demanda", demand.getName());
        assertEquals("Demanda", response.name());
        assertNull(response.description());
        assertTrue(demand.getUpdatedAt().isAfter(oldUpdatedAt));
        assertEquals(demand.getUpdatedAt(), response.updatedAt());
        verify(demands).saveAndFlush(demand);
        InOrder order = inOrder(demandRoles, demands);
        order.verify(demandRoles).saveAll(any());
        order.verify(demands).saveAndFlush(demand);
    }

    @Test
    void canonicalCreateRequiresOneAuthorizedDemandScopeAndAudits() {
        User actorUser = new User();
        actorUser.setId(50);
        CommunityAuthorizationService.CommunityAccessContext actor =
            new CommunityAuthorizationService.CommunityAccessContext(
                community,
                actorUser,
                false,
                false,
                true,
                Set.of(CommunityCapability.TEAM_MANAGE_DEMANDS),
                List.of(2),
                Map.of(),
                Set.of(2, 3)
            );
        CommunityTeamRole role = role(3, community, true);

        when(communityAuthorization.requireTeamRoleSet(
            auth,
            1,
            CommunityCapability.TEAM_MANAGE_DEMANDS,
            Set.of(3)
        )).thenReturn(actor);
        when(roles.findByIdAndCommunity(3, community)).thenReturn(Optional.of(role));

        DemandResponse response = service.createCommunity(
            auth,
            1,
            new SaveDemandRequest("Moderação", null, List.of(3))
        );

        assertEquals("Moderação", response.name());
        verify(auditRepository).save(argThat(entry ->
            "TEAM_DEMAND_CREATED".equals(entry.getAction())
                && "TEAM_DEMAND".equals(entry.getTargetType())
        ));
    }

    @Test
    void canonicalUpdateChecksExistingAndRequestedRolesTogether() {
        User actorUser = new User();
        actorUser.setId(50);
        CommunityAuthorizationService.CommunityAccessContext actor =
            new CommunityAuthorizationService.CommunityAccessContext(
                community,
                actorUser,
                false,
                false,
                true,
                Set.of(CommunityCapability.TEAM_MANAGE_DEMANDS),
                List.of(2),
                Map.of(),
                Set.of(2, 3, 6)
            );
        CommunityTeamDemand demand = demand(1, community);
        CommunityTeamRole requestedRole = role(6, community, true);

        when(communityAuthorization.requireCapability(
            auth,
            1,
            CommunityCapability.TEAM_MANAGE_DEMANDS
        )).thenReturn(actor);
        when(demands.findByIdAndCommunity(1, community)).thenReturn(Optional.of(demand));
        when(demandRoles.findByCommunityIdAndDemandIdOrderByRoleIdAsc(1, 1))
            .thenReturn(
                List.of(new CommunityTeamDemandRole(1, 1, 3)),
                List.of(new CommunityTeamDemandRole(1, 1, 6))
            );
        when(communityAuthorization.requireTeamRoleSet(
            auth,
            1,
            CommunityCapability.TEAM_MANAGE_DEMANDS,
            Set.of(3)
        )).thenReturn(actor);
        when(communityAuthorization.requireTeamRoleSet(
            auth,
            1,
            CommunityCapability.TEAM_MANAGE_DEMANDS,
            Set.of(3, 6)
        )).thenReturn(actor);
        when(roles.findByIdAndCommunity(6, community)).thenReturn(Optional.of(requestedRole));

        service.updateCommunity(
            auth,
            1,
            1,
            new SaveDemandRequest("Nova", null, List.of(6))
        );

        verify(communityAuthorization).requireTeamRoleSet(
            auth,
            1,
            CommunityCapability.TEAM_MANAGE_DEMANDS,
            Set.of(3, 6)
        );
        verify(auditRepository).save(any(CommunityAuditLog.class));
    }

    @Test
    void scopedUserCannotModifyGlobalDemandWithoutResponsibleRoles() {
        User actorUser = new User();
        actorUser.setId(50);
        CommunityAuthorizationService.CommunityAccessContext actor =
            new CommunityAuthorizationService.CommunityAccessContext(
                community,
                actorUser,
                false,
                false,
                true,
                Set.of(CommunityCapability.TEAM_MANAGE_DEMANDS),
                List.of(2),
                Map.of(),
                Set.of(2, 3)
            );
        CommunityTeamDemand demand = demand(1, community);

        when(communityAuthorization.requireCapability(
            auth,
            1,
            CommunityCapability.TEAM_MANAGE_DEMANDS
        )).thenReturn(actor);
        when(demands.findByIdAndCommunity(1, community)).thenReturn(Optional.of(demand));
        when(demandRoles.findByCommunityIdAndDemandIdOrderByRoleIdAsc(1, 1))
            .thenReturn(List.of());
        doThrow(new ResponseStatusException(
            org.springframework.http.HttpStatus.FORBIDDEN,
            "global demand"
        )).when(communityAuthorization).requireTeamRoleSet(
            auth,
            1,
            CommunityCapability.TEAM_MANAGE_DEMANDS,
            Set.of()
        );

        assertThrows(
            ResponseStatusException.class,
            () -> service.updateCommunity(
                auth,
                1,
                1,
                new SaveDemandRequest("Nova", null, List.of(3))
            )
        );

        verifyNoInteractions(auditRepository);
    }

    @Test void listsOnlyAuthorizedCommunityAndRejectsCrossTenantDemand() {
        CommunityTeamDemand local = demand(1, community);
        when(demands.findByCommunityOrderByNameAsc(community)).thenReturn(List.of(local));
        assertEquals(List.of(1), service.list(auth, "10").stream().map(DemandResponse::id).toList());
        when(demands.findByIdAndCommunity(99, community)).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> service.delete(auth, "10", 99));
    }

    private static CommunityAuthorizationService.CommunityAccessContext accessContext(Community community) {
        User actor = new User(); actor.setId(99);
        return new CommunityAuthorizationService.CommunityAccessContext(
            community, actor, true, true, true,
            Set.of(CommunityCapability.values()), List.of(), Map.of(), Set.of()
        );
    }
    private static Community community(int id) { Community community = new Community(); community.setId(id); return community; }
    private static CommunityTeamDemand demand(int id, Community community) { CommunityTeamDemand demand = new CommunityTeamDemand(); demand.setId(id); demand.setCommunity(community); demand.setName("Demanda"); return demand; }
    private static CommunityTeamRole role(int id, Community community, boolean active) { CommunityTeamRole role = new CommunityTeamRole(); role.setId(id); role.setCommunity(community); role.setActive(active); return role; }
}
