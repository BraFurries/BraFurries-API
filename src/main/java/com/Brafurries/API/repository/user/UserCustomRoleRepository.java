package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserCustomRole;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserCustomRoleRepository extends JpaRepository<UserCustomRole, Integer> {

    @Query("""
        select role
        from UserCustomRole role, UserDiscord discord
        where discord.discordUserId = role.ownerDiscordUserId
          and discord.user.id = :userId
          and role.communityDiscord.community.id = :communityId
        order by role.id desc
        """)
    List<UserCustomRole> findAllByOwnerIdentityAndCommunityOrderByIdDesc(
        @Param("userId") Integer userId,
        @Param("communityId") Integer communityId
    );
}
