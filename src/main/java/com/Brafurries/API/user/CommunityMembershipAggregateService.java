package com.Brafurries.API.user;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityNetworkMemberStatus;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.community.CommunityNetworkMemberStatusRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CommunityMembershipAggregateService {
    private final CommunityNetworkMemberStatusRepository networkMemberships;
    private final UserCommunityStatusRepository communityMemberships;

    public CommunityMembershipAggregateService(
        CommunityNetworkMemberStatusRepository networkMemberships,
        UserCommunityStatusRepository communityMemberships
    ) {
        this.networkMemberships = networkMemberships;
        this.communityMemberships = communityMemberships;
    }

    @Transactional
    public UserCommunityStatus recompute(
        Community community,
        User user,
        LocalDateTime observedAt
    ) {
        List<UserCommunityStatus> records =
            communityMemberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(
                user.getId(),
                community.getId()
            );
        if (records.size() > 1) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Membership ambígua para esta Community"
            );
        }

        List<CommunityNetworkMemberStatus> networkStates =
            networkMemberships.findByCommunityIdAndUserIdOrderByIdAsc(
                community.getId(),
                user.getId()
            );
        if (networkStates.isEmpty()) {
            if (records.isEmpty()) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Não há evidência de rede para agregar a membership"
                );
            }
            return records.getFirst();
        }

        UserCommunityStatus aggregate = records.isEmpty()
            ? newAggregate(community, user, networkStates, observedAt)
            : records.getFirst();

        LocalDateTime earliestKnown = networkStates.stream()
            .map(CommunityNetworkMemberStatus::getFirstKnownJoinAt)
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(null);
        if (
            earliestKnown != null
                && (aggregate.getMemberSince() == null
                    || earliestKnown.isBefore(aggregate.getMemberSince()))
        ) {
            aggregate.setMemberSince(earliestKnown);
        }

        LocalDateTime latestJoin = networkStates.stream()
            .map(CommunityNetworkMemberStatus::getLastJoinAt)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
        if (
            latestJoin != null
                && (aggregate.getLastJoinDate() == null
                    || latestJoin.isAfter(aggregate.getLastJoinDate()))
        ) {
            aggregate.setLastJoinDate(latestJoin);
        }

        List<CommunityNetworkMemberStatus> presentStates = networkStates.stream()
            .filter(state -> Boolean.TRUE.equals(state.getIsPresent()))
            .toList();
        boolean wasAggregatePresent = Boolean.TRUE.equals(aggregate.getIsPresent());
        boolean present = !presentStates.isEmpty();
        aggregate.setIsPresent(present);
        if (present) {
            aggregate.setLeftAt(null);
        } else {
            LocalDateTime latestLeave = networkStates.stream()
                .map(CommunityNetworkMemberStatus::getLeftAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
            if (latestLeave != null) {
                aggregate.setLeftAt(latestLeave);
            } else if (wasAggregatePresent) {
                // We know the user is absent now, but a complete baseline does
                // not tell us when an untracked legacy departure happened.
                aggregate.setLeftAt(null);
            }
        }

        networkStates.stream()
            .filter(state -> state.getDisplayName() != null && !state.getDisplayName().isBlank())
            .sorted(
                Comparator.comparing(
                    CommunityNetworkMemberStatus::getLastObservedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())
                ).reversed()
            )
            .findFirst()
            .ifPresent(state -> aggregate.setDisplayName(state.getDisplayName()));

        applyApprovalAggregate(aggregate, presentStates);
        return communityMemberships.save(aggregate);
    }

    private UserCommunityStatus newAggregate(
        Community community,
        User user,
        List<CommunityNetworkMemberStatus> networkStates,
        LocalDateTime observedAt
    ) {
        LocalDateTime firstKnown = networkStates.stream()
            .map(CommunityNetworkMemberStatus::getFirstKnownJoinAt)
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(observedAt);

        UserCommunityStatus aggregate = new UserCommunityStatus();
        aggregate.setCommunity(community);
        aggregate.setUser(user);
        aggregate.setMemberSince(firstKnown);
        aggregate.setLastJoinDate(firstKnown);
        aggregate.setApproved(false);
        aggregate.setApprovedAt(null);
        aggregate.setIsVip(false);
        aggregate.setIsPartner(false);
        aggregate.setBanned(false);
        aggregate.setIsPresent(false);
        aggregate.setLeftAt(null);
        aggregate.setBirthdayMentionable(false);
        aggregate.setApprovalRequired(null);
        return aggregate;
    }

    private void applyApprovalAggregate(
        UserCommunityStatus aggregate,
        List<CommunityNetworkMemberStatus> presentStates
    ) {
        if (presentStates.isEmpty()) {
            return;
        }

        if (presentStates.size() == 1) {
            CommunityNetworkMemberStatus state = presentStates.getFirst();
            if (state.getApprovalRequired() != null) {
                aggregate.setApprovalRequired(state.getApprovalRequired());
            }
            if (state.getApproved() != null) {
                aggregate.setApproved(Boolean.TRUE.equals(state.getApproved()));
            }
            if (Boolean.FALSE.equals(state.getApproved())) {
                aggregate.setApprovedAt(null);
            } else if (state.getApprovedAt() != null) {
                aggregate.setApprovedAt(state.getApprovedAt());
            }
            return;
        }

        boolean hasNotRequired = presentStates.stream()
            .anyMatch(state -> Boolean.FALSE.equals(state.getApprovalRequired()));
        boolean hasApproved = presentStates.stream()
            .anyMatch(state -> Boolean.TRUE.equals(state.getApproved()));
        boolean hasPending = presentStates.stream()
            .anyMatch(state ->
                Boolean.TRUE.equals(state.getApprovalRequired())
                    && Boolean.FALSE.equals(state.getApproved())
            );

        if (hasNotRequired) {
            aggregate.setApprovalRequired(false);
            aggregate.setApproved(true);
        } else if (hasApproved) {
            aggregate.setApprovalRequired(true);
            aggregate.setApproved(true);
        } else if (hasPending) {
            aggregate.setApprovalRequired(true);
            aggregate.setApproved(false);
            aggregate.setApprovedAt(null);
            return;
        }

        LocalDateTime latestApprovedAt = presentStates.stream()
            .map(CommunityNetworkMemberStatus::getApprovedAt)
            .filter(Objects::nonNull)
            .max(Comparator.naturalOrder())
            .orElse(null);
        if (latestApprovedAt != null) {
            aggregate.setApprovedAt(latestApprovedAt);
        }
    }
}
