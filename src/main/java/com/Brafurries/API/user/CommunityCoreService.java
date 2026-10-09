package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.CommunityCoreDtos.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CommunityCoreService {
    private final CommunityRepository communityRepository;
    private final CommunityDiscordRepository communityDiscordRepository;
    private final UserRepository userRepository;
    private final UserCommunityStatusRepository membershipRepository;
    private final CommunityNetworkAvailabilityService networkAvailability;
    private final CommunityMembershipStatusResolver membershipStatusResolver;

    public CommunityCoreService(
        CommunityRepository communityRepository,
        CommunityDiscordRepository communityDiscordRepository,
        UserRepository userRepository,
        UserCommunityStatusRepository membershipRepository,
        CommunityNetworkAvailabilityService networkAvailability,
        CommunityMembershipStatusResolver membershipStatusResolver
    ) {
        this.communityRepository = communityRepository;
        this.communityDiscordRepository = communityDiscordRepository;
        this.userRepository = userRepository;
        this.membershipRepository = membershipRepository;
        this.networkAvailability = networkAvailability;
        this.membershipStatusResolver = membershipStatusResolver;
    }

    @Transactional(readOnly = true)
    public List<CommunityResponse> list(Authentication authentication) {
        User user = authenticatedUser(authentication);
        List<UserCommunityStatus> memberships = membershipRepository.findByUser(user);

        LinkedHashMap<Integer, Community> candidates = new LinkedHashMap<>();
        communityRepository.findOwnedByUserId(user.getId())
            .forEach(community -> candidates.put(community.getId(), community));
        memberships.stream()
            .map(UserCommunityStatus::getCommunity)
            .forEach(community -> candidates.put(community.getId(), community));

        if (candidates.isEmpty()) {
            return List.of();
        }

        Map<Integer, CommunityDiscord> activeDiscordByCommunity = communityDiscordRepository
            .findByCommunityIds(candidates.keySet())
            .stream()
            .filter(link -> Boolean.TRUE.equals(link.getActive()))
            .collect(Collectors.toMap(
                link -> link.getCommunity().getId(),
                link -> link,
                (first, ignored) -> first
            ));

        Set<Integer> activeMemberCommunityIds = memberships.stream()
            .filter(membership -> {
                CommunityDiscord link = activeDiscordByCommunity.get(membership.getCommunity().getId());
                return link != null
                    && membershipStatusResolver.resolve(
                        membership,
                        networkAvailability.isPortariaEnabled(link.getGuildId())
                    ) == CommunityMembershipStatus.ACTIVE;
            })
            .map(membership -> membership.getCommunity().getId())
            .collect(Collectors.toSet());

        return candidates.values().stream()
            .filter(community -> activeDiscordByCommunity.containsKey(community.getId()))
            .filter(community -> isOwnedBy(community, user.getId())
                || activeMemberCommunityIds.contains(community.getId()))
            .sorted(Comparator
                .comparingInt((Community community) -> orderingGroup(community, user.getId()))
                .thenComparing(Community::getName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Community::getId))
            .map(community -> response(
                community,
                user.getId(),
                activeMemberCommunityIds.contains(community.getId()),
                activeDiscordByCommunity.get(community.getId())
            ))
            .toList();
    }

    @Transactional(readOnly = true)
    public CommunityResponse get(Authentication authentication, Integer communityId) {
        User user = authenticatedUser(authentication);
        Community community = communityRepository.findById(communityId)
            .orElseThrow(this::communityNotFound);
        CommunityDiscord discordLink = networkAvailability.requireActiveDiscord(community);

        boolean member = membershipRepository.findByUserAndCommunity(user, community)
            .map(membership -> membershipStatusResolver.resolve(
                membership,
                networkAvailability.isPortariaEnabled(discordLink.getGuildId())
            ) == CommunityMembershipStatus.ACTIVE)
            .orElse(false);

        if (!isOwnedBy(community, user.getId()) && !member) {
            throw communityNotFound();
        }

        return response(community, user.getId(), member, discordLink);
    }

    private User authenticatedUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário não autenticado");
        }
        String email = authentication.getName().trim().toLowerCase(Locale.ROOT);
        return userRepository.findByEmail(email)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário autenticado não encontrado"));
    }

    private int orderingGroup(Community community, Integer currentUserId) {
        if (isOwnedBy(community, currentUserId)) {
            return 0;
        }
        if (community.getOwnerUser() == null) {
            return 1;
        }
        return 2;
    }

    private boolean isOwnedBy(Community community, Integer userId) {
        return community.getOwnerUser() != null
            && Objects.equals(community.getOwnerUser().getId(), userId);
    }

    private CommunityResponse response(
        Community community,
        Integer currentUserId,
        boolean member,
        CommunityDiscord discordLink
    ) {
        boolean ownerAssigned = community.getOwnerUser() != null;
        CommunityNetworkRef network = new CommunityNetworkRef(
            "DISCORD",
            String.valueOf(discordLink.getGuildId()),
            discordLink.getName(),
            discordLink.getActive()
        );
        return new CommunityResponse(
            community.getId(),
            community.getName(),
            isOwnedBy(community, currentUserId),
            ownerAssigned,
            member,
            List.of(network)
        );
    }

    private ResponseStatusException communityNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Community não encontrada");
    }
}
