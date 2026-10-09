package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.CommunityAuditLog;
import com.Brafurries.API.entity.user.User;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityAuditLogRepository extends JpaRepository<CommunityAuditLog, Long> {

    @Query("""
        SELECT audit
        FROM CommunityAuditLog audit
        JOIN FETCH audit.actorUser actor
        WHERE audit.community.id = :communityId
          AND (:actorUserId IS NULL OR actor.id = :actorUserId)
          AND (:from IS NULL OR audit.createdAt >= :from)
          AND (:to IS NULL OR audit.createdAt <= :to)
          AND (
              :cursorCreatedAt IS NULL
              OR audit.createdAt < :cursorCreatedAt
              OR (audit.createdAt = :cursorCreatedAt AND audit.id < :cursorId)
          )
          AND (
              :categoryMode = 'ALL'
              OR (:categoryMode = 'INCLUDE' AND audit.action IN :categoryActions)
              OR (:categoryMode = 'OTHER' AND audit.action NOT IN :knownActions)
          )
        ORDER BY audit.createdAt DESC, audit.id DESC
        """)
    List<CommunityAuditLog> findCommunityActivity(
        @Param("communityId") Integer communityId,
        @Param("actorUserId") Integer actorUserId,
        @Param("from") LocalDateTime from,
        @Param("to") LocalDateTime to,
        @Param("cursorCreatedAt") LocalDateTime cursorCreatedAt,
        @Param("cursorId") Long cursorId,
        @Param("categoryMode") String categoryMode,
        @Param("categoryActions") Collection<String> categoryActions,
        @Param("knownActions") Collection<String> knownActions,
        Pageable pageable
    );

    @Query("""
        SELECT DISTINCT actor
        FROM CommunityAuditLog audit
        JOIN audit.actorUser actor
        WHERE audit.community.id = :communityId
        ORDER BY actor.id ASC
        """)
    List<User> findDistinctActorsByCommunityId(
        @Param("communityId") Integer communityId
    );
}
