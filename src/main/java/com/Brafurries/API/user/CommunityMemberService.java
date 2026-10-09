package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.CommunityMemberDtos.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleAssignmentRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.user.dto.MemberDataState;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CommunityMemberService {
    private final CommunityDiscordRepository communityDiscordRepository;
    private final UserRepository userRepository;
    private final UserCommunityStatusRepository membershipRepository;
    private final CommunityTeamRoleAssignmentRepository assignmentRepository;
    private final CommunityAuthorizationService communityAuthorization;
    private final UserDiscordRepository userDiscordRepository;
    private final CommunityNetworkAvailabilityService networkAvailability;
    private final CommunityMembershipStatusResolver membershipStatusResolver;

    public CommunityMemberService(
        CommunityDiscordRepository communityDiscordRepository,
        UserRepository userRepository,
        UserCommunityStatusRepository membershipRepository,
        CommunityTeamRoleAssignmentRepository assignmentRepository,
        CommunityAuthorizationService communityAuthorization,
        UserDiscordRepository userDiscordRepository,
        CommunityNetworkAvailabilityService networkAvailability,
        CommunityMembershipStatusResolver membershipStatusResolver
    ) {
        this.communityDiscordRepository = communityDiscordRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.assignmentRepository = assignmentRepository;
        this.communityAuthorization = communityAuthorization;
        this.userDiscordRepository = userDiscordRepository;
        this.networkAvailability = networkAvailability;
        this.membershipStatusResolver = membershipStatusResolver;
    }

    @Transactional(readOnly = true)
    public CommunityMembersResponse list(
        Authentication auth,
        String guildId,
        int page,
        int pageSize,
        String search
    ) {
        Community community = authorizeCommunityMembersView(auth, guildId);
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
            return new CommunityMembersResponse(
                List.of(),
                new CommunityMembersPagination(
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
        Map<Integer, List<UserDiscord>> identitiesByUser = userDiscordRepository.findByUserIdIn(userIds)
            .stream()
            .collect(Collectors.groupingBy(identity -> identity.getUser().getId()));
        boolean portariaEnabled = networkAvailability.isPortariaEnabled(community);

        List<CommunityMember> items = users.stream()
            .map(user -> toMember(
                user,
                membershipsByUser.getOrDefault(user.getId(), List.of()),
                identitiesByUser.getOrDefault(user.getId(), List.of()),
                portariaEnabled
            ))
            .toList();

        return new CommunityMembersResponse(
            items,
            new CommunityMembersPagination(
                normalizedPage,
                normalizedPageSize,
                usersPage.getTotalElements(),
                usersPage.getTotalPages()
            )
        );
    }

    @Transactional(readOnly = true)
    public CommunityMember get(Authentication auth, String guildId, Integer userId) {
        Community community = authorizeCommunityMembersView(auth, guildId);
        List<UserCommunityStatus> memberships =
            membershipRepository.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(userId, community.getId());

        if (memberships.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Membro não encontrado nesta comunidade");
        }

        User user = memberships.getFirst().getUser();
        return toMember(
            user,
            memberships,
            userDiscordRepository.findAllByUserIdOrderByIdAsc(user.getId()),
            networkAvailability.isPortariaEnabled(community)
        );
    }

    @Transactional(readOnly = true)
    public CommunityMemberDetail getCommunityDetail(
        Authentication auth,
        Integer communityId,
        Integer userId
    ) {
        CommunityAuthorizationService.CommunityAccessContext actor =
            communityAuthorization.requireCapability(
                auth,
                communityId,
                CommunityCapability.MEMBER_DETAILS_VIEW
            );

        Community community = actor.community();
        List<UserCommunityStatus> memberships =
            membershipRepository.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(
                userId,
                community.getId()
            );

        if (memberships.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Membro não encontrado nesta Community"
            );
        }

        List<Integer> roleIds = assignmentRepository
            .findByCommunityIdAndUserIdOrderByRoleIdAsc(community.getId(), userId)
            .stream()
            .map(assignment -> assignment.getRoleId())
            .toList();

        User user = memberships.getFirst().getUser();
        return toDetail(
            user,
            memberships,
            roleIds,
            userDiscordRepository.findAllByUserIdOrderByIdAsc(user.getId()),
            networkAvailability.isPortariaEnabled(community)
        );
    }

    @Transactional(readOnly = true)
    List<CommunityMember> listByUserIds(Community community, List<Integer> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }

        Map<Integer, List<UserCommunityStatus>> membershipsByUser =
            membershipRepository.findAllByCommunityIdAndUserIdIn(community.getId(), userIds)
                .stream()
                .collect(Collectors.groupingBy(status -> status.getUser().getId()));

        Map<Integer, List<UserDiscord>> identitiesByUser = userDiscordRepository.findByUserIdIn(userIds)
            .stream()
            .collect(Collectors.groupingBy(identity -> identity.getUser().getId()));
        boolean portariaEnabled = networkAvailability.isPortariaEnabled(community);
        return userIds.stream()
            .map(userId -> {
                List<UserCommunityStatus> memberships =
                    membershipsByUser.getOrDefault(userId, List.of());
                if (memberships.isEmpty()) {
                    throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Assignment referencia membership inexistente na comunidade"
                    );
                }
                User user = memberships.getFirst().getUser();
                return toMember(
                    user,
                    memberships,
                    identitiesByUser.getOrDefault(userId, List.of()),
                    portariaEnabled
                );
            })
            .toList();
    }

    private Community authorizeCommunityMembersView(Authentication auth, String guildId) {
        final Long parsedGuildId;
        try {
            parsedGuildId = Long.valueOf(guildId);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Servidor Discord inválido");
        }

        CommunityDiscord link = communityDiscordRepository.findByGuildId(parsedGuildId)
            .filter(candidate -> Boolean.TRUE.equals(candidate.getActive()))
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Comunidade do servidor não foi registrada"
            ));
        return communityAuthorization.requireCapability(
            auth,
            link.getCommunity().getId(),
            CommunityCapability.MEMBERS_VIEW
        ).community();
    }

    private CommunityMember toMember(
        User user,
        List<UserCommunityStatus> memberships,
        List<UserDiscord> identities,
        boolean portariaEnabled
    ) {
        if (memberships.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Membership da comunidade inconsistente");
        }

        MemberDataState dataState = memberships.size() == 1
            ? MemberDataState.AVAILABLE
            : MemberDataState.AMBIGUOUS;

        LocalDateTime memberSince = memberships.stream()
            .map(UserCommunityStatus::getMemberSince)
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(null);

        if (dataState == MemberDataState.AMBIGUOUS) {
            return new CommunityMember(
                user.getId(),
                displayName(user, memberships.getFirst(), identities),
                networkUsername(user, identities),
                user.getProfileImageUrl(),
                dataState,
                memberships.size(),
                memberSince,
                "ambiguous",
                null,
                null,
                null,
                null,
                null,
                null
            );
        }

        UserCommunityStatus membership = memberships.getFirst();
        return new CommunityMember(
            user.getId(),
            displayName(user, membership, identities),
            user.getUsername(),
            user.getProfileImageUrl(),
            dataState,
            1,
            memberSince,
            membershipStatusResolver.resolve(membership, portariaEnabled).apiValue(),
            membership.getApproved(),
            membership.getApprovalRequired(),
            membership.getBanned(),
            membership.getIsPresent(),
            membership.getIsVip(),
            membership.getIsPartner()
        );
    }

    private CommunityMemberDetail toDetail(
        User user,
        List<UserCommunityStatus> memberships,
        List<Integer> roleIds,
        List<UserDiscord> identities,
        boolean portariaEnabled
    ) {
        if (memberships.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Membership da Community inconsistente"
            );
        }

        MemberDataState dataState = memberships.size() == 1
            ? MemberDataState.AVAILABLE
            : MemberDataState.AMBIGUOUS;

        LocalDateTime memberSince = memberships.stream()
            .map(UserCommunityStatus::getMemberSince)
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(null);

        if (dataState == MemberDataState.AMBIGUOUS) {
            return new CommunityMemberDetail(
                user.getId(),
                displayName(user, memberships.getFirst(), identities),
                networkUsername(user, identities),
                user.getProfileImageUrl(),
                dataState,
                memberships.size(),
                memberSince,
                null,
                "ambiguous",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.copyOf(roleIds)
            );
        }

        UserCommunityStatus membership = memberships.getFirst();
        return new CommunityMemberDetail(
            user.getId(),
            displayName(user, membership, identities),
            user.getUsername(),
            user.getProfileImageUrl(),
            dataState,
            1,
            memberSince,
            membership.getLastJoinDate(),
            membershipStatusResolver.resolve(membership, portariaEnabled).apiValue(),
            membership.getApproved(),
            membership.getApprovalRequired(),
            membership.getApprovedAt(),
            membership.getBanned(),
            membership.getIsPresent(),
            membership.getLeftAt(),
            membership.getIsVip(),
            membership.getIsPartner(),
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
        if (membership.getDisplayName() != null && !membership.getDisplayName().isBlank()) {
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
}
