package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserCommunityStatusRepository extends JpaRepository<UserCommunityStatus, Integer> {
    boolean existsByUserIdAndIsPartnerTrue(Integer userId);
    List<UserCommunityStatus> findByUser(User user);
    @Query("SELECT status FROM UserCommunityStatus status JOIN FETCH status.user WHERE status.community.id = :communityId")
    List<UserCommunityStatus> findAllByCommunityIdWithUser(@Param("communityId") Integer communityId);
    Optional<UserCommunityStatus> findFirstByUserOrderByMemberSinceAscCommunityIdAsc(User user);

    long countByUserId(Integer userId);
    long countByUserIdAndApprovedTrue(Integer userId);
    long countByUserIdAndBannedTrue(Integer userId);
    long countByUserIdAndIsVipTrue(Integer userId);
    long countByUserIdAndIsPartnerTrue(Integer userId);

    Optional<UserCommunityStatus> findByUserAndCommunity(User user, Community community);
    List<UserCommunityStatus> findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(Integer userId, Integer communityId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT status
        FROM UserCommunityStatus status
        WHERE status.user.id = :userId
          AND status.community.id = :communityId
        ORDER BY status.memberSince ASC, status.id ASC
        """)
    List<UserCommunityStatus> findAllForAssignmentByUserIdAndCommunityId(
        @Param("userId") Integer userId,
        @Param("communityId") Integer communityId
    );

    @Query("""
        SELECT status
        FROM UserCommunityStatus status
        JOIN FETCH status.user
        WHERE status.community.id = :communityId
          AND status.user.id IN :userIds
        ORDER BY status.user.id ASC, status.memberSince ASC, status.id ASC
        """)
    List<UserCommunityStatus> findAllByCommunityIdAndUserIdIn(
        @Param("communityId") Integer communityId,
        @Param("userIds") Collection<Integer> userIds
    );

}
