package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.entity.user.UserDiscord;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Community membership is mandatory for both list and count, independent of text matching. */
final class CommunityUserSearchSpecification {

    private CommunityUserSearchSpecification() {}

    static Specification<User> matching(Integer communityId, String search, boolean eligibleOnly) {
        return (user, query, cb) -> {
            // The original equality predicate never matches a null tenant.
            if (communityId == null) {
                return cb.disjunction();
            }
            var membershipQuery = query.subquery(Integer.class);
            var membership = membershipQuery.from(UserCommunityStatus.class);
            List<Predicate> membershipFilters = new ArrayList<>();
            membershipFilters.add(cb.equal(membership.get("user"), user));
            membershipFilters.add(cb.equal(membership.get("community").get("id"), communityId));
            if (eligibleOnly) {
                // Preserve SQL three-valued logic: null isPresent is not eligible.
                membershipFilters.add(cb.isTrue(membership.get("approved")));
                membershipFilters.add(cb.isFalse(membership.get("banned")));
                membershipFilters.add(cb.isTrue(membership.get("isPresent")));
            }
            membershipQuery.select(membership.<Integer>get("id"))
                .where(membershipFilters.toArray(Predicate[]::new));
            Predicate inCommunity = cb.exists(membershipQuery);
            if (search == null) {
                return inCommunity;
            }

            Expression<String> pattern = cb.lower(cb.concat(
                cb.concat(cb.literal("%"), search), cb.literal("%")));
            // Keep this separate from eligibility: legacy SQL allows the
            // matching membership row to differ from the eligible row.
            var nameQuery = query.subquery(Integer.class);
            var namedMembership = nameQuery.from(UserCommunityStatus.class);
            nameQuery.select(namedMembership.<Integer>get("id")).where(
                cb.equal(namedMembership.get("user"), user),
                cb.equal(namedMembership.get("community").get("id"), communityId),
                matches(cb, namedMembership.get("displayName"), pattern));

            var identityQuery = query.subquery(Integer.class);
            var identity = identityQuery.from(UserDiscord.class);
            identityQuery.select(identity.<Integer>get("id")).where(
                cb.equal(identity.get("user"), user),
                cb.or(matches(cb, identity.get("username"), pattern),
                    matches(cb, identity.get("displayName"), pattern)));

            return cb.and(inCommunity, cb.or(matches(cb, user.get("displayName"), pattern),
                matches(cb, user.get("username"), pattern),
                cb.exists(nameQuery), cb.exists(identityQuery)));
        };
    }

    private static Predicate matches(CriteriaBuilder cb, Expression<String> field, Expression<String> pattern) {
        return cb.like(cb.lower(cb.coalesce(field, "")), pattern);
    }
}
