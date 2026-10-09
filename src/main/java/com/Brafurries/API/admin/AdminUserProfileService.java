package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminDtos.AdminPagination;
import com.Brafurries.API.admin.dto.AdminUserProfileDtos.AdminUserProfileResponse;
import com.Brafurries.API.admin.dto.AdminUserProfileDtos.CommunityMembership;
import com.Brafurries.API.admin.dto.AdminUserProfileDtos.FirstKnownCommunity;
import com.Brafurries.API.admin.dto.AdminUserProfileDtos.Identity;
import com.Brafurries.API.admin.dto.AdminUserProfileDtos.Moderation;
import com.Brafurries.API.admin.dto.AdminUserProfileDtos.ModerationRecord;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserBirthday;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserLocale;
import com.Brafurries.API.entity.user.UserTelegram;
import com.Brafurries.API.repository.user.UserBanRepository;
import com.Brafurries.API.repository.user.UserBirthdayRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserLocaleRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTelegramRepository;
import com.Brafurries.API.repository.user.UserWarningRepository;
import java.time.LocalDate;
import java.time.temporal.TemporalAccessor;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class AdminUserProfileService {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final UserDiscordRepository userDiscordRepository;
    private final UserTelegramRepository userTelegramRepository;
    private final UserCommunityStatusRepository userCommunityStatusRepository;
    private final UserWarningRepository userWarningRepository;
    private final UserBanRepository userBanRepository;
    private final UserBirthdayRepository userBirthdayRepository;
    private final UserLocaleRepository userLocaleRepository;
    private final AdminUsersService adminUsersService;

    public AdminUserProfileService(
        UserRepository userRepository,
        UserDiscordRepository userDiscordRepository,
        UserTelegramRepository userTelegramRepository,
        UserCommunityStatusRepository userCommunityStatusRepository,
        UserWarningRepository userWarningRepository,
        UserBanRepository userBanRepository,
        UserBirthdayRepository userBirthdayRepository,
        UserLocaleRepository userLocaleRepository,
        AdminUsersService adminUsersService
    ) {
        this.userRepository = userRepository;
        this.userDiscordRepository = userDiscordRepository;
        this.userTelegramRepository = userTelegramRepository;
        this.userCommunityStatusRepository = userCommunityStatusRepository;
        this.userWarningRepository = userWarningRepository;
        this.userBanRepository = userBanRepository;
        this.userBirthdayRepository = userBirthdayRepository;
        this.userLocaleRepository = userLocaleRepository;
        this.adminUsersService = adminUsersService;
    }

    public AdminUserProfileResponse getProfile(Integer userId, Integer page, Integer pageSize) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
        List<UserDiscord> discordAccounts = userDiscordRepository.findByUserIdIn(List.of(userId));
        UserDiscord discord = discordAccounts.size() == 1 ? discordAccounts.getFirst() : null;
        List<UserTelegram> telegramAccounts = userTelegramRepository.findByUserIdIn(List.of(userId));
        UserTelegram telegram = telegramAccounts.size() == 1 ? telegramAccounts.getFirst() : null;
        UserBirthday birthday = userBirthdayRepository.findAllByUserId(userId).stream().findFirst().orElse(null);
        List<UserLocale> locales = userLocaleRepository.findAllByUserIdOrderByIdAsc(userId);
        UserLocale locale = locales.size() == 1 ? locales.getFirst() : null;
        List<UserCommunityStatus> statuses = userCommunityStatusRepository.findByUser(user);
        List<CommunityMembership> communities = statuses.stream()
            .sorted(Comparator.comparing(UserCommunityStatus::getMemberSince, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(this::toCommunity)
            .toList();
        UserCommunityStatus firstStatus = userCommunityStatusRepository
            .findFirstByUserOrderByMemberSinceAscCommunityIdAsc(user)
            .orElse(null);
        FirstKnownCommunity first = firstStatus == null ? null : new FirstKnownCommunity(
            firstStatus.getCommunity().getId(), firstStatus.getCommunity().getName()
        );

        int safePage = Math.max(page == null ? 1 : page, 1);
        int safePageSize = Math.clamp(pageSize == null ? 20 : pageSize, 1, MAX_PAGE_SIZE);
        var records = userWarningRepository.findAdminProfileRecords(userId, LocalDate.now(), PageRequest.of(safePage - 1, safePageSize));
        long activeBans = userBanRepository.countActiveBansByUserId(userId, LocalDate.now());

        return new AdminUserProfileResponse(
            new Identity(
                user.getId(), firstText(user.getDisplayName(), user.getUsername(), user.getEmail(), "Usuário " + user.getId()),
                user.getUsername(), user.getEmail(), user.getProfileImageUrl(),
                discord == null ? null : firstText(discord.getDisplayName(), discord.getUsername()),
                telegram == null ? null : firstText(telegram.getDisplayName(), telegram.getUsername()),
                activeBans > 0 ? "banned" : "active",
                adminUsersService.resolvePrimaryRole(userId),
                birthday == null ? null : asText(birthday.getBirthDate()),
                birthday == null ? null : birthday.getPlus18(),
                birthday == null ? null : birthday.getVerified(),
                birthday == null ? null : birthday.getRegistered(),
                locale == null ? null : locale.getLocale().getId(),
                locale == null ? null : locale.getLocale().getLocaleAbbrev(),
                locale == null ? null : locale.getLocale().getLocaleName(),
                locales.size() > 1,
                discordAccounts.size() > 1,
                telegramAccounts.size() > 1
            ),
            firstStatus == null ? null : asText(firstStatus.getMemberSince()),
            first,
            communities,
            new Moderation(
                userWarningRepository.countByUserId(userId),
                userWarningRepository.countByUserIdAndExpiredFalse(userId),
                userBanRepository.countByUserId(userId),
                activeBans,
                userBanRepository.countExpiredBansByUserId(userId, LocalDate.now()),
                userBanRepository.countRevokedBansByUserId(userId),
                records.getContent().stream().map(record -> new ModerationRecord(
                    record.getId(), record.getRecordType(), record.getReason(), record.getModerator(), record.getCommunityName(),
                    asText(record.getOccurredAt()), isTrue(record.getActive()), record.getStatus(),
                    nullableBoolean(record.getCanAppeal()), asText(record.getValidUntil()), asText(record.getRegisteredAt()), asText(record.getRevokedAt()),
                    record.getRevokedById(), record.getRevokedByName(), record.getRevocationReason()
                )).toList(),
                new AdminPagination(safePage, safePageSize, records.getTotalElements(), records.getTotalPages())
            )
        );
    }

    private CommunityMembership toCommunity(UserCommunityStatus status) {
        CommunityDiscord discord = status.getCommunity().getDiscord();
        return new CommunityMembership(
            status.getId(),
            status.getCommunity().getId(), status.getCommunity().getName(), discord == null || discord.getGuildId() == null ? null : String.valueOf(discord.getGuildId()),
            asText(status.getMemberSince()), asText(status.getLastJoinDate()), asText(status.getApprovedAt()),
            status.getIsPresent(), asText(status.getLeftAt()),
            Boolean.TRUE.equals(status.getBanned()) ? "banned" : Boolean.TRUE.equals(status.getApproved()) ? "approved" : "pending",
            status.getApproved(), status.getBanned(),
            Boolean.TRUE.equals(status.getIsVip()), Boolean.TRUE.equals(status.getIsPartner()),
            status.getBirthdayMentionable(), status.getInviteLinkUsed(), status.getInvitedBy(),
            discord != null && Boolean.TRUE.equals(discord.getActive())
        );
    }

    private static boolean isTrue(Number value) {
        return value != null && value.intValue() != 0;
    }

    private static Boolean nullableBoolean(Number value) {
        return value == null ? null : value.intValue() != 0;
    }

    private static String asText(TemporalAccessor value) {
        return value == null ? null : value.toString();
    }

    private static String firstText(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return null;
    }
}
