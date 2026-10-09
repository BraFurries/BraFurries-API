package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserWarning;
import java.util.Collection;
import java.util.List;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserWarningRepository extends JpaRepository<UserWarning, Integer> {
    List<UserWarning> findByUserIdIn(Collection<Integer> userIds);

    List<UserWarning> findByUserIdInAndCommunityId(Collection<Integer> userIds, Integer communityId);

    long countByUserId(Integer userId);

    long countByUserIdAndExpiredFalse(Integer userId);

    long countByUserIdAndCommunityId(Integer userId, Integer communityId);

    long countByUserIdAndCommunityIdAndExpiredFalse(Integer userId, Integer communityId);

    long countByUserIdInAndCommunityId(Collection<Integer> userIds, Integer communityId);

    long countByUserIdInAndCommunityIdAndExpiredFalse(Collection<Integer> userIds, Integer communityId);

    @Query(value = """
            SELECT * FROM (
                SELECT uw.id AS id, 'warn' AS recordType, uw.reason AS reason, uw.date AS occurredAt,
                       CASE WHEN uw.expired = 0 THEN 'active' ELSE 'expired' END AS status,
                       NULL AS canAppeal, NULL AS validUntil, NULL AS revokedAt, NULL AS revocationReason
                FROM user_warnings uw
                WHERE uw.user_id = :userId AND uw.community_id = :communityId
                UNION ALL
                SELECT ub.id AS id, 'ban' AS recordType, ub.reason AS reason, ub.date AS occurredAt,
                       CASE WHEN ub.revoked_at IS NOT NULL THEN 'revoked'
                            WHEN ub.valid_until IS NOT NULL AND ub.valid_until < :today THEN 'expired'
                            ELSE 'active' END AS status,
                       CASE WHEN ub.can_appeal IS NULL THEN NULL WHEN ub.can_appeal = 0 THEN 0 ELSE 1 END AS canAppeal,
                       ub.valid_until AS validUntil, ub.revoked_at AS revokedAt, ub.revocation_reason AS revocationReason
                FROM user_bans ub
                WHERE ub.user_id = :userId AND ub.community_id = :communityId
            ) member_moderation_records
            ORDER BY occurredAt DESC, recordType ASC, id DESC
            """, countQuery = """
            SELECT (SELECT COUNT(*) FROM user_warnings WHERE user_id = :userId AND community_id = :communityId)
                 + (SELECT COUNT(*) FROM user_bans WHERE user_id = :userId AND community_id = :communityId)
            """, nativeQuery = true)
    Page<MemberModerationProjection> findMemberCommunityModerationRecords(
        @Param("userId") Integer userId,
        @Param("communityId") Integer communityId,
        @Param("today") LocalDate today,
        Pageable pageable
    );

    @Query(value = """
            SELECT * FROM (
                SELECT uw.id AS id, 'warn' AS recordType, uw.reason AS reason,
                       COALESCE(applied.display_name, applied.username, applied.email) AS moderator,
                       c.name AS communityName, uw.date AS occurredAt, CASE WHEN uw.expired = 0 THEN 1 ELSE 0 END AS active,
                       CASE WHEN uw.expired = 0 THEN 'active' ELSE 'expired' END AS status,
                       NULL AS canAppeal, NULL AS validUntil, NULL AS registeredAt, NULL AS revokedAt,
                       NULL AS revokedById, NULL AS revokedByName, NULL AS revocationReason
                FROM user_warnings uw LEFT JOIN users applied ON applied.id = uw.applied_by LEFT JOIN communities c ON c.id = uw.community_id
                WHERE uw.user_id = :userId
                UNION ALL
                SELECT ub.id AS id, 'ban' AS recordType, ub.reason AS reason,
                       COALESCE(applied.display_name, applied.username, applied.email) AS moderator,
                       c.name AS communityName, ub.date AS occurredAt,
                       CASE WHEN ub.revoked_at IS NULL AND (ub.valid_until IS NULL OR ub.valid_until >= :today) THEN 1 ELSE 0 END AS active,
                       CASE WHEN ub.revoked_at IS NOT NULL THEN 'revoked' WHEN ub.valid_until IS NOT NULL AND ub.valid_until < :today THEN 'expired' ELSE 'active' END AS status,
                       CASE WHEN ub.can_appeal IS NULL THEN NULL WHEN ub.can_appeal = 0 THEN 0 ELSE 1 END AS canAppeal,
                       ub.valid_until AS validUntil, ub.registered_at AS registeredAt, ub.revoked_at AS revokedAt,
                       revoked.id AS revokedById, COALESCE(revoked.display_name, revoked.username, revoked.email) AS revokedByName, ub.revocation_reason AS revocationReason
                FROM user_bans ub LEFT JOIN users applied ON applied.id = ub.applied_by LEFT JOIN users revoked ON revoked.id = ub.revoked_by LEFT JOIN communities c ON c.id = ub.community_id
                WHERE ub.user_id = :userId
            ) moderation_records
            ORDER BY occurredAt DESC, recordType ASC, id DESC
            """, countQuery = """
            SELECT (SELECT COUNT(*) FROM user_warnings WHERE user_id = :userId)
                 + (SELECT COUNT(*) FROM user_bans WHERE user_id = :userId)
            """, nativeQuery = true)
    Page<AdminProfileModerationProjection> findAdminProfileRecords(
        @Param("userId") Integer userId, @Param("today") LocalDate today, Pageable pageable
    );

    @Query("""
            SELECT COUNT(DISTINCT uw.community.id)
            FROM UserWarning uw
            WHERE uw.user.id = :userId
            """)
    long countDistinctCommunitiesByUserId(@Param("userId") Integer userId);

    @Query("""
            SELECT uw.community.id AS communityId,
                   uw.community.name AS communityName,
                   COUNT(uw) AS totalWarnings,
                   SUM(CASE WHEN uw.expired = false THEN 1 ELSE 0 END) AS activeWarnings,
                   SUM(CASE WHEN uw.expired = true THEN 1 ELSE 0 END) AS expiredWarnings
            FROM UserWarning uw
            WHERE uw.user.id = :userId
            GROUP BY uw.community.id, uw.community.name
            ORDER BY COUNT(uw) DESC, uw.community.name ASC
            """)
    List<UserWarningByCommunityProjection> countWarningsByCommunity(@Param("userId") Integer userId);

    @Query("""
            SELECT uw.user.id AS userId, COUNT(uw) AS warningCount
            FROM UserWarning uw
            WHERE uw.expired = false
              AND uw.user.id IN :userIds
            GROUP BY uw.user.id
            """)
    List<UserWarningCountProjection> countActiveWarningsByUserIds(@Param("userIds") Collection<Integer> userIds);

    interface UserWarningCountProjection {
        Integer getUserId();
        Long getWarningCount();
    }

    interface AdminProfileModerationProjection {
        Integer getId();
        String getRecordType();
        String getReason();
        String getModerator();
        String getCommunityName();
        LocalDate getOccurredAt();
        Number getActive();
        String getStatus();
        Number getCanAppeal();
        LocalDate getValidUntil();
        java.time.LocalDateTime getRegisteredAt();
        java.time.LocalDateTime getRevokedAt();
        Integer getRevokedById();
        String getRevokedByName();
        String getRevocationReason();
    }

    interface MemberModerationProjection {
        Integer getId();
        String getRecordType();
        String getReason();
        LocalDate getOccurredAt();
        String getStatus();
        Number getCanAppeal();
        LocalDate getValidUntil();
        java.time.LocalDateTime getRevokedAt();
        String getRevocationReason();
    }

    interface UserWarningByCommunityProjection {
        Integer getCommunityId();
        String getCommunityName();
        Long getTotalWarnings();
        Long getActiveWarnings();
        Long getExpiredWarnings();
    }
}
