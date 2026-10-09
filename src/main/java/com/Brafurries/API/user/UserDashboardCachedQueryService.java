package com.Brafurries.API.user;

import static com.Brafurries.API.config.CacheConfig.DASHBOARD_ANALYTICS;
import static com.Brafurries.API.config.CacheConfig.DASHBOARD_EVENT_CARDS;
import static com.Brafurries.API.config.CacheConfig.DASHBOARD_MANAGED_EVENT;
import static com.Brafurries.API.config.CacheConfig.DASHBOARD_SERVERS;
import static com.Brafurries.API.config.CacheConfig.DASHBOARD_USER_STATUS;

import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository.GeneralDiscordMetricsProjection;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.event.EventRepository.DashboardEventProjection;
import com.Brafurries.API.repository.user.UserLevelRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

@Service
public class UserDashboardCachedQueryService {

    private final UserLevelRepository userLevelRepository;
    private final CommunityDiscordRepository communityDiscordRepository;
    private final EventRepository eventRepository;

    public UserDashboardCachedQueryService(
            UserLevelRepository userLevelRepository,
            CommunityDiscordRepository communityDiscordRepository,
            EventRepository eventRepository
    ) {
        this.userLevelRepository = userLevelRepository;
        this.communityDiscordRepository = communityDiscordRepository;
        this.eventRepository = eventRepository;
    }

    @Cacheable(cacheNames = DASHBOARD_USER_STATUS, key = "'level:' + #userId + ':community:' + #communityId")
    public Integer findUserLevel(Integer userId, Integer communityId) {
        return userLevelRepository.findByUserIdAndCommunityDiscordCommunityId(userId, communityId)
                .map(userLevel -> userLevel.getCurrentLevel())
                .orElse(null);
    }

    @Cacheable(cacheNames = DASHBOARD_SERVERS, key = "'discordAdmin:' + #discordUserId")
    public List<CommunityDiscord> findServersByDiscordAdminId(Long discordUserId) {
        return communityDiscordRepository.findByDiscordAdminIdAndActiveTrueOrderByGuildIdAsc(discordUserId);
    }

    @Cacheable(cacheNames = DASHBOARD_ANALYTICS, key = "'generalDiscordMetrics'")
    public GeneralDiscordMetricsProjection fetchGeneralDiscordMetrics() {
        return communityDiscordRepository.fetchGeneralDiscordMetrics();
    }

    @Cacheable(cacheNames = DASHBOARD_MANAGED_EVENT, key = "#userEmail + ':' + #timeBucket")
    public DashboardEventProjection findNextManagedEvent(String userEmail, long timeBucket) {
        return eventRepository.findUpcomingApprovedManagedDashboardCardsByUserEmail(userEmail, LocalDateTime.now(), PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .orElse(null);
    }

    @Cacheable(cacheNames = DASHBOARD_EVENT_CARDS, key = "'type:' + #isEvent + ':' + #timeBucket")
    public List<DashboardEventProjection> findDashboardEventsByType(boolean isEvent, long timeBucket) {
        LocalDateTime now = LocalDateTime.now();
        List<DashboardEventProjection> partnerEvents = eventRepository.findUpcomingApprovedDashboardCardsByTypeAndPartner(isEvent, true, now, PageRequest.of(0, 1));
        if (partnerEvents.isEmpty()) {
            return eventRepository.findUpcomingApprovedDashboardCardsByTypeAndPartner(isEvent, null, now, PageRequest.of(0, 2));
        }

        List<DashboardEventProjection> commonEvents = eventRepository.findUpcomingApprovedDashboardCardsByTypeAndPartner(isEvent, false, now, PageRequest.of(0, 1));
        return java.util.stream.Stream.concat(partnerEvents.stream(), commonEvents.stream())
                .limit(2)
                .toList();
    }
}
