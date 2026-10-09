package com.Brafurries.API.user;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.event.EventPartnershipService;
import com.Brafurries.API.repository.community.CommunityDiscordRepository.GeneralDiscordMetricsProjection;
import com.Brafurries.API.repository.event.EventRepository.DashboardEventProjection;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.dto.UserDashboardDtos.DashboardAnalyticsItem;
import com.Brafurries.API.user.dto.UserDashboardDtos.DashboardEventItem;
import com.Brafurries.API.user.dto.UserDashboardDtos.DashboardManagedEvent;
import com.Brafurries.API.user.dto.UserDashboardDtos.DashboardServerItem;
import com.Brafurries.API.user.dto.UserDashboardDtos.DiscordConnectionStatus;
import com.Brafurries.API.user.dto.UserDashboardDtos.MyStatus;
import com.Brafurries.API.user.dto.UserDashboardDtos.UserDashboardResponse;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserDashboardService {

    private final UserRepository userRepository;
    private final UserDiscordRepository userDiscordRepository;
    private final UserDashboardCachedQueryService cachedQueryService;
    private final EventPartnershipService eventPartnershipService;

    public UserDashboardService(
            UserRepository userRepository,
            UserDiscordRepository userDiscordRepository,
            UserDashboardCachedQueryService cachedQueryService,
            EventPartnershipService eventPartnershipService
    ) {
        this.userRepository = userRepository;
        this.userDiscordRepository = userDiscordRepository;
        this.cachedQueryService = cachedQueryService;
        this.eventPartnershipService = eventPartnershipService;
    }

    @Transactional(readOnly = true)
    public UserDashboardResponse getLoggedUserDashboard(Authentication authentication) {
        String normalizedEmail = authentication.getName().trim().toLowerCase();
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario autenticado nao encontrado"));
        UserDiscord userDiscord = userDiscordRepository.findByUser(user).orElse(null);
        long timeBucket = Instant.now().getEpochSecond() / 60;

        DashboardEventProjection nextManagedEvent = cachedQueryService.findNextManagedEvent(normalizedEmail, timeBucket);

        List<DashboardEventProjection> events = cachedQueryService.findDashboardEventsByType(true, timeBucket);
        List<DashboardEventProjection> meets = cachedQueryService.findDashboardEventsByType(false, timeBucket);
        Set<Integer> partnerEventIds = eventPartnershipService.findActivePartnerEventIds(
            Stream.concat(events.stream(), meets.stream()).map(DashboardEventProjection::getId).toList()
        );

        return new UserDashboardResponse(
                resolveDiscordStatus(userDiscord),
                resolveMyStatus(user),
                resolveServers(userDiscord),
                resolveAnalytics(authentication),
                toManagedEvent(nextManagedEvent),
                events.stream().map(event -> toEventItem(event, partnerEventIds.contains(event.getId()))).toList(),
                meets.stream().map(event -> toEventItem(event, partnerEventIds.contains(event.getId()))).toList()
        );
    }

    private DiscordConnectionStatus resolveDiscordStatus(UserDiscord discord) {
        if (discord == null) {
            return new DiscordConnectionStatus(false, null, null, null);
        }

        return new DiscordConnectionStatus(
                true,
                discord.getDiscordUserId(),
                discord.getUsername(),
                discord.getDisplayName()
        );
    }

    private MyStatus resolveMyStatus(User user) {
        Integer level = cachedQueryService.findUserLevel(user.getId(), 1);
        return new MyStatus(level, "", "");
    }

    private List<DashboardServerItem> resolveServers(UserDiscord userDiscord) {
        if (userDiscord == null) {
            return List.of();
        }

        return cachedQueryService.findServersByDiscordAdminId(userDiscord.getDiscordUserId()).stream()
                .map(server -> new DashboardServerItem(
                        server.getName(),
                        server.getUsersQuantity(),
                        "",
                        ""
                ))
                .toList();
    }

    private List<DashboardAnalyticsItem> resolveAnalytics(Authentication authentication) {
        if (!hasAnyPermission(authentication, "ROLE_ADMIN", "admin:full")) {
            return List.of();
        }

        GeneralDiscordMetricsProjection result = cachedQueryService.fetchGeneralDiscordMetrics();
        long activeServers = toLong(result.getActiveDiscordCommunities());
        long totalReached = toLong(result.getTotalMembersReachedActiveCommunities());

        return List.of(
                new DashboardAnalyticsItem("Servidores ativos", Long.toString(activeServers)),
                new DashboardAnalyticsItem("Total de furries alcançados", Long.toString(totalReached)),
                new DashboardAnalyticsItem("Status do bot", "Online")
        );
    }

    private DashboardManagedEvent toManagedEvent(DashboardEventProjection event) {
        if (event == null) {
            return null;
        }

        return new DashboardManagedEvent(
                event.getId(),
                event.getEventName(),
                resolvePoint(event),
                event.getStartingDatetime(),
                event.getEndingDatetime(),
                event.getEventLogoUrl()
        );
    }

    private DashboardEventItem toEventItem(DashboardEventProjection event, boolean partnerEvent) {
        return new DashboardEventItem(
                event.getId(),
                event.getEventName(),
                resolvePoint(event),
                event.getStartingDatetime(),
                event.getEndingDatetime(),
                event.getEventLogoUrl(),
                event.getIsEvent(),
                partnerEvent
        );
    }

    private String resolvePoint(DashboardEventProjection event) {
        if (event.getPointName() != null && !event.getPointName().isBlank()) {
            return event.getPointName();
        }
        return event.getAddress();
    }

    private boolean hasAnyPermission(Authentication authentication, String... permissions) {
        if (authentication == null || authentication.getAuthorities() == null) {
            return false;
        }

        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority -> {
                    for (String permission : permissions) {
                        if (permission.equals(authority)) return true;
                    }
                    return false;
                });
    }

    private long toLong(Number value) {
        return value == null ? 0L : value.longValue();
    }
}
