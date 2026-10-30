package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserTempRole;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface UserTempRoleRepository extends JpaRepository<UserTempRole, Integer> {
    @Query("""
        SELECT r FROM UserTempRole r
        WHERE r.discordCommunity.id = :discordCommunityId
          AND r.grantSource = 'LEGACY' AND r.status = 'ACTIVE'
        """)
    List<UserTempRole> findByDiscordCommunity_Id(@Param("discordCommunityId") Integer discordCommunityId);

    @EntityGraph(attributePaths = {"discordUser", "discordCommunity"})
    @Query("""
        SELECT r FROM UserTempRole r
        WHERE r.discordUser.user.id = :userId
          AND r.discordCommunity.community.id = :communityId
          AND r.grantSource = 'LEGACY' AND r.status = 'ACTIVE'
        ORDER BY r.expiringDate ASC
        """)
    List<UserTempRole> findAllByDiscordUser_User_IdAndDiscordCommunity_Community_IdOrderByExpiringDateAsc(
        @Param("userId") Integer userId, @Param("communityId") Integer communityId
    );
}
