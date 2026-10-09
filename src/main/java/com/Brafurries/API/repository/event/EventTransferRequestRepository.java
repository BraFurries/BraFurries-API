package com.Brafurries.API.repository.event;

import com.Brafurries.API.entity.event.EventTransferRequest;
import com.Brafurries.API.entity.invite.InviteStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface EventTransferRequestRepository extends JpaRepository<EventTransferRequest, Long> {

    void deleteByEventId(Integer eventId);

    @EntityGraph(attributePaths = {"event", "event.hostUser", "originalOwnerUser", "invite", "invite.requestedByUser", "invite.targetUser"})
    Optional<EventTransferRequest> findByInviteId(Long inviteId);

    @Query("""
            SELECT COUNT(r) > 0
            FROM EventTransferRequest r
            WHERE r.event.id = :eventId
              AND r.invite.status = :status
            """)
    boolean existsByEventIdAndInviteStatus(@Param("eventId") Integer eventId, @Param("status") InviteStatus status);
}
