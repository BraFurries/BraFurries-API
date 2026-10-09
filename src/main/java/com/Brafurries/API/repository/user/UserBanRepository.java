package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserBan;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserBanRepository extends JpaRepository<UserBan, Integer> {
    List<UserBan> findByUserIdIn(Collection<Integer> userIds);

    List<UserBan> findByUserIdInAndCommunityId(Collection<Integer> userIds, Integer communityId);

    @Query("""
            SELECT DISTINCT ub
            FROM UserBan ub
            JOIN FETCH ub.community c
            LEFT JOIN FETCH c.discord cd
            WHERE ub.user.id IN :userIds
              AND ub.revokedAt IS NULL
              AND (ub.validUntil IS NULL OR ub.validUntil >= :today)
            """)
    List<UserBan> findActiveBansWithDiscordByUserIds(
        @Param("userIds") Collection<Integer> userIds,
        @Param("today") LocalDate today
    );

    long countByUserId(Integer userId);

    long countByUserIdAndCommunityId(Integer userId, Integer communityId);

    @Query("""
            SELECT COUNT(ub)
            FROM UserBan ub
            WHERE ub.user.id IN :userIds
              AND ub.community.id = :communityId
              AND ub.revokedAt IS NULL
              AND (ub.validUntil IS NULL OR ub.validUntil >= :today)
            """)
    long countActiveBansByUserIdsAndCommunityId(
        @Param("userIds") Collection<Integer> userIds,
        @Param("communityId") Integer communityId,
        @Param("today") LocalDate today
    );

    @Query("""
            SELECT COUNT(ub)
            FROM UserBan ub
            WHERE ub.user.id = :userId
              AND ub.community.id = :communityId
              AND ub.revokedAt IS NULL
              AND (ub.validUntil IS NULL OR ub.validUntil >= :today)
            """)
    long countActiveBansByUserIdAndCommunityId(
        @Param("userId") Integer userId,
        @Param("communityId") Integer communityId,
        @Param("today") LocalDate today
    );

    @Query("""
            SELECT COUNT(ub)
            FROM UserBan ub
            WHERE ub.user.id = :userId
              AND ub.revokedAt IS NULL
              AND (ub.validUntil IS NULL OR ub.validUntil >= :today)
            """)
    long countActiveBansByUserId(@Param("userId") Integer userId, @Param("today") LocalDate today);

    @Query("SELECT COUNT(ub) FROM UserBan ub WHERE ub.user.id = :userId AND ub.revokedAt IS NOT NULL")
    long countRevokedBansByUserId(@Param("userId") Integer userId);

    @Query("""
            SELECT COUNT(ub) FROM UserBan ub
            WHERE ub.user.id = :userId AND ub.revokedAt IS NULL
              AND ub.validUntil IS NOT NULL AND ub.validUntil < :today
            """)
    long countExpiredBansByUserId(@Param("userId") Integer userId, @Param("today") LocalDate today);

    @Query("""
            SELECT COUNT(DISTINCT ub.community.id)
            FROM UserBan ub
            WHERE ub.user.id = :userId
            """)
    long countDistinctCommunitiesByUserId(@Param("userId") Integer userId);

    @Query("""
            SELECT ub.user.id AS userId, COUNT(ub) AS total
            FROM UserBan ub
            WHERE ub.user.id IN :userIds
              AND ub.revokedAt IS NULL
              AND (ub.validUntil IS NULL OR ub.validUntil >= :today)
            GROUP BY ub.user.id
            """)
    List<ActiveBanCountProjection> countActiveBansByUserIds(
        @Param("userIds") Collection<Integer> userIds,
        @Param("today") LocalDate today
    );

    interface ActiveBanCountProjection {
        Integer getUserId();
        Long getTotal();
    }
}
