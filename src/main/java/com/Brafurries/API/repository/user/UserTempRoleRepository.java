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
    @Query(value = """
        SELECT t.* FROM user_temp_roles t
        WHERE t.disc_community_id = :discordCommunityId
          AND t.grant_source = 'LEGACY' AND t.status = 'ACTIVE'
        """, nativeQuery = true)
    List<UserTempRole> findByDiscordCommunity_Id(@Param("discordCommunityId") Integer discordCommunityId);

    @EntityGraph(attributePaths = {"discordUser", "discordCommunity"})
    @Query("""
        SELECT t FROM UserTempRole t
        JOIN t.discordUser du
        JOIN t.discordCommunity dc
        WHERE du.user.id = :userId AND dc.community.id = :communityId
          AND t.grantSource = 'LEGACY' AND t.status = 'ACTIVE'
        ORDER BY t.expiringDate ASC
        """)
    List<UserTempRole> findAllByDiscordUser_User_IdAndDiscordCommunity_Community_IdOrderByExpiringDateAsc(
        @Param("userId") Integer userId, @Param("communityId") Integer communityId
    );
}
