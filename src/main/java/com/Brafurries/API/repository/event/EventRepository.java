package com.Brafurries.API.repository.event;

import com.Brafurries.API.entity.event.Event;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EventRepository extends JpaRepository<Event, Integer> {

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM Event e JOIN FETCH e.locale WHERE e.id = :id")
    Optional<Event> findByIdForUpdate(@Param("id") Integer id);

    @EntityGraph(attributePaths = {"locale", "hostUser"})
    @Query("""
            SELECT e FROM Event e
            WHERE e.isEvent = :isEvent
              AND (:search IS NULL OR LOWER(e.eventName) LIKE LOWER(CONCAT('%', :search, '%')))
            ORDER BY e.eventName ASC
            """)
    Page<Event> searchPartnerCandidates(
            @Param("isEvent") Boolean isEvent,
            @Param("search") String search,
            Pageable pageable
    );

    @Query("""
            SELECT e
            FROM Event e
            JOIN FETCH e.locale l
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
            WHERE (
                   :monthStart IS NULL
                   OR (
                        latestSchedule.startingDatetime < :monthEnd
                        AND latestSchedule.endingDatetime > :monthStart
                   )
              )
              AND (:state IS NULL OR LOWER(l.localeName) LIKE LOWER(CONCAT('%', :state, '%'))
                                 OR LOWER(l.localeAbbrev) = LOWER(:state))
              AND (:location IS NULL OR LOWER(e.city) LIKE LOWER(CONCAT('%', :location, '%')))
              AND (:name IS NULL OR LOWER(e.eventName) LIKE LOWER(CONCAT('%', :name, '%')))
            ORDER BY latestSchedule.startingDatetime ASC, e.id DESC
            """)
    List<Event> findAllWithFilters(
            @Param("monthStart") java.time.LocalDateTime monthStart,
            @Param("monthEnd") java.time.LocalDateTime monthEnd,
            @Param("state") String state,
            @Param("location") String location,
            @Param("name") String name
    );

    @EntityGraph(attributePaths = {"locale", "hostUser"})
    Optional<Event> findWithLocaleById(Integer id);

    @EntityGraph(attributePaths = {"locale", "hostUser"})
    @Query("""
            SELECT e
            FROM Event e
            ORDER BY e.id ASC
            """)
    List<Event> findAllWithLocaleAndHostUser();

    @EntityGraph(attributePaths = {"locale", "hostUser"})
    @Query("""
            SELECT e
            FROM Event e
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
            ORDER BY latestSchedule.startingDatetime ASC, e.id DESC
            """)
    List<Event> findAllForUserListing();

    @EntityGraph(attributePaths = {"locale", "hostUser"})
    @Query("""
            SELECT e
            FROM Event e
            WHERE e.approved IS NULL
            ORDER BY e.id ASC
            """)
    List<Event> findPendingApprovalEvents();

    @EntityGraph(attributePaths = {"locale", "hostUser"})
    @Query("""
            SELECT DISTINCT e
            FROM Event e
            WHERE e.approved IS NULL
              AND (
                    e.hostUser.id = :relatedUserId
                    OR EXISTS (
                        SELECT 1
                        FROM EventStaff staff
                        WHERE staff.event = e
                          AND staff.user.id = :relatedUserId
                    )
              )
            ORDER BY e.id ASC
            """)
    List<Event> findPendingApprovalEventsByRelatedUserId(@Param("relatedUserId") Integer relatedUserId);

    @EntityGraph(attributePaths = {"locale", "hostUser"})
    @Query(value = """
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
            """,
            countQuery = """
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
            """)
    Page<Event> findUserEventsListing(
            @Param("userEmail") String userEmail,
            @Param("canSeeAllEvents") boolean canSeeAllEvents,
            @Param("canManageAllEvents") boolean canManageAllEvents,
            @Param("approved") Boolean approved,
            @Param("state") String state,
            @Param("location") String location,
            @Param("name") String name,
            @Param("monthStart") java.time.LocalDateTime monthStart,
            @Param("monthEnd") java.time.LocalDateTime monthEnd,
            @Param("weekStart") java.time.LocalDateTime weekStart,
            @Param("weekEnd") java.time.LocalDateTime weekEnd,
            @Param("type") String type,
            @Param("scope") String scope,
            @Param("relatedUserId") Integer relatedUserId,
            @Param("staffOnlyManaged") boolean staffOnlyManaged,
            @Param("upcoming") Boolean upcoming,
            @Param("now") java.time.LocalDateTime now,
            Pageable pageable
    );

    @EntityGraph(attributePaths = {"hostUser"})
    List<Event> findByHostUserId(Integer userId);

    boolean existsByIdAndHostUserEmail(Integer id, String email);

    @Query("""
            SELECT COUNT(DISTINCT e.id)
            FROM Event e
            JOIN EventScheduling latestSchedule
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
            WHERE e.approved = true
              AND latestSchedule.endingDatetime >= :now
              AND LOWER(e.hostUser.email) = LOWER(:userEmail)
            """)
    Long countActiveApprovedOwnedEvents(
            @Param("userEmail") String userEmail,
            @Param("now") java.time.LocalDateTime now
    );

    @Query("""
            SELECT COUNT(DISTINCT e.id)
            FROM Event e
            JOIN EventScheduling latestSchedule
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
            WHERE e.approved = true
              AND latestSchedule.endingDatetime >= :now
              AND LOWER(e.hostUser.email) <> LOWER(:userEmail)
              AND EXISTS (
                    SELECT 1
                    FROM EventStaff staff
                    WHERE staff.event = e
                      AND LOWER(staff.user.email) = LOWER(:userEmail)
              )
            """)
    Long countActiveApprovedStaffEvents(
            @Param("userEmail") String userEmail,
            @Param("now") java.time.LocalDateTime now
    );

    @Query("""
            SELECT COUNT(e)
            FROM Event e
            WHERE e.approved IS NULL
              AND LOWER(e.hostUser.email) = LOWER(:userEmail)
            """)
    Long countPendingReviewOwnedEvents(@Param("userEmail") String userEmail);

    @Query("""
            SELECT COUNT(DISTINCT e.id)
            FROM Event e
            JOIN EventScheduling latestSchedule
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
            WHERE e.approved = true
              AND latestSchedule.endingDatetime >= :now
            """)
    Long countActiveApprovedEvents(@Param("now") java.time.LocalDateTime now);

    @Query("""
            SELECT COUNT(DISTINCT e.id)
            FROM Event e
            JOIN EventScheduling latestSchedule
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
            WHERE e.approved = true
              AND EXISTS (
                    SELECT 1 FROM PartnerLink pl
                    WHERE pl.targetType = 'event'
                      AND pl.targetId = e.id
                      AND LOWER(pl.partner.status) = 'active'
              )
              AND latestSchedule.endingDatetime >= :now
            """)
    Long countActiveApprovedPartnerEvents(@Param("now") java.time.LocalDateTime now);

    @Query("""
            SELECT COUNT(e)
            FROM Event e
            WHERE e.approved IS NULL
            """)
    Long countPendingApprovalEvents();

    @Query("""
            SELECT
                e.id AS id,
                e.eventName AS eventName,
                e.pointName AS pointName,
                e.address AS address,
                latestSchedule.startingDatetime AS startingDatetime,
                latestSchedule.endingDatetime AS endingDatetime,
                e.eventLogoUrl AS eventLogoUrl,
                e.isEvent AS isEvent
            FROM Event e
            JOIN EventScheduling latestSchedule
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
            WHERE e.approved = true
              AND e.isEvent = :isEvent
              AND (
                    :partnerEvent IS NULL
                    OR (:partnerEvent = true AND EXISTS (SELECT 1 FROM PartnerLink pl WHERE pl.targetType = 'event' AND pl.targetId = e.id AND LOWER(pl.partner.status) = 'active'))
                    OR (:partnerEvent = false AND NOT EXISTS (SELECT 1 FROM PartnerLink pl WHERE pl.targetType = 'event' AND pl.targetId = e.id AND LOWER(pl.partner.status) = 'active'))
              )
              AND latestSchedule.startingDatetime >= :now
            ORDER BY latestSchedule.startingDatetime ASC, e.id ASC
            """)
    List<DashboardEventProjection> findUpcomingApprovedDashboardCardsByTypeAndPartner(
            @Param("isEvent") Boolean isEvent,
            @Param("partnerEvent") Boolean partnerEvent,
            @Param("now") java.time.LocalDateTime now,
            Pageable pageable
    );

    @Query("""
            SELECT DISTINCT
                e.id AS id,
                e.eventName AS eventName,
                e.pointName AS pointName,
                e.address AS address,
                latestSchedule.startingDatetime AS startingDatetime,
                latestSchedule.endingDatetime AS endingDatetime,
                e.eventLogoUrl AS eventLogoUrl,
                e.isEvent AS isEvent
            FROM Event e
            JOIN EventScheduling latestSchedule
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
            WHERE e.approved = true
              AND latestSchedule.startingDatetime >= :now
              AND (
                    LOWER(e.hostUser.email) = LOWER(:userEmail)
                    OR EXISTS (
                        SELECT 1
                        FROM EventStaff staff
                        WHERE staff.event = e
                          AND LOWER(staff.user.email) = LOWER(:userEmail)
                    )
              )
            ORDER BY latestSchedule.startingDatetime ASC, e.id ASC
            """)
    List<DashboardEventProjection> findUpcomingApprovedManagedDashboardCardsByUserEmail(
            @Param("userEmail") String userEmail,
            @Param("now") java.time.LocalDateTime now,
            Pageable pageable
    );

    @Query("""
            SELECT
                e.eventName AS eventName,
                e.city AS city,
                l.id AS localeId,
                l.localeAbbrev AS localeAbbrev,
                l.localeName AS localeName,
                latestSchedule.startingDatetime AS startingDatetime,
                latestSchedule.endingDatetime AS endingDatetime,
                e.isEvent AS isEvent,
                CASE WHEN l.id = :localeId THEN true ELSE false END AS sameLocale,
                CASE
                    WHEN (
                        latestSchedule.startingDatetime < :dateEnd
                        AND latestSchedule.endingDatetime > :dateStart
                    )
                    OR (
                        latestSchedule.startingDatetime < :weekendEnd
                        AND latestSchedule.endingDatetime > :weekendStart
                    )
                    THEN true
                    ELSE false
                END AS sameDateOrWeekend
            FROM Event e
            JOIN e.locale l
            JOIN EventScheduling latestSchedule
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
            WHERE e.approved = true
              AND (
                    (
                        e.isEvent = true
                        AND latestSchedule.startingDatetime < :eventWindowEnd
                        AND latestSchedule.endingDatetime > :eventWindowStart
                    )
                    OR (
                e.isEvent = false
                        AND l.id = :localeId
                        AND (
                              (
                                  latestSchedule.startingDatetime < :dateEnd
                                  AND latestSchedule.endingDatetime > :dateStart
                              )
                              OR (
                                  latestSchedule.startingDatetime < :weekendEnd
                                  AND latestSchedule.endingDatetime > :weekendStart
                              )
                        )
                    )
              )
            ORDER BY latestSchedule.startingDatetime ASC, e.id ASC
            """)
    List<EventAvailabilityProjection> findApprovedNearbyEventsForAvailability(
            @Param("localeId") Integer localeId,
            @Param("dateStart") java.time.LocalDateTime dateStart,
            @Param("dateEnd") java.time.LocalDateTime dateEnd,
            @Param("weekendStart") java.time.LocalDateTime weekendStart,
            @Param("weekendEnd") java.time.LocalDateTime weekendEnd,
            @Param("eventWindowStart") java.time.LocalDateTime eventWindowStart,
            @Param("eventWindowEnd") java.time.LocalDateTime eventWindowEnd
    );

    interface DashboardEventProjection {
        Integer getId();
        String getEventName();
        String getPointName();
        String getAddress();
        java.time.LocalDateTime getStartingDatetime();
        java.time.LocalDateTime getEndingDatetime();
        String getEventLogoUrl();
        Boolean getIsEvent();
    }

    interface EventAvailabilityProjection {
        String getEventName();
        String getCity();
        Integer getLocaleId();
        String getLocaleAbbrev();
        String getLocaleName();
        java.time.LocalDateTime getStartingDatetime();
        java.time.LocalDateTime getEndingDatetime();
        Boolean getIsEvent();
        Boolean getSameLocale();
        Boolean getSameDateOrWeekend();
    }
}
