package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminUserReliabilityDtos.AccountSummary;
import com.Brafurries.API.admin.dto.AdminUserReliabilityDtos.CommunitySummary;
import com.Brafurries.API.admin.dto.AdminUserReliabilityDtos.EventSummary;
import com.Brafurries.API.admin.dto.AdminUserReliabilityDtos.ModerationSummary;
import com.Brafurries.API.admin.dto.AdminUserReliabilityDtos.ReliabilitySummary;
import com.Brafurries.API.admin.dto.AdminUserReliabilityDtos.UserReliabilityResponse;
import com.Brafurries.API.admin.dto.AdminUserReliabilityDtos.UserSummary;
import com.Brafurries.API.admin.dto.AdminUserReliabilityDtos.WarningsByServer;
import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.entity.event.EventStaff;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserTelegram;
import com.Brafurries.API.event.EventPartnershipService;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.event.EventStaffRepository;
import com.Brafurries.API.repository.user.UserBanRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTelegramRepository;
import com.Brafurries.API.repository.user.UserWarningRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminUserReliabilityService {

    private final UserRepository userRepository;
    private final UserWarningRepository userWarningRepository;
    private final UserBanRepository userBanRepository;
    private final EventRepository eventRepository;
    private final EventStaffRepository eventStaffRepository;
    private final UserCommunityStatusRepository userCommunityStatusRepository;
    private final UserDiscordRepository userDiscordRepository;
    private final UserTelegramRepository userTelegramRepository;
    private final EventPartnershipService eventPartnershipService;

    public AdminUserReliabilityService(
        UserRepository userRepository,
        UserWarningRepository userWarningRepository,
        UserBanRepository userBanRepository,
        EventRepository eventRepository,
        EventStaffRepository eventStaffRepository,
        UserCommunityStatusRepository userCommunityStatusRepository,
        UserDiscordRepository userDiscordRepository,
        UserTelegramRepository userTelegramRepository,
        EventPartnershipService eventPartnershipService
    ) {
        this.userRepository = userRepository;
        this.userWarningRepository = userWarningRepository;
        this.userBanRepository = userBanRepository;
        this.eventRepository = eventRepository;
        this.eventStaffRepository = eventStaffRepository;
        this.userCommunityStatusRepository = userCommunityStatusRepository;
        this.userDiscordRepository = userDiscordRepository;
        this.userTelegramRepository = userTelegramRepository;
        this.eventPartnershipService = eventPartnershipService;
    }

    public UserReliabilityResponse getUserReliability(Integer id, String email) {
        User user = resolveUser(id, email);

        ModerationSummary moderation = buildModerationSummary(user);
        EventSummary events = buildEventSummary(user);
        CommunitySummary communities = buildCommunitySummary(user);
        AccountSummary account = buildAccountSummary(user);
        ReliabilitySummary reliability = buildReliabilitySummary(moderation, events, communities, account);

        return new UserReliabilityResponse(
            new UserSummary(user.getId(), user.getEmail(), user.getUsername(), user.getDisplayName()),
            moderation,
            events,
            communities,
            account,
            reliability
        );
    }

    private User resolveUser(Integer id, String email) {
        if (id == null && !hasText(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe id ou email");
        }
        if (id != null) {
            return userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
        }
        return userRepository.findByEmail(email.trim().toLowerCase())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
    }

    private ModerationSummary buildModerationSummary(User user) {
        long totalWarnings = userWarningRepository.countByUserId(user.getId());
        long activeWarnings = userWarningRepository.countByUserIdAndExpiredFalse(user.getId());
        long expiredWarnings = Math.max(totalWarnings - activeWarnings, 0);
        long warnedServers = userWarningRepository.countDistinctCommunitiesByUserId(user.getId());
        double averageWarningsPerWarnedServer = warnedServers == 0 ? 0D : round2((double) totalWarnings / warnedServers);

        long totalBans = userBanRepository.countByUserId(user.getId());
        long activeBans = userBanRepository.countActiveBansByUserId(user.getId(), LocalDate.now());
        long expiredBans = userBanRepository.countExpiredBansByUserId(user.getId(), LocalDate.now());
        long revokedBans = userBanRepository.countRevokedBansByUserId(user.getId());
        long bannedServers = userBanRepository.countDistinctCommunitiesByUserId(user.getId());

        List<WarningsByServer> warningsByServer = userWarningRepository.countWarningsByCommunity(user.getId())
            .stream()
            .map(item -> new WarningsByServer(
                item.getCommunityId(),
                item.getCommunityName(),
                toLong(item.getTotalWarnings()),
                toLong(item.getActiveWarnings()),
                toLong(item.getExpiredWarnings())
            ))
            .toList();

        return new ModerationSummary(
            totalWarnings,
            activeWarnings,
            expiredWarnings,
            warnedServers,
            averageWarningsPerWarnedServer,
            totalBans,
            activeBans,
            expiredBans,
            revokedBans,
            bannedServers,
            warningsByServer
        );
    }

    private EventSummary buildEventSummary(User user) {
        List<Event> ownedEvents = eventRepository.findByHostUserId(user.getId());
        long ownedApprovedEvents = ownedEvents.stream().filter(event -> Boolean.TRUE.equals(event.getApproved())).count();
        long ownedPendingEvents = ownedEvents.stream().filter(event -> event.getApproved() == null).count();
        long ownedRejectedEvents = ownedEvents.stream().filter(event -> Boolean.FALSE.equals(event.getApproved())).count();
        long ownedPartnerEvents = eventPartnershipService.findActivePartnerEventIds(
            ownedEvents.stream().map(Event::getId).toList()
        ).size();

        List<EventStaff> staffEntries = eventStaffRepository.findByUserId(user.getId());
        long staffEvents = staffEntries.stream()
            .filter(staff -> staff.getEvent() != null
                && staff.getEvent().getHostUser() != null
                && !user.getId().equals(staff.getEvent().getHostUser().getId()))
            .map(staff -> staff.getEvent().getId())
            .distinct()
            .count();
        long staffEventsWithManageStaff = staffEntries.stream().filter(staff -> Boolean.TRUE.equals(staff.getMngStaff())).count();
        long staffEventsWithEditEvent = staffEntries.stream().filter(staff -> Boolean.TRUE.equals(staff.getEditEvent())).count();
        long staffEventsWithManageAgenda = staffEntries.stream().filter(staff -> Boolean.TRUE.equals(staff.getMngAgenda())).count();

        return new EventSummary(
            ownedEvents.size() + staffEvents,
            ownedEvents.size(),
            ownedApprovedEvents,
            ownedPendingEvents,
            ownedRejectedEvents,
            ownedPartnerEvents,
            staffEvents,
            staffEventsWithManageStaff,
            staffEventsWithEditEvent,
            staffEventsWithManageAgenda
        );
    }

    private CommunitySummary buildCommunitySummary(User user) {
        return new CommunitySummary(
            userCommunityStatusRepository.countByUserId(user.getId()),
            userCommunityStatusRepository.countByUserIdAndApprovedTrue(user.getId()),
            userCommunityStatusRepository.countByUserIdAndBannedTrue(user.getId()),
            userCommunityStatusRepository.countByUserIdAndIsVipTrue(user.getId()),
            userCommunityStatusRepository.countByUserIdAndIsPartnerTrue(user.getId())
        );
    }

    private AccountSummary buildAccountSummary(User user) {
        List<UserDiscord> discordAccounts = userDiscordRepository.findByUserIdIn(List.of(user.getId()));
        UserDiscord discord = discordAccounts.size() == 1 ? discordAccounts.getFirst() : null;
        List<UserTelegram> telegramAccounts = userTelegramRepository.findByUserIdIn(List.of(user.getId()));
        UserTelegram telegram = telegramAccounts.size() == 1 ? telegramAccounts.getFirst() : null;
        return new AccountSummary(
            !discordAccounts.isEmpty(),
            discord != null ? discord.getDiscordUserId() : null,
            discord != null ? discord.getUsername() : null,
            !telegramAccounts.isEmpty(),
            telegram != null ? telegram.getTelegramUserId() : null,
            telegram != null ? telegram.getUsername() : null,
            discordAccounts.size() > 1,
            telegramAccounts.size() > 1
        );
    }

    private ReliabilitySummary buildReliabilitySummary(
        ModerationSummary moderation,
        EventSummary events,
        CommunitySummary communities,
        AccountSummary account
    ) {
        int score = 100;
        score -= moderation.activeWarnings() * 12;
        score -= moderation.expiredWarnings() * 4;
        score -= moderation.activeBans() * 30;
        score -= moderation.expiredBans() * 10;
        score -= communities.bannedMemberships() * 10;
        score += events.ownedApprovedEvents() * 5;
        score += events.staffEvents() * 2;
        if (account.linkedDiscord()) score += 3;
        if (account.linkedTelegram()) score += 2;
        score = Math.max(0, Math.min(100, score));

        List<String> signals = new ArrayList<>();
        if (moderation.activeBans() > 0) signals.add("possui_banimento_ativo");
        if (moderation.activeWarnings() >= 3) signals.add("muitos_warns_ativos");
        if (moderation.warnedServers() >= 2) signals.add("warns_em_multiplos_servidores");
        if (events.ownedApprovedEvents() > 0) signals.add("ja_gerenciou_eventos_aprovados");
        if (events.staffEvents() > 0) signals.add("atua_como_staff_em_eventos");
        if (communities.partnerMemberships() > 0) signals.add("membro_partner_em_servidor");
        if (!account.linkedDiscord()) signals.add("discord_nao_vinculado");

        return new ReliabilitySummary(score, reliabilityLevel(score), List.copyOf(signals));
    }

    private String reliabilityLevel(int score) {
        if (score >= 80) return "alta";
        if (score >= 60) return "media";
        if (score >= 40) return "baixa";
        return "critica";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private long toLong(Number value) {
        return value == null ? 0L : value.longValue();
    }

    private double round2(double value) {
        return Math.round(value * 100D) / 100D;
    }
}
