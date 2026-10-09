package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.CommunityCapabilityManagementDtos.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityTeamRole;
import com.Brafurries.API.entity.community.CommunityTeamRoleCapability;
import com.Brafurries.API.entity.community.CommunityTeamRoleCapabilityId;
import com.Brafurries.API.entity.community.CommunityUserCapabilityGrant;
import com.Brafurries.API.entity.community.CommunityUserCapabilityGrantId;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleCapabilityRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleRepository;
import com.Brafurries.API.repository.community.CommunityUserCapabilityGrantRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import java.util.Arrays;
import java.util.Comparator;
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
public class CommunityCapabilityManagementService {
    private final CommunityAuthorizationService authorizationService;
    private final UserCommunityStatusRepository membershipRepository;
    private final CommunityTeamRoleRepository roleRepository;
    private final CommunityUserCapabilityGrantRepository directGrantRepository;
    private final CommunityTeamRoleCapabilityRepository roleCapabilityRepository;
    private final CommunityAuditLogRepository auditRepository;

    public CommunityCapabilityManagementService(
        CommunityAuthorizationService authorizationService,
        UserCommunityStatusRepository membershipRepository,
        CommunityTeamRoleRepository roleRepository,
        CommunityUserCapabilityGrantRepository directGrantRepository,
        CommunityTeamRoleCapabilityRepository roleCapabilityRepository,
        CommunityAuditLogRepository auditRepository
    ) {
        this.authorizationService = authorizationService;
        this.membershipRepository = membershipRepository;
        this.roleRepository = roleRepository;
        this.directGrantRepository = directGrantRepository;
        this.roleCapabilityRepository = roleCapabilityRepository;
        this.auditRepository = auditRepository;
    }

    @Transactional(readOnly = true)
    public CapabilityCatalogResponse catalog(Authentication authentication, Integer communityId) {
        requireCapabilityManager(authentication, communityId);
        List<CapabilityDefinition> definitions = Arrays.stream(CommunityCapability.values())
            .sorted(Comparator.comparing(Enum::name))
            .map(capability -> new CapabilityDefinition(
                capability.name(),
                capability.directGrantAllowed(),
                capability.roleGrantAllowed(),
                capability.hierarchicalTeamScope()
            ))
            .toList();
        return new CapabilityCatalogResponse(definitions);
    }

    @Transactional(readOnly = true)
    public DirectCapabilityGrantsResponse directGrants(
        Authentication authentication,
        Integer communityId,
        Integer userId
    ) {
        requireCapabilityManager(authentication, communityId);
        requireMembershipRow(communityId, userId, false, false);
        return directResponse(communityId, userId);
    }

