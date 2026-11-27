package com.Brafurries.API.repository.event;

import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.entity.event.EventScheduling;
import com.Brafurries.API.entity.event.EventStaff;
import com.Brafurries.API.entity.misc.PartnerLink;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.query.criteria.JpaRoot;
import org.hibernate.query.sqm.tree.SqmJoinType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.query.QueryUtils;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.transaction.annotation.Transactional;

/** Criteria implementation of the existing event listing, including schedule tie-breaking. */
public class EventListingRepositoryImpl implements EventListingRepository {
    private final EntityManager em;

    public EventListingRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Event> findUserEventsListing(String userEmail, boolean canSeeAllEvents,
        boolean canManageAllEvents, Boolean approved, String state, String location, String name,
        LocalDateTime monthStart, LocalDateTime monthEnd, LocalDateTime weekStart, LocalDateTime weekEnd,
        String type, String scope, Integer relatedUserId, boolean staffOnlyManaged,
        Boolean upcoming, LocalDateTime now, Pageable pageable) {

        var filter = new Filters(userEmail, canSeeAllEvents, canManageAllEvents, approved,
            state, location, name, monthStart, monthEnd, weekStart, weekEnd, type, scope,
            relatedUserId, staffOnlyManaged, upcoming, now);
        var cb = em.getCriteriaBuilder();
        var query = cb.createQuery(Event.class);
        var event = query.from(Event.class);
        apply(cb, query, event, filter, true, pageable);
        query.select(event);
        var selection = em.createQuery(query);
        if (pageable.isPaged()) {
            selection.setFirstResult(Math.toIntExact(pageable.getOffset()));
            selection.setMaxResults(pageable.getPageSize());
        }
        return PageableExecutionUtils.getPage(selection.getResultList(), pageable, () -> {
            var count = cb.createQuery(Long.class);
            var countedEvent = count.from(Event.class);
            apply(cb, count, countedEvent, filter, false, pageable);
            count.select(cb.count(countedEvent));
            return em.createQuery(count).getSingleResult();
        });
    }

    private record Filters(String email, boolean seeAll, boolean manageAll, Boolean approved,
        String state, String location, String name, LocalDateTime monthStart, LocalDateTime monthEnd,
        LocalDateTime weekStart, LocalDateTime weekEnd, String type, String scope,
        Integer relatedUserId, boolean staffOnly, Boolean upcoming, LocalDateTime now) {}

