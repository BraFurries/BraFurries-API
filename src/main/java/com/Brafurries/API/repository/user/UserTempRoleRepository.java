package com.Brafurries.API.repository.user;

import com.Brafurries.API.entity.user.UserTempRole;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    // Filter against the canonical ledger without mapping new columns on the
    // legacy entity. That keeps Hibernate's baseline schema validation valid.
    @Query(value = """
        SELECT t.id FROM user_temp_roles t
        JOIN user_discord ud ON ud.id=t.disc_user_id
        JOIN community_discord cd ON cd.id=t.disc_community_id
        WHERE ud.user_id=:userId AND cd.community_id=:communityId
          AND t.grant_source='LEGACY' AND t.status='ACTIVE'
        ORDER BY t.expiring_date ASC, t.id ASC
        """, nativeQuery = true)
    List<Integer> findActiveLegacyIdsForUser(
        @Param("userId") Integer userId, @Param("communityId") Integer communityId);

    @EntityGraph(attributePaths = {"discordUser", "discordCommunity"})
    @Query("SELECT t FROM UserTempRole t WHERE t.id IN :ids")
    List<UserTempRole> findLegacyWithRelationsByIds(@Param("ids") List<Integer> ids);

    default List<UserTempRole> findAllByDiscordUser_User_IdAndDiscordCommunity_Community_IdOrderByExpiringDateAsc(
        Integer userId, Integer communityId) {
        List<Integer> ids = findActiveLegacyIdsForUser(userId, communityId);
        if (ids.isEmpty()) return List.of();
        Map<Integer, UserTempRole> byId = findLegacyWithRelationsByIds(ids).stream()
            .collect(Collectors.toMap(UserTempRole::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }
}
