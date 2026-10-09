package com.Brafurries.API.user;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.community.CommunityTeamRole;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityRepository;
import com.Brafurries.API.repository.community.CommunityTeamDemandRoleRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleAssignmentRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleRepository;
import com.Brafurries.API.user.dto.CommunityTeamRoleDtos.*;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CommunityTeamRoleService {
    private final CommunityDiscordRepository communityDiscordRepository;
    private final CommunityRepository communityRepository;
    private final CommunityTeamRoleRepository roleRepository;
    private final CommunityTeamDemandRoleRepository demandRoleRepository;
    private final CommunityTeamRoleAssignmentRepository assignmentRepository;
    private final CommunityAuthorizationService communityAuthorization;
    private final CommunityAuditLogRepository auditRepository;

    public CommunityTeamRoleService(
        CommunityDiscordRepository communityDiscordRepository,
        CommunityRepository communityRepository,
        CommunityTeamRoleRepository roleRepository,
        CommunityTeamDemandRoleRepository demandRoleRepository,
        CommunityTeamRoleAssignmentRepository assignmentRepository,
        CommunityAuthorizationService communityAuthorization,
        CommunityAuditLogRepository auditRepository
    ) {
        this.communityDiscordRepository = communityDiscordRepository;
        this.communityRepository = communityRepository;
        this.roleRepository = roleRepository;
        this.demandRoleRepository = demandRoleRepository;
        this.assignmentRepository = assignmentRepository;
        this.communityAuthorization = communityAuthorization;
        this.auditRepository = auditRepository;
    }

    // Legacy Discord-scoped entry points remain available during the migration window,
    // but they are authorization aliases only. They must never bypass Community capabilities.
    @Transactional(readOnly = true)
    public List<RoleResponse> list(Authentication auth, String guildId) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireCapability(
                auth,
                communityIdForGuild(guildId),
                CommunityCapability.TEAM_VIEW
            );
        return roleRepository.findByCommunityOrderByNameAsc(actor.community()).stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public RoleResponse create(Authentication auth, String guildId, CreateRoleRequest request) {
        return createCommunity(auth, communityIdForGuild(guildId), request);
    }

    @Transactional
    public RoleResponse update(
        Authentication auth,
        String guildId,
        Integer roleId,
        UpdateRoleRequest request
    ) {
        return updateCommunity(auth, communityIdForGuild(guildId), roleId, request);
    }

    @Transactional
    public RoleResponse updateActive(
        Authentication auth,
        String guildId,
        Integer roleId,
        UpdateRoleActiveRequest request
    ) {
        return updateActiveCommunity(auth, communityIdForGuild(guildId), roleId, request);
    }

    @Transactional(readOnly = true)
    public RoleDeletionImpactResponse deletionImpact(
        Authentication auth,
        String guildId,
        Integer roleId
    ) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireTeamTarget(
                auth,
                communityIdForGuild(guildId),
                CommunityCapability.TEAM_MANAGE_STRUCTURE,
                roleId
            );
        return legacyDeletionImpact(requireRole(actor.community(), roleId));
    }

    @Transactional
    public void delete(Authentication auth, String guildId, Integer roleId) {
        Integer communityId = communityIdForGuild(guildId);
        lockTeamStructure(communityId);
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireTeamTarget(
                auth,
                communityId,
                CommunityCapability.TEAM_MANAGE_STRUCTURE,
                roleId
            );
        CommunityTeamRole role = requireRole(actor.community(), roleId);
        deleteRoleLegacy(role);
        audit(actor, "TEAM_ROLE_DELETED", roleId, "{\"legacyDemandLinksDetached\":true}");
    }

    // Canonical Community-scoped mutations.
    @Transactional
    public RoleResponse createCommunity(
        Authentication auth,
        Integer communityId,
        CreateRoleRequest request
    ) {
        lockTeamStructure(communityId);
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireTeamCreateParent(
                auth,
                communityId,
                CommunityCapability.TEAM_MANAGE_STRUCTURE,
                request.parentRoleId()
            );

        RoleResponse response = createRole(actor.community(), request);
        audit(
            actor,
            "TEAM_ROLE_CREATED",
            response.id(),
            "{\"parentRoleId\":" + nullableNumber(response.parentRoleId()) + "}"
        );
        return response;
    }

    @Transactional
    public RoleResponse updateCommunity(
        Authentication auth,
        Integer communityId,
        Integer roleId,
        UpdateRoleRequest request
    ) {
        lockTeamStructure(communityId);
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireTeamMove(
                auth,
                communityId,
                CommunityCapability.TEAM_MANAGE_STRUCTURE,
                roleId,
                request.parentRoleId()
            );

        RoleResponse response = updateRole(actor.community(), roleId, request);
        audit(
            actor,
            "TEAM_ROLE_UPDATED",
            roleId,
            "{\"parentRoleId\":" + nullableNumber(response.parentRoleId()) + "}"
        );
        return response;
    }

    @Transactional
    public RoleResponse updateActiveCommunity(
        Authentication auth,
        Integer communityId,
        Integer roleId,
        UpdateRoleActiveRequest request
    ) {
        lockTeamStructure(communityId);
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireTeamTarget(
                auth,
                communityId,
                CommunityCapability.TEAM_MANAGE_STRUCTURE,
                roleId
            );

        CommunityTeamRole role = requireRole(actor.community(), roleId);
        role.setActive(request.active());
        RoleResponse response = toResponse(roleRepository.saveAndFlush(role));
        audit(
            actor,
            "TEAM_ROLE_ACTIVE_CHANGED",
            roleId,
            "{\"active\":" + Boolean.TRUE.equals(request.active()) + "}"
        );
        return response;
    }

    @Transactional(readOnly = true)
    public RoleDeletionImpactResponse deletionImpactCommunity(
        Authentication auth,
        Integer communityId,
        Integer roleId
    ) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireTeamTarget(
                auth,
                communityId,
                CommunityCapability.TEAM_MANAGE_STRUCTURE,
                roleId
            );
        return canonicalDeletionImpact(requireRole(actor.community(), roleId));
    }

    @Transactional
    public void deleteCommunity(
        Authentication auth,
        Integer communityId,
        Integer roleId
    ) {
        lockTeamStructure(communityId);
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireTeamTarget(
                auth,
                communityId,
                CommunityCapability.TEAM_MANAGE_STRUCTURE,
                roleId
            );
        CommunityTeamRole role = requireRole(actor.community(), roleId);
        deleteRoleCanonical(role);
        audit(actor, "TEAM_ROLE_DELETED", roleId, null);
    }

    private RoleResponse createRole(Community community, CreateRoleRequest request) {
        CommunityTeamRole role = new CommunityTeamRole();
        role.setCommunity(community);
        role.setName(normalizeName(request.name()));
        role.setDescription(normalizeDescription(request.description()));
        role.setParentRole(resolveParent(community, request.parentRoleId(), null));
        return toResponse(roleRepository.save(role));
    }

    private RoleResponse updateRole(
        Community community,
        Integer roleId,
        UpdateRoleRequest request
    ) {
        CommunityTeamRole role = requireRole(community, roleId);
        CommunityTeamRole parent = resolveParent(community, request.parentRoleId(), role);
        role.setName(normalizeName(request.name()));
        role.setDescription(normalizeDescription(request.description()));
        role.setParentRole(parent);
        return toResponse(roleRepository.saveAndFlush(role));
    }

    private void deleteRoleLegacy(CommunityTeamRole role) {
        RoleDeletionImpactResponse impact = legacyDeletionImpact(role);
        requireNoChildrenOrAssignments(impact);
        demandRoleRepository.deleteByCommunityIdAndRoleId(
            role.getCommunity().getId(),
            role.getId()
        );
        deleteRoleEntity(role);
    }

    private void deleteRoleCanonical(CommunityTeamRole role) {
        RoleDeletionImpactResponse impact = canonicalDeletionImpact(role);
        requireNoChildrenOrAssignments(impact);
        if (impact.demandCount() > 0) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cargo possui demandas vinculadas e não pode ser excluído"
            );
        }
        deleteRoleEntity(role);
    }

    private void requireNoChildrenOrAssignments(RoleDeletionImpactResponse impact) {
        if (impact.childRoleCount() > 0) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cargo possui cargos subordinados e não pode ser excluído"
            );
        }
        if (impact.assignmentCount() > 0) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cargo possui membros atribuídos e não pode ser excluído"
            );
        }
    }

    private void deleteRoleEntity(CommunityTeamRole role) {
        try {
            roleRepository.delete(role);
            roleRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cargo possui vínculos e não pode ser excluído",
                exception
            );
        }
    }

    private RoleDeletionImpactResponse legacyDeletionImpact(CommunityTeamRole role) {
        long childRoleCount = roleRepository.countByParentRole(role);
        long demandCount = demandRoleRepository.countByCommunityIdAndRoleId(
            role.getCommunity().getId(),
            role.getId()
        );
        long assignmentCount = assignmentRepository.countByCommunityIdAndRoleId(
            role.getCommunity().getId(),
            role.getId()
        );
        return new RoleDeletionImpactResponse(
            childRoleCount == 0 && assignmentCount == 0,
            childRoleCount,
            demandCount,
            assignmentCount
        );
    }

    private RoleDeletionImpactResponse canonicalDeletionImpact(CommunityTeamRole role) {
        RoleDeletionImpactResponse legacy = legacyDeletionImpact(role);
        return new RoleDeletionImpactResponse(
            legacy.canDelete() && legacy.demandCount() == 0,
            legacy.childRoleCount(),
            legacy.demandCount(),
            legacy.assignmentCount()
        );
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

    private void lockTeamStructure(Integer communityId) {
        communityRepository.findByIdForTeamStructureUpdate(communityId)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Community não encontrada"
            ));
    }

    private CommunityTeamRole requireRole(Community community, Integer roleId) {
        return roleRepository.findByIdAndCommunity(roleId, community)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Cargo não encontrado nesta comunidade"
            ));
    }

    private CommunityTeamRole resolveParent(
        Community community,
        Integer parentId,
        CommunityTeamRole current
    ) {
        if (parentId == null) return null;

        CommunityTeamRole parent = roleRepository.findByIdAndCommunity(parentId, community)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Cargo superior não encontrado nesta comunidade"
            ));

        boolean preservingExistingParent = current != null
            && current.getParentRole() != null
            && Objects.equals(current.getParentRole().getId(), parent.getId());

        if (!Boolean.TRUE.equals(parent.getActive()) && !preservingExistingParent) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Cargo superior inativo não pode receber novas atribuições"
            );
        }

        if (current != null) ensureNoCycle(community, current, parent);
        return parent;
    }

    private void ensureNoCycle(
        Community community,
        CommunityTeamRole role,
        CommunityTeamRole parent
    ) {
        Set<Integer> visited = new HashSet<>();
        CommunityTeamRole current = parent;
        while (current != null) {
            if (!visited.add(current.getId()) || current.getId().equals(role.getId())) {
                throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Cargo superior criaria um ciclo hierárquico"
                );
            }
            if (!community.getId().equals(current.getCommunity().getId())) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Hierarquia de cargos inconsistente entre comunidades"
                );
            }
            current = current.getParentRole();
        }
    }

    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Nome do cargo é obrigatório"
            );
        }
        return normalized;
    }

    private String normalizeDescription(String description) {
        if (description == null) return null;
        String normalized = description.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private RoleResponse toResponse(CommunityTeamRole role) {
        return new RoleResponse(
            role.getId(),
            role.getName(),
            role.getDescription(),
            role.getParentRole() == null ? null : role.getParentRole().getId(),
            Boolean.TRUE.equals(role.getActive()),
            role.getCreatedAt(),
            role.getUpdatedAt()
        );
    }

    private void audit(
        CommunityAuthorizationService.CommunityAccessContext actor,
        String action,
        Integer roleId,
        String metadata
    ) {
        CommunityAuditLog entry = new CommunityAuditLog();
        entry.setCommunity(actor.community());
        entry.setActorUser(actor.user());
        entry.setAction(action);
        entry.setTargetType("TEAM_ROLE");
        entry.setTargetId(String.valueOf(roleId));
        entry.setMetadata(metadata);
        auditRepository.save(entry);
    }

    private String nullableNumber(Integer value) {
        return value == null ? "null" : String.valueOf(value);
    }
}
