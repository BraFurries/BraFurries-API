package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserIdentityLink;
import com.Brafurries.API.entity.user.UserIdentityLinkStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserIdentityLinkRepository extends JpaRepository<UserIdentityLink, Long> {

    @EntityGraph(attributePaths = {"userA", "userB", "createdByUser", "revokedByUser"})
    @Query("""
        SELECT link FROM UserIdentityLink link
        WHERE link.revokedAt IS NULL
          AND (
              (link.userA.id = :userId AND link.userB.id = :otherUserId)
              OR (link.userA.id = :otherUserId AND link.userB.id = :userId)
          )
        """)
    Optional<UserIdentityLink> findActiveByPair(
        @Param("userId") Integer userId,
        @Param("otherUserId") Integer otherUserId
    );

    @EntityGraph(attributePaths = {"userA", "userB", "createdByUser", "revokedByUser"})
    @Query("""
        SELECT DISTINCT link FROM UserIdentityLink link
        WHERE link.revokedAt IS NULL
          AND (link.userA.id = :userId OR link.userB.id = :userId)
        ORDER BY link.id
        """)
    List<UserIdentityLink> findActiveLinksContainingUser(@Param("userId") Integer userId);

    @EntityGraph(attributePaths = {"userA", "userB", "createdByUser", "revokedByUser"})
    @Query("""
        SELECT DISTINCT link FROM UserIdentityLink link
        WHERE link.revokedAt IS NULL
          AND link.status = :status
          AND (link.userA.id IN :userIds OR link.userB.id IN :userIds)
        ORDER BY link.id
        """)
    List<UserIdentityLink> findActiveLinksContainingUsersByStatus(
        @Param("userIds") Collection<Integer> userIds,
        @Param("status") UserIdentityLinkStatus status
    );
}
