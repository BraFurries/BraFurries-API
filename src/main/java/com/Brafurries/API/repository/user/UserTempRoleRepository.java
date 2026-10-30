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
    @Query(value = """
        SELECT t.* FROM user_temp_roles t
        JOIN user_discord ud ON ud.id=t.disc_user_id
        JOIN community_discord cd ON cd.id=t.disc_community_id
        WHERE ud.user_id=:userId AND cd.community_id=:communityId
          AND t.grant_source='LEGACY' AND t.status='ACTIVE'
        ORDER BY t.expiring_date ASC
        """, nativeQuery = true)
    List<UserTempRole> findAllByDiscordUser_User_IdAndDiscordCommunity_Community_IdOrderByExpiringDateAsc(
        @Param("userId") Integer userId, @Param("communityId") Integer communityId
    );
}
