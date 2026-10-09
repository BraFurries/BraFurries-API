package com.Brafurries.API.repository.event;

import com.Brafurries.API.entity.event.EventTransferLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EventTransferLogRepository extends JpaRepository<EventTransferLog, Long> {
    void deleteByEventId(Integer eventId);
}
