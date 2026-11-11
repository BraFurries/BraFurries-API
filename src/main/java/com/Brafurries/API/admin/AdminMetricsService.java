package com.Brafurries.API.admin;

import static com.Brafurries.API.config.CacheConfig.ADMIN_GENERAL_METRICS;

import com.Brafurries.API.admin.dto.AdminMetricsDtos.GeneralBotMetricsResponse;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository.GeneralDiscordMetricsProjection;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTelegramRepository;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;

@Service
public class AdminMetricsService {

    private final CommunityDiscordRepository communityDiscordRepository;
    private final UserRepository userRepository;
    private final UserDiscordRepository userDiscordRepository;
    private final UserTelegramRepository userTelegramRepository;
    private final AdminCoddyReachService reachService;

    public AdminMetricsService(
        CommunityDiscordRepository communityDiscordRepository,
        UserRepository userRepository,
        UserDiscordRepository userDiscordRepository,
        UserTelegramRepository userTelegramRepository,
        AdminCoddyReachService reachService
    ) {
        this.communityDiscordRepository = communityDiscordRepository;
        this.userRepository = userRepository;
        this.userDiscordRepository = userDiscordRepository;
        this.userTelegramRepository = userTelegramRepository;
        this.reachService = reachService;
    }

    @Cacheable(cacheNames = ADMIN_GENERAL_METRICS, key = "'general'")
    public GeneralBotMetricsResponse getGeneralMetrics() {
        GeneralDiscordMetricsProjection result = communityDiscordRepository.fetchGeneralDiscordMetrics();

        long totalDiscordCommunities = toLong(result.getTotalDiscordCommunities());
        long activeDiscordCommunities = toLong(result.getActiveDiscordCommunities());
        // Match the overview/presence reach contract; do not sum per-guild observations.
        long totalMembersReachedActiveCommunities = reachService.read().uniqueMembers();
        long totalMembersReachedAllCommunities = toLong(result.getTotalMembersReachedAllCommunities());

        long totalRegisteredUsers = userRepository.count();
        long totalLinkedDiscordUsers = userDiscordRepository.count();
        long totalLinkedTelegramUsers = userTelegramRepository.count();

        return new GeneralBotMetricsResponse(
            totalDiscordCommunities,
            activeDiscordCommunities,
            Math.max(totalDiscordCommunities - activeDiscordCommunities, 0),
            totalMembersReachedActiveCommunities,
            totalMembersReachedAllCommunities,
            totalRegisteredUsers,
            totalLinkedDiscordUsers,
            totalLinkedTelegramUsers
        );
    }

    private long toLong(Number value) {
        return value == null ? 0L : value.longValue();
    }
}
