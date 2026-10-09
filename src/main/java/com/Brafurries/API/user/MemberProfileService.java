package com.Brafurries.API.user;

import static com.Brafurries.API.user.dto.MemberProfileDtos.*;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserTelegram;
import com.Brafurries.API.repository.user.UserBirthdayRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTelegramRepository;
import com.Brafurries.API.user.dto.MemberDataState;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class MemberProfileService {
    private final UserRepository userRepository;
    private final UserBirthdayRepository userBirthdayRepository;
    private final UserDiscordRepository userDiscordRepository;
    private final UserTelegramRepository userTelegramRepository;
    private final UserCommunityStatusRepository userCommunityStatusRepository;

    public MemberProfileService(
        UserRepository userRepository,
        UserBirthdayRepository userBirthdayRepository,
        UserDiscordRepository userDiscordRepository,
        UserTelegramRepository userTelegramRepository,
        UserCommunityStatusRepository userCommunityStatusRepository
    ) {
        this.userRepository = userRepository;
        this.userBirthdayRepository = userBirthdayRepository;
        this.userDiscordRepository = userDiscordRepository;
        this.userTelegramRepository = userTelegramRepository;
        this.userCommunityStatusRepository = userCommunityStatusRepository;
    }

    @Transactional(readOnly = true)
    public MemberProfileResponse getLoggedUserProfile(String principal) {
        User user = findAuthenticatedUser(principal);
        List<UserCommunityStatus> memberships = userCommunityStatusRepository.findByUser(user).stream()
            .filter(status -> status.getCommunity().getDiscord() != null)
            .filter(status -> Boolean.TRUE.equals(status.getCommunity().getDiscord().getActive()))
            .toList();
        Map<Integer, List<UserCommunityStatus>> membershipsByCommunity = memberships.stream().collect(
            java.util.stream.Collectors.groupingBy(
                status -> status.getCommunity().getId(),
                LinkedHashMap::new,
                java.util.stream.Collectors.toList()
            )
        );

        List<MemberCommunitySummary> communities = membershipsByCommunity.values().stream()
            .map(this::toCommunitySummary)
            .sorted(Comparator.comparing(MemberCommunitySummary::communityName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER))
                .thenComparing(MemberCommunitySummary::communityId))
            .toList();

        return new MemberProfileResponse(
            user.getId(),
            user.getDisplayName(),
            user.getUsername(),
            user.getProfileImageUrl(),
            onlyBirthday(user.getId()),
            discordAccount(user.getId()),
            telegramAccount(user.getId()),
            membershipsByCommunity.size(),
            firstKnownCommunity(membershipsByCommunity),
            communities
        );
    }

    private User findAuthenticatedUser(String principal) {
        String normalizedEmail = principal == null ? "" : principal.trim().toLowerCase(Locale.ROOT);
        return userRepository.findByEmail(normalizedEmail)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado"));
    }

    private java.time.LocalDate onlyBirthday(Integer userId) {
        var birthdays = userBirthdayRepository.findAllByUserId(userId);
        return birthdays.size() == 1 ? birthdays.getFirst().getBirthDate() : null;
    }

    private ExternalAccountState discordAccount(Integer userId) {
        List<UserDiscord> records = userDiscordRepository.findAllByUserIdOrderByIdAsc(userId);
        return records.size() == 1
            ? new ExternalAccountState(MemberDataState.AVAILABLE, 1, records.getFirst().getUsername(), records.getFirst().getDisplayName())
            : new ExternalAccountState(state(records.size()), records.size(), null, null);
    }

    private ExternalAccountState telegramAccount(Integer userId) {
        List<UserTelegram> records = userTelegramRepository.findAllByUserIdOrderByIdAsc(userId);
        return records.size() == 1
            ? new ExternalAccountState(MemberDataState.AVAILABLE, 1, records.getFirst().getUsername(), records.getFirst().getDisplayName())
            : new ExternalAccountState(state(records.size()), records.size(), null, null);
    }

    private MemberCommunitySummary toCommunitySummary(List<UserCommunityStatus> records) {
        Community community = records.getFirst().getCommunity();
        String guildId = community.getDiscord() == null ? null : String.valueOf(community.getDiscord().getGuildId());
        return new MemberCommunitySummary(community.getId(), community.getName(), guildId, state(records.size()), records.size());
    }

    private FirstKnownCommunity firstKnownCommunity(Map<Integer, List<UserCommunityStatus>> membershipsByCommunity) {
        if (membershipsByCommunity.isEmpty()) {
            return new FirstKnownCommunity(MemberDataState.NOT_FOUND, null, null, null);
        }
        if (membershipsByCommunity.values().stream().anyMatch(records -> records.size() != 1)) {
            return new FirstKnownCommunity(MemberDataState.AMBIGUOUS, null, null, null);
        }

        List<UserCommunityStatus> records = membershipsByCommunity.values().stream().map(List::getFirst).toList();
        LocalDateTime earliest = records.stream().map(UserCommunityStatus::getMemberSince).min(LocalDateTime::compareTo).orElse(null);
        List<UserCommunityStatus> earliestRecords = records.stream().filter(record -> record.getMemberSince().equals(earliest)).toList();
        if (earliestRecords.size() != 1) {
            return new FirstKnownCommunity(MemberDataState.AMBIGUOUS, null, null, null);
        }
        Community community = earliestRecords.getFirst().getCommunity();
        return new FirstKnownCommunity(MemberDataState.AVAILABLE, community.getId(), community.getName(), earliest);
    }

    private MemberDataState state(int recordCount) {
        return recordCount == 0 ? MemberDataState.NOT_FOUND : recordCount == 1 ? MemberDataState.AVAILABLE : MemberDataState.AMBIGUOUS;
    }
}
