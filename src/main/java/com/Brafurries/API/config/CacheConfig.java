package com.Brafurries.API.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String DASHBOARD_ANALYTICS = "dashboardAnalytics";
    public static final String DASHBOARD_EVENT_CARDS = "dashboardEventCards";
    public static final String DASHBOARD_MANAGED_EVENT = "dashboardManagedEvent";
    public static final String DASHBOARD_SERVERS = "dashboardServers";
    public static final String DASHBOARD_USER_STATUS = "dashboardUserStatus";
    public static final String EVENT_AVAILABILITY = "eventAvailability";
    public static final String ADMIN_ANNOUNCEMENTS_CONFIG = "adminAnnouncementsConfig";
    public static final String ADMIN_GENERAL_METRICS = "adminGeneralMetrics";
    public static final String ADMIN_METRICS = "adminMetrics";
    public static final String ADMIN_OVERVIEW = "adminOverview";
    public static final String ADMIN_PARTNERS = "adminPartners";
    public static final String ADMIN_SESSION = "adminSession";
    public static final String ADMIN_USERS = "adminUsers";

    @Bean
    public CacheManager cacheManager(
            @Value("${app.cache.dashboard.ttl-seconds:60}") long dashboardTtlSeconds,
            @Value("${app.cache.dashboard.max-size:10000}") long dashboardMaxSize
    ) {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCacheNames(List.of(
                DASHBOARD_ANALYTICS,
                DASHBOARD_EVENT_CARDS,
                DASHBOARD_MANAGED_EVENT,
                DASHBOARD_SERVERS,
                DASHBOARD_USER_STATUS,
                EVENT_AVAILABILITY,
                ADMIN_ANNOUNCEMENTS_CONFIG,
                ADMIN_GENERAL_METRICS,
                ADMIN_METRICS,
                ADMIN_OVERVIEW,
                ADMIN_PARTNERS,
                ADMIN_SESSION,
                ADMIN_USERS
        ));
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(dashboardMaxSize)
                .expireAfterWrite(Duration.ofSeconds(Math.max(dashboardTtlSeconds, 1))));
        return cacheManager;
    }
}
