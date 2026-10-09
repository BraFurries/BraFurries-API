package com.Brafurries.API.admin;

import static com.Brafurries.API.config.CacheConfig.ADMIN_METRICS;
import static com.Brafurries.API.config.CacheConfig.ADMIN_OVERVIEW;
import static com.Brafurries.API.config.CacheConfig.ADMIN_SESSION;

import com.Brafurries.API.admin.dto.AdminDtos.AdminMetricPoint;
import com.Brafurries.API.admin.dto.AdminDtos.AdminMetrics;
import com.Brafurries.API.admin.dto.AdminDtos.AdminMetricsSummary;
import com.Brafurries.API.admin.dto.AdminDtos.AdminOverview;
import com.Brafurries.API.admin.dto.AdminDtos.AdminOverviewMetrics;
import com.Brafurries.API.admin.dto.AdminDtos.AdminPlatformDistribution;
import com.Brafurries.API.admin.dto.AdminDtos.AdminRecentActivity;
import com.Brafurries.API.admin.dto.AdminDtos.AdminSession;
import com.Brafurries.API.admin.dto.AdminDtos.AdminTopCommand;
import com.Brafurries.API.repository.api.ApiRolePermissionRepository;
import com.Brafurries.API.repository.api.ApiUserRoleRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.misc.ConfigCommandUseRepository;
import com.Brafurries.API.repository.misc.ConfigCommandUseRepository.TopCommandProjection;
import com.Brafurries.API.repository.misc.PartnerRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTelegramRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class AdminDashboardService {

    private final UserRepository userRepository;
    private final UserDiscordRepository userDiscordRepository;
    private final UserTelegramRepository userTelegramRepository;
    private final PartnerRepository partnerRepository;
    private final CommunityDiscordRepository communityDiscordRepository;
    private final ConfigCommandUseRepository configCommandUseRepository;
    private final ApiUserRoleRepository apiUserRoleRepository;
    private final ApiRolePermissionRepository apiRolePermissionRepository;
    private final AdminBotStatusService botStatusService;

    public AdminDashboardService(
        UserRepository userRepository,
        UserDiscordRepository userDiscordRepository,
        UserTelegramRepository userTelegramRepository,
        PartnerRepository partnerRepository,
        CommunityDiscordRepository communityDiscordRepository,
        ConfigCommandUseRepository configCommandUseRepository,
        ApiUserRoleRepository apiUserRoleRepository,
        ApiRolePermissionRepository apiRolePermissionRepository,
        AdminBotStatusService botStatusService
    ) {
        this.userRepository = userRepository;
        this.userDiscordRepository = userDiscordRepository;
        this.userTelegramRepository = userTelegramRepository;
        this.partnerRepository = partnerRepository;
        this.communityDiscordRepository = communityDiscordRepository;
        this.configCommandUseRepository = configCommandUseRepository;
        this.apiUserRoleRepository = apiUserRoleRepository;
        this.apiRolePermissionRepository = apiRolePermissionRepository;
        this.botStatusService = botStatusService;
    }

    @Cacheable(cacheNames = ADMIN_SESSION, key = "#authentication.name")
    public AdminSession getSession(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Nao autenticado");
        }
        var user = userRepository.findByEmail(authentication.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario autenticado nao encontrado"));
        List<String> permissions = apiRolePermissionRepository.findPermissionNamesByUserId(user.getId());
        List<String> roles = apiUserRoleRepository.findRoleNamesByUserId(user.getId())
            .stream()
            .map(role -> role.toLowerCase(Locale.ROOT))
            .toList();
        String role = roles.contains("admin") ? "admin" : "moderator";

        return new AdminSession(
            user.getId(),
            coalesce(user.getDisplayName(), user.getUsername(), user.getEmail()),
            role,
            permissions
        );
    }

    @Cacheable(cacheNames = ADMIN_OVERVIEW, key = "'overview'")
    public AdminOverview getOverview() {
        var discordMetrics = communityDiscordRepository.fetchGeneralDiscordMetrics();
        var botStatus = botStatusService.getStatus();
        long activePartners = partnerRepository.countByStatusIgnoreCase("active");

        AdminOverviewMetrics metrics = new AdminOverviewMetrics(
            userRepository.count(),
            partnerRepository.countByStatusIgnoreCase("pending"),
            activePartners,
            0,
            botStatus.status(),
            botStatus.pingMs() == null ? 0 : Math.round(botStatus.pingMs()),
            safeLong(discordMetrics.getActiveDiscordCommunities()),
            0,
            safeLong(discordMetrics.getTotalMembersReachedActiveCommunities())
        );

        String systemStatus = "online".equals(botStatus.status()) || "degraded".equals(botStatus.status())
            ? "operational"
            : "degraded";

        return new AdminOverview(systemStatus, metrics, List.of());
    }

    @Cacheable(cacheNames = ADMIN_METRICS, key = "#period == null ? '7d' : #period.trim().toLowerCase()")
    public AdminMetrics getMetrics(String period) {
        LocalDateTime start = startForPeriod(period);
        LocalDateTime previousStart = start.minusSeconds(java.time.Duration.between(start, LocalDateTime.now()).toSeconds());

        long commands = configCommandUseRepository.countByDtmLastUsageGreaterThanEqual(start);
        long previousCommands = configCommandUseRepository.countByDtmLastUsageGreaterThanEqual(previousStart) - commands;
        double commandsGrowth = previousCommands <= 0 ? 0 : ((commands - previousCommands) * 100.0) / previousCommands;

        long totalUsers = userRepository.count();
        long discordLinked = userDiscordRepository.count();
        long telegramLinked = userTelegramRepository.count();
        var discordMetrics = communityDiscordRepository.fetchGeneralDiscordMetrics();
        long siteMembers = Math.max(totalUsers - Math.max(discordLinked, telegramLinked), 0);

        AdminMetricsSummary summary = new AdminMetricsSummary(
            commands,
            Math.round(commandsGrowth * 100.0) / 100.0,
            percentage(discordLinked, totalUsers),
            percentage(telegramLinked, totalUsers),
            0,
            0
        );

        List<AdminMetricPoint> communityGrowth = List.of(
            new AdminMetricPoint(LocalDate.now().toString(), safeLong(discordMetrics.getActiveDiscordCommunities()))
        );

        long totalPlatformMembers = discordLinked + telegramLinked + siteMembers;
        List<AdminPlatformDistribution> platformDistribution = List.of(
            new AdminPlatformDistribution("discord", discordLinked, percentage(discordLinked, totalPlatformMembers)),
            new AdminPlatformDistribution("telegram", telegramLinked, percentage(telegramLinked, totalPlatformMembers)),
            new AdminPlatformDistribution("site", siteMembers, percentage(siteMembers, totalPlatformMembers))
        );

        List<AdminTopCommand> topCommands = configCommandUseRepository.findTopCommandsSince(start, PageRequest.of(0, 10))
            .stream()
            .map(this::toTopCommand)
            .toList();

        return new AdminMetrics(summary, communityGrowth, platformDistribution, topCommands);
    }

    private AdminTopCommand toTopCommand(TopCommandProjection projection) {
        return new AdminTopCommand(
            projection.getCommand(),
            safeLong(projection.getUses()),
            "Comando /" + projection.getCommand()
        );
    }

    private LocalDateTime startForPeriod(String period) {
        return switch (period == null ? "7d" : period.trim().toLowerCase(Locale.ROOT)) {
            case "30d" -> LocalDateTime.now().minusDays(30);
            case "year" -> LocalDateTime.now().minusYears(1);
            default -> LocalDateTime.now().minusDays(7);
        };
    }

    private double percentage(long value, long total) {
        return total <= 0 ? 0 : Math.round((value * 10000.0) / total) / 100.0;
    }

    private long safeLong(Number value) {
        return value == null ? 0 : value.longValue();
    }

    private String coalesce(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
