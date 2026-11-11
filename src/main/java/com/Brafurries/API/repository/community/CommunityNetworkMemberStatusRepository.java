package com.Brafurries.API.repository.community;

import com.Brafurries.API.entity.community.CommunityNetworkMemberStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CommunityNetworkMemberStatusRepository
    extends JpaRepository<CommunityNetworkMemberStatus, Long> {

    Optional<CommunityNetworkMemberStatus>
        findByNetworkTypeAndExternalNetworkIdAndUserId(
            String networkType,
            Long externalNetworkId,
            Integer userId
        );

    List<CommunityNetworkMemberStatus>
        findByCommunityIdAndUserIdOrderByIdAsc(Integer communityId, Integer userId);

    List<CommunityNetworkMemberStatus>
        findByNetworkTypeAndExternalNetworkIdAndIsPresentTrue(
            String networkType,
            Long externalNetworkId
        );

    List<CommunityNetworkMemberStatus>
        findByNetworkTypeAndExternalNetworkIdAndUserIdIn(
            String networkType,
            Long externalNetworkId,
            Collection<Integer> userIds
        );

    long countByNetworkTypeAndExternalNetworkIdAndIsPresentTrue(
        String networkType,
        Long externalNetworkId
    );

    /**
     * Distinct platform identities present in active guilds with a completed,
     * healthy full snapshot. Both the network id and tenant must match.
     * Counts persisted identity observations (which can include bot accounts),
     * not necessarily unique human beings or current Discord voice activity.
     */
    @Query("""
        SELECT COUNT(DISTINCT state.user.id)
        FROM CommunityNetworkMemberStatus state
        WHERE state.networkType = 'DISCORD'
          AND state.isPresent = true
          AND EXISTS (
              SELECT guild.id
              FROM CommunityDiscord guild
              WHERE guild.guildId = state.externalNetworkId
                AND guild.community.id = state.community.id
                AND guild.active = true
                AND guild.membershipSyncState = 'HEALTHY'
                AND guild.lastFullReconciliationAt IS NOT NULL
          )
        """)
    long countDistinctPresentUsersInHealthyActiveDiscordGuilds();

    @Query("""
        SELECT COUNT(state)
        FROM CommunityNetworkMemberStatus state
        WHERE state.networkType = :networkType
          AND state.externalNetworkId = :externalNetworkId
          AND state.lastSyncRun.id = :runId
        """)
    long countObservedInRun(
        @Param("networkType") String networkType,
        @Param("externalNetworkId") Long externalNetworkId,
        @Param("runId") Long runId
    );

    @Query("""
        SELECT state
        FROM CommunityNetworkMemberStatus state
        JOIN FETCH state.user
        WHERE state.networkType = :networkType
          AND state.externalNetworkId = :externalNetworkId
          AND state.isPresent = true
          AND state.lastObservedAt <= :reconciliationStartedAt
          AND (state.lastSyncRun IS NULL OR state.lastSyncRun.id <> :runId)
        """)
    List<CommunityNetworkMemberStatus> findPresentNotObservedInRun(
        @Param("networkType") String networkType,
        @Param("externalNetworkId") Long externalNetworkId,
        @Param("runId") Long runId,
        @Param("reconciliationStartedAt") LocalDateTime reconciliationStartedAt
    );
}
