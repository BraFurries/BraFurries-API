package com.Brafurries.API.repository.event;

import com.Brafurries.API.entity.event.EventStaff;
import com.Brafurries.API.entity.event.EventStaffId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EventStaffRepository extends JpaRepository<EventStaff, EventStaffId> {
    boolean existsByEventIdAndUserEmail(Integer eventId, String email);
    boolean existsByEventIdAndUserEmailAndEditEventTrue(Integer eventId, String email);
    boolean existsByEventIdAndUserEmailAndMngAgendaTrue(Integer eventId, String email);
    Optional<EventStaff> findByEventIdAndUserId(Integer eventId, Integer userId);

    @EntityGraph(attributePaths = {"user"})
    List<EventStaff> findByEventId(Integer eventId);

    void deleteByEventIdAndUserId(Integer eventId, Integer userId);
    void deleteByEventId(Integer eventId);

    @EntityGraph(attributePaths = {"event", "event.hostUser", "user"})
    List<EventStaff> findByUserId(Integer userId);

    @Query("""
            SELECT COUNT(DISTINCT e.id)
            FROM EventStaff staff
            JOIN staff.event e
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
            WHERE LOWER(staff.user.email) = LOWER(:email)
              AND (
                    e.approved IS NULL
                    OR (
                        e.approved = true
                        AND latestSchedule.endingDatetime >= :now
                    )
              )
            """)
    Long countActiveApprovedOrPendingReviewEventsByUserEmail(
            @Param("email") String email,
            @Param("now") java.time.LocalDateTime now
    );
}
