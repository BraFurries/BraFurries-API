package com.Brafurries.API.repository.event;

import com.Brafurries.API.entity.event.EventScheduling;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EventSchedulingRepository extends JpaRepository<EventScheduling, Long> {
    Optional<EventScheduling> findTopByEventIdOrderByEndingDatetimeDescIdDesc(Integer eventId);
    List<EventScheduling> findByEventId(Integer eventId);
    void deleteByEventId(Integer eventId);

    @Query("""
            SELECT es.event.id as eventId, es.startingDatetime as startingDatetime, es.endingDatetime as endingDatetime, es.gcalEventId as gcalEventId
            FROM EventScheduling es
            WHERE es.event.id IN :eventIds
              AND NOT EXISTS (
                    SELECT 1
                    FROM EventScheduling newer
                    WHERE newer.event.id = es.event.id
                      AND (
                            newer.endingDatetime > es.endingDatetime
                            OR (newer.endingDatetime = es.endingDatetime AND newer.id > es.id)
                      )
              )
            """)
    List<LatestEventScheduleProjection> findLatestSchedulesByEventIds(@Param("eventIds") Collection<Integer> eventIds);

    interface LatestEventScheduleProjection {
        Integer getEventId();
        LocalDateTime getStartingDatetime();
        LocalDateTime getEndingDatetime();
        String getGcalEventId();
    }
}