    @Transactional
    public DirectCapabilityGrantsResponse grantDirect(
        Authentication authentication,
        Integer communityId,
        Integer userId,
        String capabilityCode
    ) {
        CommunityCapability capability = directCapability(capabilityCode);
        CommunityAuthorizationService.CommunityAccessContext actor =
            capability == CommunityCapability.COMMUNITY_ADMIN
                ? authorizationService.requireOwnerForCommunityAdminManagement(authentication, communityId)
                : requireCapabilityManager(authentication, communityId);

        UserCommunityStatus targetMembership = requireMembershipRow(communityId, userId, true, true);
        Community community = actor.community();

        if (community.getOwnerUser() != null
            && community.getOwnerUser().getId().equals(targetMembership.getUser().getId())) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "O proprietário da Community já possui autoridade total e não recebe grants diretos"
            );
        }

        CommunityUserCapabilityGrantId id =
            new CommunityUserCapabilityGrantId(communityId, userId, capability.name());

        if (!directGrantRepository.existsById(id)) {
            directGrantRepository.saveAndFlush(new CommunityUserCapabilityGrant(
                communityId,
                userId,
                capability.name(),
                actor.user().getId()
            ));
            audit(
                actor,
                "COMMUNITY_CAPABILITY_GRANTED",
                "USER",
                String.valueOf(userId),
                capability
            );
        }

        return directResponse(communityId, userId);
    }

    @Transactional
    public DirectCapabilityGrantsResponse revokeDirect(
        Authentication authentication,
        Integer communityId,
        Integer userId,
        String capabilityCode
    ) {
        CommunityCapability capability = directCapability(capabilityCode);
        CommunityAuthorizationService.CommunityAccessContext actor =
            capability == CommunityCapability.COMMUNITY_ADMIN
                ? authorizationService.requireOwnerForCommunityAdminManagement(authentication, communityId)
                : requireCapabilityManager(authentication, communityId);

        requireMembershipRow(communityId, userId, false, true);

        CommunityUserCapabilityGrantId id =
            new CommunityUserCapabilityGrantId(communityId, userId, capability.name());

        if (directGrantRepository.existsById(id)) {
            directGrantRepository.deleteById(id);
            directGrantRepository.flush();
            audit(
                actor,
                "COMMUNITY_CAPABILITY_REVOKED",
                "USER",
                String.valueOf(userId),
                capability
            );
        }

        return directResponse(communityId, userId);
    }

    @Transactional(readOnly = true)
    public RoleCapabilityGrantsResponse roleGrants(
        Authentication authentication,
        Integer communityId,
        Integer roleId
    ) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            requireCapabilityManager(authentication, communityId);
        requireRole(actor.community(), roleId, false);
        return roleResponse(communityId, roleId);
    }

    @Transactional
    public RoleCapabilityGrantsResponse grantRole(
        Authentication authentication,
        Integer communityId,
        Integer roleId,
        String capabilityCode
    ) {
        CommunityCapability capability = roleCapability(capabilityCode);
        CommunityAuthorizationService.CommunityAccessContext actor =
            requireCapabilityManager(authentication, communityId);
        requireRole(actor.community(), roleId, true);

        CommunityTeamRoleCapabilityId id =
            new CommunityTeamRoleCapabilityId(communityId, roleId, capability.name());

        if (!roleCapabilityRepository.existsById(id)) {
            roleCapabilityRepository.saveAndFlush(new CommunityTeamRoleCapability(
                communityId,
                roleId,
                capability.name(),
                actor.user().getId()
            ));
            audit(
                actor,
                "TEAM_ROLE_CAPABILITY_GRANTED",
                "TEAM_ROLE",
                String.valueOf(roleId),
                capability
            );
        }

        return roleResponse(communityId, roleId);
    }

    @Transactional
    public RoleCapabilityGrantsResponse revokeRole(
        Authentication authentication,
        Integer communityId,
        Integer roleId,
        String capabilityCode
    ) {
        CommunityCapability capability = roleCapability(capabilityCode);
        CommunityAuthorizationService.CommunityAccessContext actor =
            requireCapabilityManager(authentication, communityId);
        requireRole(actor.community(), roleId, true);

        CommunityTeamRoleCapabilityId id =
            new CommunityTeamRoleCapabilityId(communityId, roleId, capability.name());

        if (roleCapabilityRepository.existsById(id)) {
            roleCapabilityRepository.deleteById(id);
            roleCapabilityRepository.flush();
            audit(
                actor,
                "TEAM_ROLE_CAPABILITY_REVOKED",
                "TEAM_ROLE",
                String.valueOf(roleId),
                capability
            );
        }

        return roleResponse(communityId, roleId);
    }

    private CommunityAuthorizationService.CommunityAccessContext requireCapabilityManager(
        Authentication authentication,
        Integer communityId
    ) {
        CommunityAuthorizationService.CommunityAccessContext context =
            authorizationService.resolve(authentication, communityId);
        if (!context.owner() && !context.communityAdmin()) {
            throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Somente Owner ou Community Admin pode configurar capabilities"
            );
        }
        return context;
    }

    private UserCommunityStatus requireMembershipRow(
        Integer communityId,
        Integer userId,
        boolean requireEligible,
        boolean lockForMutation
    ) {
        List<UserCommunityStatus> rows = lockForMutation
            ? membershipRepository.findAllForAssignmentByUserIdAndCommunityId(userId, communityId)
            : membershipRepository.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(userId, communityId);

        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Membro não encontrado nesta Community"
            );
        }
        if (rows.size() != 1) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Estado de membership ambíguo; nenhuma capability foi alterada"
            );
        }

        UserCommunityStatus membership = rows.getFirst();
        if (requireEligible && !authorizationService.isAuthorizationEligibleMembership(membership)) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "O membro não está elegível para receber capabilities"
            );
        }
        return membership;
    }

    private CommunityTeamRole requireRole(
        Community community,
        Integer roleId,
        boolean lockForMutation
    ) {
        return (lockForMutation
            ? roleRepository.findForAssignmentByIdAndCommunity(roleId, community)
            : roleRepository.findByIdAndCommunity(roleId, community))
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Cargo interno não encontrado nesta Community"
            ));
    }

    private CommunityCapability directCapability(String code) {
        CommunityCapability capability = parseCapability(code);
        if (!capability.directGrantAllowed()) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Esta capability não pode ser concedida diretamente na V1"
            );
        }
        return capability;
    }

    private CommunityCapability roleCapability(String code) {
        CommunityCapability capability = parseCapability(code);
        if (!capability.roleGrantAllowed()) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Esta capability não pode ser concedida por cargo na V1"
            );
        }
        return capability;
    }

    private CommunityCapability parseCapability(String code) {
        return CommunityCapability.fromCode(code)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Capability desconhecida"
            ));
    }

    private DirectCapabilityGrantsResponse directResponse(Integer communityId, Integer userId) {
        Set<String> capabilities = directGrantRepository
            .findByCommunityIdAndUserIdOrderByCapabilityAsc(communityId, userId)
            .stream()
            .map(CommunityUserCapabilityGrant::getCapability)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return new DirectCapabilityGrantsResponse(userId, Set.copyOf(capabilities));
    }

    private RoleCapabilityGrantsResponse roleResponse(Integer communityId, Integer roleId) {
        Set<String> capabilities = roleCapabilityRepository
            .findByCommunityIdAndRoleIdOrderByCapabilityAsc(communityId, roleId)
            .stream()
            .map(CommunityTeamRoleCapability::getCapability)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return new RoleCapabilityGrantsResponse(roleId, Set.copyOf(capabilities));
    }

    private void audit(
        CommunityAuthorizationService.CommunityAccessContext actor,
        String action,
        String targetType,
        String targetId,
        CommunityCapability capability
    ) {
        CommunityAuditLog entry = new CommunityAuditLog();
        entry.setCommunity(actor.community());
        entry.setActorUser(actor.user());
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setMetadata("{\"capability\":\"" + capability.name() + "\"}");
        auditRepository.save(entry);
    }
}
