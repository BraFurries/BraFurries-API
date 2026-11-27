package com.Brafurries.API.repository.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.entity.event.EventScheduling;
import com.Brafurries.API.entity.event.EventStaff;
import com.Brafurries.API.entity.misc.Locale;
import com.Brafurries.API.entity.misc.Partner;
import com.Brafurries.API.entity.misc.PartnerLink;
import com.Brafurries.API.entity.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class EventListingRepositoryIntegrationTest {
    @Container @ServiceConnection
    static MariaDBContainer<?> mariaDB = new MariaDBContainer<>("mariadb:10.6");
    @Autowired EventRepository events;
    @Autowired EntityManager em;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 27, 12, 0);

    @Test
    void matchesLegacyListingAndCountForPermissionsFiltersAndScheduleOrdering() {
        User host = user("host@example.test");
        User viewer = user("viewer@example.test");
        User unrelated = user("other@example.test");
        Locale sp = locale("SP", "Sao Paulo");
        Locale rj = locale("RJ", "Rio de Janeiro");

        Event ongoing = event(host, sp, "Ongoing", "Sao Paulo", true, true);
        schedule(ongoing, NOW.minusDays(1), NOW.plusHours(2));
        Event future = event(viewer, sp, "Future", "Campinas", true, false);
        schedule(future, NOW.minusDays(3), NOW.minusDays(2));
        schedule(future, NOW.plusDays(2), NOW.plusDays(3));
        Event past = event(host, rj, "Past", "Rio", true, true);
        schedule(past, NOW.minusDays(6), NOW.minusDays(5));
        Event unscheduled = event(unrelated, sp, "Unscheduled", "Sao Paulo", true, false);
        Event pending = event(viewer, sp, "Pending", "Sao Paulo", false, true);
        schedule(pending, NOW.plusDays(1), NOW.plusDays(2));
        Event ties = event(host, sp, "Tie schedule", "Sao Paulo", true, true);
        schedule(ties, NOW.minusDays(2), NOW.plusDays(5));
        schedule(ties, NOW.plusDays(4), NOW.plusDays(5));
        Event editing = event(unrelated, rj, "Editing", "Rio", true, false);
        schedule(editing, NOW.plusHours(1), NOW.plusHours(5));
        Event boundaries = event(host, rj, "Boundary", "Rio", true, true);
        schedule(boundaries, NOW.minusHours(1), NOW);
        staff(ongoing, viewer, false);
        staff(editing, viewer, true);
        staff(pending, unrelated, true);
        link(ongoing, "active", "event");
        link(future, "inactive", "event");
        link(past, "active", "other");
        em.flush();
        em.clear();

        var filters = List.of(
            new Filter(false, false, null, null, null, null, null, null, null, null, null, null, null, false, null),
            new Filter(true, false, null, null, null, null, null, null, null, null, null, null, null, false, null),
            new Filter(false, true, false, null, null, null, null, null, null, null, null, null, null, false, null),
            new Filter(true, false, false, null, null, null, null, null, null, null, null, null, null, false, null),
            new Filter(true, false, true, "sp", "SAO", null, null, null, null, null, "evento", null, null, false, null),
            new Filter(true, false, null, "Rio", null, "it", null, null, null, null, "meet", null, null, false, true),
            new Filter(true, false, null, null, null, null, NOW.minusDays(1), NOW.plusDays(3), null, null, null, null, null, false, null),
            new Filter(true, false, null, null, null, null, null, null, NOW, NOW.plusDays(3), null, null, null, false, null),
            new Filter(true, false, null, null, null, null, NOW.minusDays(3), NOW.plusDays(3), NOW, NOW.plusDays(1), null, null, null, false, null),
            new Filter(false, false, null, null, null, null, null, null, null, null, null, "partner", null, false, null),
            new Filter(true, false, null, null, null, null, null, null, null, null, null, "common", null, false, null),
            new Filter(false, false, null, null, null, null, null, null, null, null, null, "managed", null, false, null),
            new Filter(false, true, null, null, null, null, null, null, null, null, null, "managed", null, true, null),
            new Filter(true, true, null, null, null, null, null, null, null, null, null, "managed", null, false, null),
            new Filter(true, false, null, null, null, null, null, null, null, null, null, "managed", viewer.getId(), false, null),
            new Filter(true, true, null, null, null, null, null, null, null, null, null, "common", viewer.getId(), false, null),
            new Filter(true, false, null, null, null, null, null, null, null, null, null, null, unrelated.getId(), false, null),
            new Filter(false, false, null, null, null, null, null, null, null, null, null, null, null, false, true),
            new Filter(false, false, null, null, null, null, null, null, null, null, null, null, null, false, false),
            new Filter(true, true, null, null, null, null, null, null, null, null, "unknown", null, null, false, null),
            new Filter(true, true, null, null, null, null, null, null, null, null, null, "unknown", null, false, null)
        );
        for (String email : new String[]{"VIEWER@example.test", null}) {
            for (Filter f : filters) {
                var expected = bind(em.createQuery(LEGACY_LIST, Event.class), f, email)
                    .getResultList().stream().map(Event::getId).toList();
                long count = bind(em.createQuery(LEGACY_COUNT, Long.class), f, email).getSingleResult();
                assertEquals(expected.size(), count, "Legacy fixture count: " + f);
                for (int page = 0; page <= 4; page++) {
                    em.clear();
                    var actual = events.findUserEventsListing(email, f.seeAll(), f.manageAll(), f.approved(),
                        f.state(), f.location(), f.name(), f.monthStart(), f.monthEnd(), f.weekStart(), f.weekEnd(),
                        f.type(), f.scope(), f.related(), f.staffOnly(), f.upcoming(), NOW, PageRequest.of(page, 2));
                    var persistence = em.getEntityManagerFactory().getPersistenceUnitUtil();
                    for (Event event : actual.getContent()) {
                        assertTrue(persistence.isLoaded(event, "locale"), "Locale must be loaded");
                        assertTrue(persistence.isLoaded(event, "hostUser"), "Host must be loaded");
                    }
                    assertEquals(count, actual.getTotalElements(), f.toString());
                    assertEquals(expected.stream().skip(page * 2L).limit(2).toList(),
                        actual.map(Event::getId).getContent(), f.toString());
                }
            }
        }
    }

    private record Filter(boolean seeAll, boolean manageAll, Boolean approved, String state,
        String location, String name, LocalDateTime monthStart, LocalDateTime monthEnd,
        LocalDateTime weekStart, LocalDateTime weekEnd, String type, String scope,
        Integer related, boolean staffOnly, Boolean upcoming) {}

    private static <T> TypedQuery<T> bind(TypedQuery<T> q, Filter f, String email) {
        return q.setParameter("userEmail", email).setParameter("canSeeAllEvents", f.seeAll())
            .setParameter("canManageAllEvents", f.manageAll()).setParameter("approved", f.approved())
            .setParameter("state", f.state()).setParameter("location", f.location()).setParameter("name", f.name())
            .setParameter("monthStart", f.monthStart()).setParameter("monthEnd", f.monthEnd())
            .setParameter("weekStart", f.weekStart()).setParameter("weekEnd", f.weekEnd())
            .setParameter("type", f.type()).setParameter("scope", f.scope())
            .setParameter("relatedUserId", f.related()).setParameter("staffOnlyManaged", f.staffOnly())
            .setParameter("upcoming", f.upcoming()).setParameter("now", NOW);
    }

    private User user(String email) {
        User u = new User();
        u.setEmail(email);
        em.persist(u);
        return u;
    }
    private Locale locale(String abbrev, String name) {
        Locale l = new Locale();
        l.setLocaleAbbrev(abbrev);
        l.setLocaleName(name);
        em.persist(l);
        return l;
    }
    private Event event(User owner, Locale locale, String name, String city, boolean approved, boolean type) {
        Event e = new Event();
        e.setHostUser(owner);
        e.setLocale(locale);
        e.setEventName(name);
        e.setCity(city);
        e.setAddress("Test address");
        e.setPrice(0.0);
        e.setPriceConfirmed(true);
        e.setCreatedAt(NOW);
        e.setOutOfTickets(false);
        e.setSalesEnded(false);
        e.setApproved(approved);
        e.setIsEvent(type);
        em.persist(e);
        return e;
    }
    private void schedule(Event e, LocalDateTime start, LocalDateTime end) {
        EventScheduling s = new EventScheduling();
        s.setEvent(e);
        s.setStartingDatetime(start);
        s.setEndingDatetime(end);
        em.persist(s);
    }
    private void staff(Event e, User u, boolean canEdit) {
        EventStaff s = new EventStaff();
        s.setEvent(e);
        s.setUser(u);
        s.setEditEvent(canEdit);
        em.persist(s);
    }
    private void link(Event e, String status, String type) {
        Partner partner = new Partner();
        partner.setName("Partner " + e.getEventName());
        partner.setStatus(status);
        em.persist(partner);
        PartnerLink link = new PartnerLink();
        link.setPartner(partner);
        link.setTargetType(type);
        link.setTargetId(e.getId());
        em.persist(link);
    }

    private static final String LEGACY_LIST = """
            SELECT e
            FROM Event e
            JOIN e.locale l
            LEFT JOIN EventScheduling latestSchedule
                   ON latestSchedule.event = e
                  AND NOT EXISTS (
                        SELECT 1
                        FROM EventScheduling newer
                        WHERE newer.event = e
                          AND (
                                newer.endingDatetime > latestSchedule.endingDatetime
                                OR (newer.endingDatetime = latestSchedule.endingDatetime AND newer.id > latestSchedule.id)
                          )
                  )
            WHERE (:canSeeAllEvents = true OR e.approved = true)
              AND (:canSeeAllEvents = false OR :approved IS NULL OR e.approved = :approved)
              AND (:state IS NULL OR LOWER(l.localeName) LIKE LOWER(CONCAT('%', :state, '%'))
                                 OR LOWER(l.localeAbbrev) = LOWER(:state))
              AND (:location IS NULL OR LOWER(e.city) LIKE LOWER(CONCAT('%', :location, '%')))
              AND (:name IS NULL OR LOWER(e.eventName) LIKE LOWER(CONCAT('%', :name, '%')))
              AND (
                   :monthStart IS NULL
                   OR (
                        latestSchedule.startingDatetime < :monthEnd
                        AND latestSchedule.endingDatetime > :monthStart
                   )
              )
              AND (
                   :weekStart IS NULL
                   OR (
                        latestSchedule.startingDatetime < :weekEnd
                        AND latestSchedule.endingDatetime > :weekStart
                   )
              )
              AND (:type IS NULL OR (:type = 'meet' AND e.isEvent = false) OR (:type = 'evento' AND e.isEvent = true))
              AND (
                   :scope IS NULL
                   OR (:scope = 'partner' AND EXISTS (SELECT 1 FROM PartnerLink pl WHERE pl.targetType = 'event' AND pl.targetId = e.id AND LOWER(pl.partner.status) = 'active'))
                   OR (:scope = 'common' AND NOT EXISTS (SELECT 1 FROM PartnerLink pl WHERE pl.targetType = 'event' AND pl.targetId = e.id AND LOWER(pl.partner.status) = 'active'))
                   OR (:scope = 'managed' AND (
                        (:relatedUserId IS NOT NULL AND (
                            e.hostUser.id = :relatedUserId
                            OR EXISTS (
                                SELECT 1 FROM EventStaff esRelated
                                WHERE esRelated.event.id = e.id
                                  AND esRelated.user.id = :relatedUserId
                            )
                        ))
                        OR (:relatedUserId IS NULL AND (
                            (:staffOnlyManaged = true AND EXISTS (
                                SELECT 1 FROM EventStaff es
                                WHERE es.event.id = e.id
                                  AND LOWER(es.user.email) = LOWER(:userEmail)
                            ))
                            OR (:staffOnlyManaged = false AND (
                                :canManageAllEvents = true
                                OR LOWER(e.hostUser.email) = LOWER(:userEmail)
                                OR EXISTS (
                                    SELECT 1 FROM EventStaff es
                                    WHERE es.event.id = e.id
                                      AND LOWER(es.user.email) = LOWER(:userEmail)
                                      AND es.editEvent = true
                                )
                            ))
                        ))
                   ))
              )
              AND (
                   :relatedUserId IS NULL
                   OR :scope = 'managed'
                   OR e.hostUser.id = :relatedUserId
                   OR EXISTS (
                        SELECT 1 FROM EventStaff esFilter
                        WHERE esFilter.event.id = e.id
                          AND esFilter.user.id = :relatedUserId
                   )
              )
              AND (
                   :upcoming IS NULL
                   OR (:upcoming = true AND latestSchedule.endingDatetime >= :now)
                   OR (:upcoming = false AND latestSchedule.endingDatetime < :now)
              )
            ORDER BY CASE
                         WHEN latestSchedule.startingDatetime IS NULL THEN 3
                         WHEN latestSchedule.startingDatetime <= :now AND latestSchedule.endingDatetime >= :now THEN 0
                         WHEN latestSchedule.startingDatetime > :now THEN 1
                         ELSE 2
                     END ASC,
                     CASE
                         WHEN latestSchedule.startingDatetime <= :now AND latestSchedule.endingDatetime >= :now THEN latestSchedule.endingDatetime
                     END ASC,
                     CASE
                         WHEN latestSchedule.startingDatetime > :now THEN latestSchedule.startingDatetime
                     END ASC,
                     CASE
                         WHEN latestSchedule.startingDatetime < :now THEN latestSchedule.startingDatetime
                     END DESC,
                     e.id ASC
            """;
    private static final String LEGACY_COUNT = """
            SELECT COUNT(e)
            FROM Event e
            JOIN e.locale l
            LEFT JOIN EventScheduling latestSchedule
                   ON latestSchedule.event = e
                  AND NOT EXISTS (
                        SELECT 1
                        FROM EventScheduling newer
                        WHERE newer.event = e
                          AND (
                                newer.endingDatetime > latestSchedule.endingDatetime
                                OR (newer.endingDatetime = latestSchedule.endingDatetime AND newer.id > latestSchedule.id)
                          )
                  )
            WHERE (:canSeeAllEvents = true OR e.approved = true)
              AND (:canSeeAllEvents = false OR :approved IS NULL OR e.approved = :approved)
              AND (:state IS NULL OR LOWER(l.localeName) LIKE LOWER(CONCAT('%', :state, '%'))
                                 OR LOWER(l.localeAbbrev) = LOWER(:state))
              AND (:location IS NULL OR LOWER(e.city) LIKE LOWER(CONCAT('%', :location, '%')))
              AND (:name IS NULL OR LOWER(e.eventName) LIKE LOWER(CONCAT('%', :name, '%')))
              AND (
                   :monthStart IS NULL
                   OR (
                        latestSchedule.startingDatetime < :monthEnd
                        AND latestSchedule.endingDatetime > :monthStart
                   )
              )
              AND (
                   :weekStart IS NULL
                   OR (
                        latestSchedule.startingDatetime < :weekEnd
                        AND latestSchedule.endingDatetime > :weekStart
                   )
              )
              AND (:type IS NULL OR (:type = 'meet' AND e.isEvent = false) OR (:type = 'evento' AND e.isEvent = true))
              AND (
                   :scope IS NULL
                   OR (:scope = 'partner' AND EXISTS (SELECT 1 FROM PartnerLink pl WHERE pl.targetType = 'event' AND pl.targetId = e.id AND LOWER(pl.partner.status) = 'active'))
                   OR (:scope = 'common' AND NOT EXISTS (SELECT 1 FROM PartnerLink pl WHERE pl.targetType = 'event' AND pl.targetId = e.id AND LOWER(pl.partner.status) = 'active'))
                   OR (:scope = 'managed' AND (
                        (:relatedUserId IS NOT NULL AND (
                            e.hostUser.id = :relatedUserId
                            OR EXISTS (
                                SELECT 1 FROM EventStaff esRelated
                                WHERE esRelated.event.id = e.id
                                  AND esRelated.user.id = :relatedUserId
                            )
                        ))
                        OR (:relatedUserId IS NULL AND (
                            (:staffOnlyManaged = true AND EXISTS (
                                SELECT 1 FROM EventStaff es
                                WHERE es.event.id = e.id
                                  AND LOWER(es.user.email) = LOWER(:userEmail)
                            ))
                            OR (:staffOnlyManaged = false AND (
                                :canManageAllEvents = true
                                OR LOWER(e.hostUser.email) = LOWER(:userEmail)
                                OR EXISTS (
                                    SELECT 1 FROM EventStaff es
                                    WHERE es.event.id = e.id
                                      AND LOWER(es.user.email) = LOWER(:userEmail)
                                      AND es.editEvent = true
                                )
                            ))
                        ))
                   ))
              )
              AND (
                   :relatedUserId IS NULL
                   OR :scope = 'managed'
                   OR e.hostUser.id = :relatedUserId
                   OR EXISTS (
                        SELECT 1 FROM EventStaff esFilter
                        WHERE esFilter.event.id = e.id
                          AND esFilter.user.id = :relatedUserId
                   )
              )
              AND (
                   :upcoming IS NULL
                   OR (:upcoming = true AND latestSchedule.endingDatetime >= :now)
                   OR (:upcoming = false AND latestSchedule.endingDatetime < :now)
              )
            """;
}
