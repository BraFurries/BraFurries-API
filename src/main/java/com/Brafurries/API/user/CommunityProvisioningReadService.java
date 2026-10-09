package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.CommunityProvisioningDtos.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityTeamRole;
import com.Brafurries.API.entity.community.CommunityTeamRoleAssignment;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.repository.community.CommunityTeamRoleAssignmentRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.user.dto.CommunityTeamRoleDtos.RoleResponse;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommunityProvisioningReadService {
    private final CommunityAuthorizationService authorizationService;
    private final CommunityTeamRoleRepository roleRepository;
    private final CommunityTeamRoleAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final UserCommunityStatusRepository membershipRepository;
    private final UserDiscordRepository userDiscordRepository;
    private final CommunityNetworkAvailabilityService networkAvailability;
    private final CommunityMembershipStatusResolver membershipStatusResolver;

    public CommunityProvisioningReadService(
        CommunityAuthorizationService authorizationService,
        CommunityTeamRoleRepository roleRepository,
        CommunityTeamRoleAssignmentRepository assignmentRepository,
        UserRepository userRepository,
        UserCommunityStatusRepository membershipRepository,
        UserDiscordRepository userDiscordRepository,
        CommunityNetworkAvailabilityService networkAvailability,
        CommunityMembershipStatusResolver membershipStatusResolver
    ) {
        this.authorizationService = authorizationService;
        this.roleRepository = roleRepository;
        this.assignmentRepository = assignmentRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.userDiscordRepository = userDiscordRepository;
        this.networkAvailability = networkAvailability;
        this.membershipStatusResolver = membershipStatusResolver;
    }

    @Transactional(readOnly = true)
    public List<RoleResponse> listRoles(Authentication authentication, Integer communityId) {
        CommunityAuthorizationService.CommunityAccessContext context =
            authorizationService.requireCapability(
                authentication,
                communityId,
                CommunityCapability.TEAM_VIEW
            );

        return roleRepository.findByCommunityOrderByNameAsc(context.community()).stream()
            .map(this::roleResponse)
            .toList();
    }

    @Transactional(readOnly = true)
    public CommunityMemberSummaryResponse listMembers(
        Authentication authentication,
        Integer communityId,
        int page,
        int pageSize,
        String search
    ) {
        CommunityAuthorizationService.CommunityAccessContext context =
            authorizationService.requireCapability(
                authentication,
                communityId,
                CommunityCapability.MEMBERS_VIEW
            );

        Community community = context.community();
        int normalizedPage = Math.max(page, 1);
        int normalizedPageSize = Math.clamp(pageSize, 1, 100);
        var usersPage = CommunityMemberSearch.find(
            userRepository,
            community.getId(),
            search,
            PageRequest.of(
                normalizedPage - 1,
                normalizedPageSize,
                Sort.by(Sort.Direction.ASC, "id")
            )
        );

        List<User> users = usersPage.getContent();
        if (users.isEmpty()) {
            return new CommunityMemberSummaryResponse(
                List.of(),
                new CommunityMemberSummaryPagination(
                    normalizedPage,
                    normalizedPageSize,
                    usersPage.getTotalElements(),
                    usersPage.getTotalPages()
                )
            );
        }

        Collection<Integer> userIds = users.stream().map(User::getId).toList();

        Map<Integer, List<UserCommunityStatus>> membershipsByUser =
            membershipRepository.findAllByCommunityIdAndUserIdIn(community.getId(), userIds)
                .stream()
                .collect(Collectors.groupingBy(status -> status.getUser().getId()));

        Map<Integer, List<Integer>> roleIdsByUser = assignmentRepository
            .findByCommunityIdAndUserIdInOrderByUserIdAscRoleIdAsc(community.getId(), userIds)
            .stream()
            .collect(Collectors.groupingBy(
                CommunityTeamRoleAssignment::getUserId,
                LinkedHashMap::new,
                Collectors.mapping(
                    CommunityTeamRoleAssignment::getRoleId,
                    Collectors.toList()
                )
            ));
        Map<Integer, List<UserDiscord>> identitiesByUser = userDiscordRepository.findByUserIdIn(userIds)
            .stream()
            .collect(Collectors.groupingBy(identity -> identity.getUser().getId()));
        boolean portariaEnabled = networkAvailability.isPortariaEnabled(community);

        List<CommunityMemberSummary> items = users.stream()
            .map(user -> summary(
                user,
                membershipsByUser.getOrDefault(user.getId(), List.of()),
                roleIdsByUser.getOrDefault(user.getId(), List.of()),
                identitiesByUser.getOrDefault(user.getId(), List.of()),
                portariaEnabled
            ))
            .toList();

        return new CommunityMemberSummaryResponse(
            items,
            new CommunityMemberSummaryPagination(
                normalizedPage,
                normalizedPageSize,
                usersPage.getTotalElements(),
                usersPage.getTotalPages()
            )
        );
    }

    private CommunityMemberSummary summary(
        User user,
        List<UserCommunityStatus> memberships,
        List<Integer> roleIds,
        List<UserDiscord> identities,
        boolean portariaEnabled
    ) {
        String status;
        if (memberships.size() != 1) {
            status = memberships.isEmpty() ? "unknown" : "ambiguous";
        } else {
            status = membershipStatusResolver.resolve(
                memberships.getFirst(),
                portariaEnabled
            ).apiValue();
        }

        return new CommunityMemberSummary(
            user.getId(),
            displayName(user, memberships.isEmpty() ? null : memberships.getFirst(), identities),
            networkUsername(user, identities),
            user.getProfileImageUrl(),
            status,
            List.copyOf(roleIds)
        );
    }

    private String networkUsername(User user, List<UserDiscord> identities) {
        return identities.stream()
            .map(UserDiscord::getUsername)
            .filter(Objects::nonNull)
            .filter(value -> !value.isBlank())
            .findFirst()
            .orElse(user.getUsername());
    }

    private String displayName(
        User user,
        UserCommunityStatus membership,
        List<UserDiscord> identities
    ) {
        if (membership != null && membership.getDisplayName() != null && !membership.getDisplayName().isBlank()) {
            return membership.getDisplayName();
        }
        String networkName = identities.stream()
            .map(identity -> identity.getDisplayName() == null || identity.getDisplayName().isBlank()
                ? identity.getUsername()
                : identity.getDisplayName())
            .filter(Objects::nonNull)
            .filter(value -> !value.isBlank())
            .findFirst()
            .orElse(null);
        if (networkName != null) {
            return networkName;
        }
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return "Membro " + user.getId();
    }

    private RoleResponse roleResponse(CommunityTeamRole role) {
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
}
