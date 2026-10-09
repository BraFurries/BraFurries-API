package com.Brafurries.API.user;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.community.CommunityTeamRole;
import com.Brafurries.API.entity.community.CommunityTeamRoleAssignment;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityAuditLogRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleAssignmentRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.dto.CommunityMemberDtos.CommunityMember;
import com.Brafurries.API.user.dto.CommunityTeamAssignmentDtos.AssignmentResponse;
import com.Brafurries.API.user.dto.CommunityTeamAssignmentDtos.TeamMemberRef;
import com.Brafurries.API.user.dto.CommunityTeamAssignmentDtos.TeamMemberCandidatePagination;
import com.Brafurries.API.user.dto.CommunityTeamAssignmentDtos.TeamMemberCandidateResponse;
import com.Brafurries.API.user.dto.CommunityTeamRoleDtos.RoleResponse;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CommunityTeamAssignmentService {
    private final CommunityDiscordRepository communityDiscordRepository;
    private final CommunityTeamRoleRepository roleRepository;
    private final CommunityTeamRoleAssignmentRepository assignmentRepository;
    private final UserCommunityStatusRepository membershipRepository;
    private final UserRepository userRepository;
    private final CommunityMemberService memberService;
    private final CommunityAuthorizationService communityAuthorization;
    private final CommunityAuditLogRepository auditRepository;

    public CommunityTeamAssignmentService(
        CommunityDiscordRepository communityDiscordRepository,
        CommunityTeamRoleRepository roleRepository,
        CommunityTeamRoleAssignmentRepository assignmentRepository,
        UserCommunityStatusRepository membershipRepository,
        UserRepository userRepository,
        CommunityMemberService memberService,
        CommunityAuthorizationService communityAuthorization,
        CommunityAuditLogRepository auditRepository
    ) {
        this.communityDiscordRepository = communityDiscordRepository;
        this.roleRepository = roleRepository;
        this.assignmentRepository = assignmentRepository;
        this.membershipRepository = membershipRepository;
        this.userRepository = userRepository;
        this.memberService = memberService;
        this.communityAuthorization = communityAuthorization;
        this.auditRepository = auditRepository;
    }

    // Legacy Discord-scoped entry points remain during migration.
    @Transactional(readOnly = true)
    public List<CommunityMember> listMembers(
        Authentication auth,
        String guildId,
        Integer roleId
    ) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireCapability(
                auth,
                communityIdForGuild(guildId),
                CommunityCapability.MEMBERS_VIEW
            );
        Community community = actor.community();
        requireRole(community, roleId);
        List<Integer> userIds = assignmentRepository
            .findByCommunityIdAndRoleIdOrderByUserIdAsc(community.getId(), roleId)
            .stream()
            .map(CommunityTeamRoleAssignment::getUserId)
            .toList();
        return memberService.listByUserIds(community, userIds);
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles(
        Authentication auth,
        String guildId,
        Integer userId
    ) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireCapability(
                auth,
                communityIdForGuild(guildId),
                CommunityCapability.MEMBERS_VIEW
            );
        Community community = actor.community();
        requireMembership(community, userId);
        return rolesForMember(community, userId);
    }

    @Transactional
    public AssignmentResponse assign(
        Authentication auth,
        String guildId,
        Integer roleId,
        Integer userId
    ) {
        return assignCommunity(auth, communityIdForGuild(guildId), roleId, userId);
    }

    @Transactional
    public void remove(
        Authentication auth,
        String guildId,
        Integer roleId,
        Integer userId
    ) {
        removeCommunity(auth, communityIdForGuild(guildId), roleId, userId);
    }

    // Canonical Community-scoped entry points.
    @Transactional(readOnly = true)
    public List<TeamMemberRef> listMembersCommunity(
        Authentication auth,
        Integer communityId,
        Integer roleId
    ) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.resolve(auth, communityId);
        if (!actor.capabilities().contains(CommunityCapability.MEMBERS_VIEW)) {
            actor = communityAuthorization.requireTeamTarget(
                auth,
                communityId,
                CommunityCapability.TEAM_ASSIGN_MEMBERS,
                roleId
            );
        }
        Community community = actor.community();
        requireRole(community, roleId);

        List<Integer> userIds = assignmentRepository
            .findByCommunityIdAndRoleIdOrderByUserIdAsc(communityId, roleId)
            .stream()
            .map(CommunityTeamRoleAssignment::getUserId)
            .toList();

        return minimalMembers(communityId, userIds);
    }

    @Transactional(readOnly = true)
    public TeamMemberCandidateResponse listMemberCandidatesCommunity(
        Authentication auth,
        Integer communityId,
        Integer roleId,
        int page,
        int pageSize,
        String search
    ) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireTeamTarget(
                auth,
                communityId,
                CommunityCapability.TEAM_ASSIGN_MEMBERS,
                roleId
            );

        requireRole(actor.community(), roleId);

        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.clamp(pageSize, 1, 100);
        String normalizedSearch =
            search == null || search.isBlank() ? null : search.trim();

        var usersPage = userRepository.searchEligibleCommunityTeamCandidates(
            communityId,
            normalizedSearch,
            PageRequest.of(
                normalizedPage - 1,
                normalizedPageSize,
                Sort.by(Sort.Direction.ASC, "id")
            )
        );

        List<TeamMemberRef> items = usersPage.getContent().stream()
            .map(user -> new TeamMemberRef(
                user.getId(),
                displayName(user),
                user.getUsername(),
                user.getProfileImageUrl()
            ))
            .toList();

        return new TeamMemberCandidateResponse(
            items,
            new TeamMemberCandidatePagination(
                normalizedPage,
                normalizedPageSize,
                usersPage.getTotalElements(),
                usersPage.getTotalPages()
            )
        );
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> listRolesCommunity(
        Authentication auth,
        Integer communityId,
        Integer userId
    ) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireCapability(
                auth,
                communityId,
                CommunityCapability.MEMBERS_VIEW
            );
        requireMembership(actor.community(), userId);
        return rolesForMember(actor.community(), userId);
    }

    @Transactional
    public AssignmentResponse assignCommunity(
        Authentication auth,
        Integer communityId,
        Integer roleId,
        Integer userId
    ) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireTeamTarget(
                auth,
                communityId,
                CommunityCapability.TEAM_ASSIGN_MEMBERS,
                roleId
            );

        Community community = actor.community();
        CommunityTeamRole role = requireRoleForAssignment(community, roleId);

        var existing = assignmentRepository
            .findByCommunityIdAndRoleIdAndUserId(communityId, roleId, userId);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }

        if (!Boolean.TRUE.equals(role.getActive())) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "Cargo inativo não pode receber novas atribuições"
            );
        }

        UserCommunityStatus membership = requireMembershipForAssignment(
            community,
            userId
        );
        if (!communityAuthorization.isAuthorizationEligibleMembership(membership)) {
            throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "O membro não está elegível para receber nova atribuição"
            );
        }

        AssignmentResponse response = createAssignment(
            communityId,
            roleId,
            userId
        );
        auditAssignment(actor, "TEAM_MEMBER_ASSIGNED", roleId, userId);
        return response;
    }

    @Transactional
    public void removeCommunity(
        Authentication auth,
        Integer communityId,
        Integer roleId,
        Integer userId
    ) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireTeamTarget(
                auth,
                communityId,
                CommunityCapability.TEAM_ASSIGN_MEMBERS,
                roleId
            );

        requireRole(actor.community(), roleId);
        requireMembership(actor.community(), userId);

        long deleted = assignmentRepository.deleteByCommunityIdAndRoleIdAndUserId(
            communityId,
            roleId,
            userId
        );
        if (deleted > 0) {
            auditAssignment(actor, "TEAM_MEMBER_REMOVED", roleId, userId);
        }
    }

    private List<TeamMemberRef> minimalMembers(
        Integer communityId,
        List<Integer> userIds
    ) {
        if (userIds.isEmpty()) {
            return List.of();
        }

        Collection<Integer> ids = userIds;
        Map<Integer, List<UserCommunityStatus>> byUser = membershipRepository
            .findAllByCommunityIdAndUserIdIn(communityId, ids)
            .stream()
            .collect(Collectors.groupingBy(status -> status.getUser().getId()));

        return userIds.stream()
            .map(userId -> {
                List<UserCommunityStatus> rows = byUser.getOrDefault(
                    userId,
                    List.of()
                );
                UserCommunityStatus membership = requireSingleMembership(rows);
                User user = membership.getUser();
                return new TeamMemberRef(
                    user.getId(),
                    displayName(user),
                    user.getUsername(),
                    user.getProfileImageUrl()
                );
            })
            .toList();
    }

    private List<RoleResponse> rolesForMember(
        Community community,
        Integer userId
    ) {
        List<Integer> roleIds = assignmentRepository
            .findByCommunityIdAndUserIdOrderByRoleIdAsc(
                community.getId(),
                userId
            )
            .stream()
            .map(CommunityTeamRoleAssignment::getRoleId)
            .toList();

        if (roleIds.isEmpty()) {
            return List.of();
        }

        return roleRepository
            .findByCommunityAndIdInOrderByNameAsc(community, roleIds)
            .stream()
            .map(this::toRoleResponse)
            .toList();
    }

    private AssignmentResponse createAssignment(
        Integer communityId,
        Integer roleId,
        Integer userId
    ) {
        try {
            assignmentRepository.insertIfAbsent(
                communityId,
                roleId,
                userId
            );
        } catch (DataIntegrityViolationException exception) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Não foi possível criar a atribuição de cargo",
                exception
            );
        }

        return assignmentRepository
            .findByCommunityIdAndRoleIdAndUserId(
                communityId,
                roleId,
                userId
            )
            .map(this::toResponse)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT,
                "A atribuição não pôde ser confirmada após a gravação"
            ));
    }

    private UserCommunityStatus requireMembershipForAssignment(
        Community community,
        Integer userId
    ) {
        List<UserCommunityStatus> memberships =
            membershipRepository.findAllForAssignmentByUserIdAndCommunityId(
                userId,
                community.getId()
            );
        return requireSingleMembership(memberships);
    }

    private UserCommunityStatus requireMembership(
        Community community,
        Integer userId
    ) {
        List<UserCommunityStatus> memberships =
            membershipRepository.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(
                userId,
                community.getId()
            );
        return requireSingleMembership(memberships);
    }

    private UserCommunityStatus requireSingleMembership(
        List<UserCommunityStatus> memberships
    ) {
        if (memberships.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Membro não encontrado nesta comunidade"
            );
        }
        if (memberships.size() != 1) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Membership da comunidade inconsistente"
            );
        }
        return memberships.getFirst();
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

    private CommunityTeamRole requireRoleForAssignment(
        Community community,
        Integer roleId
    ) {
        return roleRepository.findForAssignmentByIdAndCommunity(
                roleId,
                community
            )
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Cargo não encontrado nesta comunidade"
            ));
    }

    private CommunityTeamRole requireRole(
        Community community,
        Integer roleId
    ) {
        return roleRepository.findByIdAndCommunity(roleId, community)
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Cargo não encontrado nesta comunidade"
            ));
    }

    private AssignmentResponse toResponse(
        CommunityTeamRoleAssignment assignment
    ) {
        return new AssignmentResponse(
            assignment.getRoleId(),
            assignment.getUserId(),
            assignment.getCreatedAt()
        );
    }

    private RoleResponse toRoleResponse(CommunityTeamRole role) {
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

    private String displayName(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return "Membro " + user.getId();
    }

    private void auditAssignment(
        CommunityAuthorizationService.CommunityAccessContext actor,
        String action,
        Integer roleId,
        Integer userId
    ) {
        CommunityAuditLog entry = new CommunityAuditLog();
        entry.setCommunity(actor.community());
        entry.setActorUser(actor.user());
        entry.setAction(action);
        entry.setTargetType("TEAM_ROLE_ASSIGNMENT");
        entry.setTargetId(roleId + ":" + userId);
        entry.setMetadata(
            "{\"roleId\":" + roleId + ",\"userId\":" + userId + "}"
        );
        auditRepository.save(entry);
    }
}
