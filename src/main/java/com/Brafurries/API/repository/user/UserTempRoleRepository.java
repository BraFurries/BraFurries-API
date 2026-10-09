package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserTempRole;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.EntityGraph;

@Repository
public interface UserTempRoleRepository extends JpaRepository<UserTempRole, Integer> {
    List<UserTempRole> findByDiscordCommunity_Id(Integer discordCommunityId);
    @EntityGraph(attributePaths = {"discordUser", "discordCommunity"})
    List<UserTempRole> findAllByDiscordUser_User_IdAndDiscordCommunity_Community_IdOrderByExpiringDateAsc(
        Integer userId, Integer communityId
    );
}