    private static void apply(CriteriaBuilder cb, CriteriaQuery<?> query, Root<Event> event,
        Filters f, boolean ordered, Pageable pageable) {
        var locale = event.join("locale");
        // The previous JPQL implicitly inner-joined the host through hostUser.email.
        var host = event.join("hostUser");
        // Hibernate 6.6 supports an entity join without modifying the Event mapping.
        var schedule = ((JpaRoot<Event>) event).join(EventScheduling.class, SqmJoinType.LEFT);
        var start = schedule.<LocalDateTime>get("startingDatetime");
        var end = schedule.<LocalDateTime>get("endingDatetime");
        var newerQuery = query.subquery(Integer.class);
        var newer = newerQuery.from(EventScheduling.class);
        newerQuery.select(cb.literal(1)).where(cb.equal(newer.get("event"), event),
            cb.or(cb.greaterThan(newer.<LocalDateTime>get("endingDatetime"), end),
                cb.and(cb.equal(newer.get("endingDatetime"), end),
                    cb.gt(newer.<Integer>get("id"), schedule.<Integer>get("id")))));
        schedule.on(cb.equal(schedule.get("event"), event), cb.not(cb.exists(newerQuery)));

        List<Predicate> predicates = new ArrayList<>();
        if (!f.seeAll()) {
            predicates.add(cb.isTrue(event.get("approved")));
        } else if (f.approved() != null) {
            predicates.add(cb.equal(event.get("approved"), f.approved()));
        }
        if (f.state() != null) {
            predicates.add(cb.or(like(cb, locale.get("localeName"), f.state()),
                cb.equal(cb.lower(locale.get("localeAbbrev")), cb.lower(cb.literal(f.state())))));
        }
        if (f.location() != null) predicates.add(like(cb, event.get("city"), f.location()));
        if (f.name() != null) predicates.add(like(cb, event.get("eventName"), f.name()));
        if (f.monthStart() != null) predicates.add(overlap(cb, start, end, f.monthStart(), f.monthEnd()));
        if (f.weekStart() != null) predicates.add(overlap(cb, start, end, f.weekStart(), f.weekEnd()));
        if (f.type() != null) {
            predicates.add(cb.or(
                cb.and(cb.equal(cb.literal("meet"), f.type()), cb.isFalse(event.get("isEvent"))),
                cb.and(cb.equal(cb.literal("evento"), f.type()), cb.isTrue(event.get("isEvent")))));
        }

        Predicate related = f.relatedUserId() == null ? null : cb.or(
            cb.equal(event.get("hostUser").get("id"), f.relatedUserId()),
            relatedStaff(cb, query, event, f.relatedUserId()));
        if (related != null) predicates.add(related);
        if (f.scope() != null) {
            var partnerQuery = query.subquery(Integer.class);
            var link = partnerQuery.from(PartnerLink.class);
            partnerQuery.select(cb.literal(1)).where(
                cb.equal(link.get("targetType"), "event"),
                cb.equal(link.get("targetId"), event.get("id")),
                cb.equal(cb.lower(link.get("partner").<String>get("status")), "active"));
            Predicate partner = cb.exists(partnerQuery);
            Predicate managed;
            if (related != null) {
                managed = related;
            } else if (f.staffOnly()) {
                managed = emailStaff(cb, query, event, f.email(), false);
            } else if (f.manageAll()) {
                managed = cb.conjunction();
            } else {
                managed = cb.or(emailEquals(cb, host.get("email"), f.email()),
                    emailStaff(cb, query, event, f.email(), true));
            }
            predicates.add(cb.or(
                cb.and(cb.equal(cb.literal("partner"), f.scope()), partner),
                cb.and(cb.equal(cb.literal("common"), f.scope()), cb.not(partner)),
                cb.and(cb.equal(cb.literal("managed"), f.scope()), managed)));
        }

        Expression<LocalDateTime> now = f.now() == null ? cb.nullLiteral(LocalDateTime.class) : cb.literal(f.now());
        if (f.upcoming() != null) {
            predicates.add(f.upcoming() ? cb.greaterThanOrEqualTo(end, now) : cb.lessThan(end, now));
        }
        query.where(predicates.toArray(Predicate[]::new));
        if (ordered) {
            Predicate ongoing = cb.and(cb.lessThanOrEqualTo(start, now), cb.greaterThanOrEqualTo(end, now));
            Predicate future = cb.greaterThan(start, now);
            var rank = cb.<Integer>selectCase().when(cb.isNull(start), 3)
                .when(ongoing, 0).when(future, 1).otherwise(2);
            List<Order> order = new ArrayList<>(List.of(
                cb.asc(rank),
                cb.asc(cb.<LocalDateTime>selectCase().when(ongoing, end).otherwise(cb.nullLiteral(LocalDateTime.class))),
                cb.asc(cb.<LocalDateTime>selectCase().when(future, start).otherwise(cb.nullLiteral(LocalDateTime.class))),
                cb.desc(cb.<LocalDateTime>selectCase().when(cb.lessThan(start, now), start)
                    .otherwise(cb.nullLiteral(LocalDateTime.class))),
                cb.asc(event.get("id"))));
            order.addAll(QueryUtils.toOrders(pageable.getSort(), event, cb));
            query.orderBy(order);
        }
    }

    private static Predicate like(CriteriaBuilder cb, Expression<String> field, String value) {
        return cb.like(cb.lower(field), cb.lower(cb.concat(cb.concat(cb.literal("%"), value), cb.literal("%"))));
    }

    private static Predicate overlap(CriteriaBuilder cb, Expression<LocalDateTime> start,
        Expression<LocalDateTime> end, LocalDateTime from, LocalDateTime to) {
        return to == null ? cb.disjunction() : cb.and(cb.lessThan(start, to), cb.greaterThan(end, from));
    }

    private static Predicate relatedStaff(CriteriaBuilder cb, CriteriaQuery<?> query, Root<Event> event, int id) {
        var subquery = query.subquery(Integer.class);
        var staff = subquery.from(EventStaff.class);
        subquery.select(cb.literal(1)).where(cb.equal(staff.get("event"), event),
            cb.equal(staff.get("user").get("id"), id));
        return cb.exists(subquery);
    }

    private static Predicate emailEquals(CriteriaBuilder cb, Expression<String> email, String value) {
        return value == null ? cb.disjunction() : cb.equal(cb.lower(email), cb.lower(cb.literal(value)));
    }

    private static Predicate emailStaff(CriteriaBuilder cb, CriteriaQuery<?> query, Root<Event> event,
        String email, boolean requiresEdit) {
        var subquery = query.subquery(Integer.class);
        var staff = subquery.from(EventStaff.class);
        var owned = cb.equal(staff.get("event"), event);
        var matches = emailEquals(cb, staff.get("user").get("email"), email);
        subquery.select(cb.literal(1)).where(owned, matches,
            requiresEdit ? cb.isTrue(staff.get("editEvent")) : cb.conjunction());
        return cb.exists(subquery);
    }
}
