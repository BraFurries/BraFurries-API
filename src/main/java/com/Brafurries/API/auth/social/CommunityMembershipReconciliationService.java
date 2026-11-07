package com.Brafurries.API.auth.social;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Explicitly tenant-scoped policy for the first non-sparse consolidation:
 * transfer a whole membership row when only the absorbed website user owns
 * that Community, it has no restriction or privileged grant, and the Discord
 * history owner has no membership in that Community.
 *
 * The same row (including its ID, portaria decisions and timestamps) is
 * transferred. No duplicate membership, approval reset, role escalation,
 * ban override or cross-Community data folding is allowed.
 *
 * This service does not transfer XP/economy/notes/other ownership. The
 * global live-schema guard must check those references separately while
 * the parent users rows and these membership rows are locked.
 */
@Service
public class CommunityMembershipReconciliationService {
    private final UserCommunityStatusRepository memberships;

    public CommunityMembershipReconciliationService(UserCommunityStatusRepository memberships) {
        this.memberships = memberships;
    }

    public record CommunityDecision(
        Integer communityId,
        String action,
        List<String> reasons
    ) {}

    public record Assessment(
        boolean transferReady,
        List<CommunityDecision> communities,
        List<String> nextActions
    ) {}

    @Transactional(readOnly = true)
    public Assessment preview(User website, User canonicalDiscord) {
        validateUsers(website, canonicalDiscord);
        return evaluate(memberships.findByUser(website), memberships.findByUser(canonicalDiscord));
    }

    /**
     * Must run inside the outer SERIALIZABLE, authenticated account-merge
     * transaction. The executor locks users IDs first in ascending order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UserCommunityStatus> prepareLockedTransfer(User website, User canonicalDiscord) {
        validateUsers(website, canonicalDiscord);
        List<UserCommunityStatus> from = memberships.findAllForAccountReconciliation(website.getId());
        List<UserCommunityStatus> into = memberships.findAllForAccountReconciliation(canonicalDiscord.getId());
        Assessment plan = evaluate(from, into);
        if (!plan.transferReady()) {
            throw blocked("COMMUNITY_MEMBERSHIP_RECONCILIATION_REQUIRED");
        }
        return List.copyOf(from);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void transferPrepared(List<UserCommunityStatus> lockedRows,
                                 User website, User canonicalDiscord) {
        validateUsers(website, canonicalDiscord);
        if (lockedRows == null) throw blocked("COMMUNITY_MEMBERSHIP_RECONCILIATION_REQUIRED");
        for (UserCommunityStatus row : lockedRows) {
            if (row == null || row.getUser() == null
                || !website.getId().equals(row.getUser().getId())
                || !isNeutral(row)) {
                throw blocked("COMMUNITY_MEMBERSHIP_CHANGED");
            }
            row.setUser(canonicalDiscord);
            // Retain membership.id and every historical field unchanged.
            // Saving occurs in the same atomic transaction as the account
            // alias, OAuth subject reassignment and token invalidation.
            memberships.save(row);
        }
        memberships.flush();
    }

    private Assessment evaluate(List<UserCommunityStatus> websiteRows,
                                List<UserCommunityStatus> discordRows) {
        if (websiteRows == null || discordRows == null) {
            return new Assessment(false, List.of(),
                List.of("REVIEW_MEMBERSHIP_INVENTORY"));
        }
        Map<Integer, UserCommunityStatus> canonical = new HashMap<>();
        Set<Integer> invalidCanonical = new HashSet<>();
        for (UserCommunityStatus row : discordRows) {
            Integer id = communityId(row);
            if (id == null || canonical.putIfAbsent(id, row) != null) {
                invalidCanonical.add(id);
            }
        }

        Set<Integer> seenWebsite = new HashSet<>();
        List<CommunityDecision> decisions = new ArrayList<>();
        for (UserCommunityStatus row : websiteRows) {
            Integer communityId = communityId(row);
            List<String> reasons = new ArrayList<>();
            if (communityId == null || !seenWebsite.add(communityId)
                || invalidCanonical.contains(communityId)) {
                reasons.add("REVIEW_MEMBERSHIP_INVENTORY");
            }
            if (canonical.containsKey(communityId)) {
                reasons.add("RECONCILE_DUPLICATE_COMMUNITY");
            }
            if (row == null || Boolean.TRUE.equals(row.getBanned())
                || row.getBanned() == null) {
                reasons.add("PRESERVE_MEMBERSHIP_RESTRICTIONS");
            }
            if (row == null || Boolean.TRUE.equals(row.getIsVip())
                || Boolean.TRUE.equals(row.getIsPartner())
                || row.getIsVip() == null || row.getIsPartner() == null) {
                reasons.add("REVIEW_MEMBERSHIP_GRANTS");
            }
            if (row == null || row.getApproved() == null || row.getMemberSince() == null) {
                reasons.add("REVIEW_LEGACY_MEMBERSHIP_STATE");
            }

            decisions.add(new CommunityDecision(communityId,
                reasons.isEmpty() ? "TRANSFER_CANDIDATE" : "REVIEW_REQUIRED",
                List.copyOf(reasons)));
        }

        for (UserCommunityStatus row : discordRows) {
            Integer id = communityId(row);
            if (!seenWebsite.contains(id)) {
                decisions.add(new CommunityDecision(id,
                    invalidCanonical.contains(id) ? "REVIEW_REQUIRED" : "PRESERVE_CANONICAL",
                    invalidCanonical.contains(id)
                        ? List.of("REVIEW_MEMBERSHIP_INVENTORY") : List.of()));
            }
        }
        decisions.sort(Comparator.comparing(CommunityDecision::communityId,
            Comparator.nullsLast(Integer::compareTo)));
        List<String> blockers = decisions.stream()
            .flatMap(d -> d.reasons().stream()).distinct().toList();
        boolean ready = blockers.isEmpty();
        return new Assessment(ready, List.copyOf(decisions),
            ready ? List.of("VERIFY_ALL_OTHER_USER_REFERENCES_UNDER_LOCK")
                : blockers);
    }

    private Integer communityId(UserCommunityStatus row) {
        return row == null || row.getCommunity() == null
            ? null : row.getCommunity().getId();
    }

    private boolean isNeutral(UserCommunityStatus row) {
        return row != null && row.getCommunity() != null
            && row.getCommunity().getId() != null
            && row.getMemberSince() != null && row.getApproved() != null
            && Boolean.FALSE.equals(row.getBanned())
            && Boolean.FALSE.equals(row.getIsVip())
            && Boolean.FALSE.equals(row.getIsPartner());
    }

    private void validateUsers(User website, User canonicalDiscord) {
        if (website == null || canonicalDiscord == null
            || website.getId() == null || canonicalDiscord.getId() == null
            || website.getId().equals(canonicalDiscord.getId())) {
            throw new IllegalArgumentException("Two independently verified users are required");
        }
    }

    private ResponseStatusException blocked(String reason) {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }
}
