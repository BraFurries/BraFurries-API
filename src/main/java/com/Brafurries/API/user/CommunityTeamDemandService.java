package com.Brafurries.API.user;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.community.CommunityTeamDemand;
import com.Brafurries.API.entity.community.CommunityTeamDemandRole;
import com.Brafurries.API.entity.community.CommunityTeamRole;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityTeamDemandRepository;
import com.Brafurries.API.repository.community.CommunityTeamDemandRoleRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleRepository;
import com.Brafurries.API.user.dto.CommunityTeamDemandDtos.*;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CommunityTeamDemandService {
    private final CommunityDiscordRepository communityDiscordRepository;
    private final CommunityTeamDemandRepository demandRepository;
    private final CommunityTeamDemandRoleRepository demandRoleRepository;
    private final CommunityTeamRoleRepository roleRepository;
    private final CommunityAuthorizationService communityAuthorization;
    private final CommunityAuditLogRepository auditRepository;

    public CommunityTeamDemandService(
        CommunityDiscordRepository communityDiscordRepository,
        CommunityTeamDemandRepository demandRepository,
        CommunityTeamDemandRoleRepository demandRoleRepository,
        CommunityTeamRoleRepository roleRepository,
        CommunityAuthorizationService communityAuthorization,
        CommunityAuditLogRepository auditRepository
    ) {
        this.communityDiscordRepository = communityDiscordRepository;
        this.demandRepository = demandRepository;
        this.demandRoleRepository = demandRoleRepository;
        this.roleRepository = roleRepository;
        this.communityAuthorization = communityAuthorization;
        this.auditRepository = auditRepository;
    }

    // Legacy Discord-scoped entry points remain during migration.
    @Transactional(readOnly = true)
    public List<DemandResponse> list(Authentication auth, String guildId) {
        return listCommunity(auth, communityIdForGuild(guildId));
    }

    @Transactional
    public DemandResponse create(
        Authentication auth,
        String guildId,
        SaveDemandRequest request
    ) {
        return createCommunity(auth, communityIdForGuild(guildId), request);
    }

    @Transactional
    public DemandResponse update(
        Authentication auth,
        String guildId,
        Integer demandId,
        SaveDemandRequest request
    ) {
        return updateCommunity(auth, communityIdForGuild(guildId), demandId, request);
    }

    @Transactional
    public void delete(Authentication auth, String guildId, Integer demandId) {
        deleteCommunity(auth, communityIdForGuild(guildId), demandId);
    }

    // Canonical Community-scoped entry points.
    @Transactional(readOnly = true)
    public List<DemandResponse> listCommunity(Authentication auth, Integer communityId) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireCapability(
                auth,
                communityId,
                CommunityCapability.TEAM_VIEW
            );
        return listByCommunity(actor.community());
    }

    @Transactional
    public DemandResponse createCommunity(
        Authentication auth,
        Integer communityId,
        SaveDemandRequest request
    ) {
        Set<Integer> requested = requestedRoleIds(request.roleIds());
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireTeamRoleSet(
                auth,
                communityId,
                CommunityCapability.TEAM_MANAGE_DEMANDS,
                requested
            );

        DemandResponse response = createDemand(actor.community(), request);
        audit(
            actor,
            "TEAM_DEMAND_CREATED",
            response.id(),
            roleIdsMetadata(requested)
        );
        return response;
    }

    @Transactional
    public DemandResponse updateCommunity(
        Authentication auth,
        Integer communityId,
        Integer demandId,
        SaveDemandRequest request
    ) {
        CommunityAuthorizationService.CommunityAccessContext base =
            communityAuthorization.requireCapability(
                auth,
                communityId,
                CommunityCapability.TEAM_MANAGE_DEMANDS
            );

        CommunityTeamDemand demand = requireDemand(base.community(), demandId);
        Set<Integer> existing = existingRoleIds(base.community(), demand);
        Set<Integer> requested = requestedRoleIds(request.roleIds());

        // Existing global demands can only be changed by unrestricted access.
        communityAuthorization.requireTeamRoleSet(
            auth,
            communityId,
            CommunityCapability.TEAM_MANAGE_DEMANDS,
            existing
        );

        LinkedHashSet<Integer> combined = new LinkedHashSet<>(existing);
        combined.addAll(requested);
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireTeamRoleSet(
                auth,
                communityId,
                CommunityCapability.TEAM_MANAGE_DEMANDS,
                combined
            );

        DemandResponse response = updateExistingDemand(
            actor.community(),
            demand,
            request,
            requested
        );
        audit(
            actor,
            "TEAM_DEMAND_UPDATED",
            demandId,
            roleIdsMetadata(requested)
        );
        return response;
    }

    @Transactional
    public void deleteCommunity(
        Authentication auth,
        Integer communityId,
        Integer demandId
    ) {
        CommunityAuthorizationService.CommunityAccessContext base =
            communityAuthorization.requireCapability(
                auth,
                communityId,
                CommunityCapability.TEAM_MANAGE_DEMANDS
            );

        CommunityTeamDemand demand = requireDemand(base.community(), demandId);
        Set<Integer> existing = existingRoleIds(base.community(), demand);

        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireTeamRoleSet(
                auth,
                communityId,
                CommunityCapability.TEAM_MANAGE_DEMANDS,
                existing
            );

        deleteDemand(actor.community(), demandId);
        audit(actor, "TEAM_DEMAND_DELETED", demandId, roleIdsMetadata(existing));
    }

    private List<DemandResponse> listByCommunity(Community community) {
        return demandRepository.findByCommunityOrderByNameAsc(community).stream()
            .map(demand -> toResponse(community, demand))
            .toList();
    }

    private DemandResponse createDemand(
        Community community,
        SaveDemandRequest request
    ) {
        Set<Integer> requested = requestedRoleIds(request.roleIds());
        CommunityTeamDemand demand = new CommunityTeamDemand();
        demand.setCommunity(community);
        demand.setName(normalizeName(request.name()));
        demand.setDescription(normalizeDescription(request.description()));
        demand = demandRepository.saveAndFlush(demand);
        replaceRoles(community, demand, requested);
        return toResponse(community, demand);
    }

    private DemandResponse updateDemand(
        Community community,
        Integer demandId,
        SaveDemandRequest request
    ) {
        CommunityTeamDemand demand = requireDemand(community, demandId);
        return updateExistingDemand(
            community,
            demand,
            request,
            requestedRoleIds(request.roleIds())
        );
    }

    private DemandResponse updateExistingDemand(
        Community community,
        CommunityTeamDemand demand,
        SaveDemandRequest request,
        Set<Integer> requested
    ) {
        demand.setName(normalizeName(request.name()));
        demand.setDescription(normalizeDescription(request.description()));
        replaceRoles(community, demand, requested);
        demand.setUpdatedAt(LocalDateTime.now());
        demand = demandRepository.saveAndFlush(demand);
        return toResponse(community, demand);
    }

    private void deleteDemand(Community community, Integer demandId) {
        CommunityTeamDemand demand = requireDemand(community, demandId);
        demandRoleRepository.deleteByCommunityIdAndDemandId(
            community.getId(),
            demand.getId()
        );
        demandRepository.delete(demand);
    }

    private Integer communityIdForGuild(String guildId) {
        final Long parsedGuildId;
        try {
            parsedGuildId = Long.valueOf(guildId);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Servidor Discord inválido");
        }

        CommunityDiscord link = communityDiscordRepository.findByGuildId(parsedGuildId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Comunidade do servidor não foi registrada"
            ));
        return link.getCommunity().getId();
    }

    private CommunityTeamDemand requireDemand(
        Community community,
        Integer demandId
    ) {
        return demandRepository.findByIdAndCommunity(demandId, community)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Demanda não encontrada nesta comunidade"
            ));
    }

    private Set<Integer> requestedRoleIds(List<Integer> requestedRoleIds) {
        LinkedHashSet<Integer> requested = new LinkedHashSet<>(requestedRoleIds);
        if (requested.size() != requestedRoleIds.size()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Cargos responsáveis não podem ser repetidos"
            );
        }
        return requested;
    }

    private Set<Integer> existingRoleIds(
        Community community,
        CommunityTeamDemand demand
    ) {
        return demandRoleRepository
            .findByCommunityIdAndDemandIdOrderByRoleIdAsc(
                community.getId(),
                demand.getId()
            )
            .stream()
            .map(CommunityTeamDemandRole::getRoleId)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void replaceRoles(
        Community community,
        CommunityTeamDemand demand,
        Set<Integer> requested
    ) {
        Set<Integer> existing = existingRoleIds(community, demand);

        for (Integer roleId : requested) {
            CommunityTeamRole role = roleRepository.findByIdAndCommunity(roleId, community)
                .orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Cargo responsável não encontrado nesta comunidade"
                ));

            if (!Boolean.TRUE.equals(role.getActive()) && !existing.contains(roleId)) {
                throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cargo inativo não pode receber novas demandas"
                );
            }
        }

        demandRoleRepository.deleteByCommunityIdAndDemandId(
            community.getId(),
            demand.getId()
        );
        demandRoleRepository.saveAll(
            requested.stream()
                .map(roleId -> new CommunityTeamDemandRole(
                    community.getId(),
                    demand.getId(),
                    roleId
                ))
                .toList()
        );
    }

    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Nome da demanda é obrigatório"
            );
        }
        return normalized;
    }

    private String normalizeDescription(String description) {
        if (description == null) return null;
        String normalized = description.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private DemandResponse toResponse(
        Community community,
        CommunityTeamDemand demand
    ) {
        List<Integer> roleIds = demandRoleRepository
            .findByCommunityIdAndDemandIdOrderByRoleIdAsc(
                community.getId(),
                demand.getId()
            )
            .stream()
            .map(CommunityTeamDemandRole::getRoleId)
            .toList();

        return new DemandResponse(
            demand.getId(),
            demand.getName(),
            demand.getDescription(),
            roleIds,
            demand.getCreatedAt(),
            demand.getUpdatedAt()
        );
    }

    private void audit(
        CommunityAuthorizationService.CommunityAccessContext actor,
        String action,
        Integer demandId,
        String metadata
    ) {
        CommunityAuditLog entry = new CommunityAuditLog();
        entry.setCommunity(actor.community());
        entry.setActorUser(actor.user());
        entry.setAction(action);
        entry.setTargetType("TEAM_DEMAND");
        entry.setTargetId(String.valueOf(demandId));
        entry.setMetadata(metadata);
        auditRepository.save(entry);
    }

    private String roleIdsMetadata(Set<Integer> roleIds) {
        return "{\"roleIds\":[" +
            roleIds.stream().map(String::valueOf).collect(Collectors.joining(",")) +
            "]}";
    }
}
