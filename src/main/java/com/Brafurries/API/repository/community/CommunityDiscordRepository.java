package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityDiscordRepository extends JpaRepository<CommunityDiscord, Integer> {
    Page<CommunityDiscord> findByNameContainingIgnoreCase(String name, Pageable pageable);
    Optional<CommunityDiscord> findByGuildId(Long guildId);
    Optional<CommunityDiscord> findByCommunity(Community community);
    boolean existsByCommunityIdAndActiveTrue(Integer communityId);
    List<CommunityDiscord> findAllByActiveTrueOrderByGuildIdAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT link FROM CommunityDiscord link JOIN FETCH link.community WHERE link.guildId = :guildId")
    Optional<CommunityDiscord> findByGuildIdForUpdate(@Param("guildId") Long guildId);
    @Query("""
        SELECT link
        FROM CommunityDiscord link
        JOIN FETCH link.community
        WHERE link.community.id IN :communityIds
        """)
    List<CommunityDiscord> findByCommunityIds(@Param("communityIds") Collection<Integer> communityIds);

    Optional<CommunityDiscord> findFirstByActiveTrueAndNameIgnoreCase(String name);

    List<CommunityDiscord> findByDiscordAdminIdOrderByGuildIdAsc(Long discordAdminId);
    List<CommunityDiscord> findByDiscordAdminIdAndActiveTrueOrderByGuildIdAsc(Long discordAdminId);
    List<CommunityDiscord> findByDiscordAdminIdInOrderByGuildIdAsc(Collection<Long> discordAdminIds);

    @Query("""
        SELECT
            COUNT(cd) AS totalDiscordCommunities,
            COALESCE(SUM(CASE WHEN cd.active = true THEN 1 ELSE 0 END), 0) AS activeDiscordCommunities,
            COALESCE(SUM(CASE WHEN cd.active = true THEN cd.usersQuantity ELSE 0 END), 0) AS totalMembersReachedActiveCommunities,
            COALESCE(SUM(cd.usersQuantity), 0) AS totalMembersReachedAllCommunities
        FROM CommunityDiscord cd
        """)
    GeneralDiscordMetricsProjection fetchGeneralDiscordMetrics();

    interface GeneralDiscordMetricsProjection {
        Long getTotalDiscordCommunities();
        Long getActiveDiscordCommunities();
        Long getTotalMembersReachedActiveCommunities();
        Long getTotalMembersReachedAllCommunities();
    }
}
