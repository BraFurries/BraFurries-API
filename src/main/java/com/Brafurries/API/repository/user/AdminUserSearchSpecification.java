package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.api.ApiUserRole;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserBan;
import com.Brafurries.API.entity.user.UserDiscord;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/** Global administrative search only; community-scoped searches remain separate. */
final class AdminUserSearchSpecification {

    private AdminUserSearchSpecification() {}

    static Specification<User> matching(String search, Long discordUserId,
                                        String status, String role, LocalDate today) {
        return (user, query, cb) -> {
            List<Predicate> filters = new ArrayList<>();
            if (discordUserId != null) {
                var identityQuery = query.subquery(Integer.class);
                var identity = identityQuery.from(UserDiscord.class);
                identityQuery.select(identity.<Integer>get("id")).where(
                    cb.equal(identity.get("user"), user),
                    cb.equal(identity.get("discordUserId"), discordUserId));
                filters.add(cb.exists(identityQuery));
            } else if (search != null) {
                // Keep database-side case folding and the existing LIKE wildcard semantics.
                Expression<String> pattern = cb.lower(cb.concat(
                    cb.concat(cb.literal("%"), search), cb.literal("%")));
                var identityQuery = query.subquery(Integer.class);
                var identity = identityQuery.from(UserDiscord.class);
                identityQuery.select(identity.<Integer>get("id")).where(
                    cb.equal(identity.get("user"), user),
                    cb.or(matches(cb, identity.get("username"), pattern),
                          matches(cb, identity.get("displayName"), pattern)));
                filters.add(cb.or(matches(cb, user.get("displayName"), pattern),
                    matches(cb, user.get("username"), pattern),
                    matches(cb, user.get("email"), pattern), cb.exists(identityQuery)));
            }

            if (status != null) {
                var banQuery = query.subquery(Integer.class);
                var ban = banQuery.from(UserBan.class);
                Predicate validity = cb.isNull(ban.get("validUntil"));
                if (today != null) {
                    validity = cb.or(validity,
                        cb.greaterThanOrEqualTo(ban.<LocalDate>get("validUntil"), today));
                }
                banQuery.select(ban.<Integer>get("id")).where(
                    cb.equal(ban.get("user"), user), cb.isNull(ban.get("revokedAt")), validity);
                Predicate banned = cb.exists(banQuery);
                // Keep database comparison semantics for filter strings, including collation.
                filters.add(cb.or(cb.and(cb.equal(cb.literal("banned"), status), banned),
                    cb.and(cb.equal(cb.literal("active"), status), cb.not(banned))));
            }

            if (role != null) {
                Predicate admin = hasRole(user, query, cb, "admin");
                Predicate moderator = hasRole(user, query, cb, "moderator");
                Predicate supporter = hasRole(user, query, cb, "supporter");
                // Same highest-role precedence as the previous JPQL.
                filters.add(cb.or(
                    cb.and(cb.equal(cb.literal("admin"), role), admin),
                    cb.and(cb.equal(cb.literal("moderator"), role), cb.not(admin), moderator),
                    cb.and(cb.equal(cb.literal("supporter"), role), cb.not(admin), cb.not(moderator), supporter),
                    cb.and(cb.equal(cb.literal("member"), role), cb.not(admin), cb.not(moderator), cb.not(supporter))
                ));
            }
            return cb.and(filters.toArray(Predicate[]::new));
        };
    }

    private static Predicate matches(CriteriaBuilder cb, Expression<String> field, Expression<String> pattern) {
        return cb.like(cb.lower(cb.coalesce(field, "")), pattern);
    }

    private static Predicate hasRole(Root<User> user, CriteriaQuery<?> query, CriteriaBuilder cb, String role) {
        var roleQuery = query.subquery(Integer.class);
        var assignment = roleQuery.from(ApiUserRole.class);
        roleQuery.select(assignment.get("user").<Integer>get("id")).where(
            cb.equal(assignment.get("user"), user),
            cb.equal(cb.lower(assignment.get("role").<String>get("name")), role));
        return cb.exists(roleQuery);
    }
}
