package com.Brafurries.API.internal;

import com.Brafurries.API.admin.ConfirmedIdentityClusterService;
import com.Brafurries.API.internal.dto.InternalIdentityDtos.CommunityModerationSummary;
import com.Brafurries.API.internal.dto.InternalIdentityDtos.ConfirmedIdentity;
import com.Brafurries.API.internal.dto.InternalIdentityDtos.InternalIdentityResponse;
import com.Brafurries.API.repository.user.UserBanRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserWarningRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class InternalIdentityService {

    private final ConfirmedIdentityClusterService clusterService;
    private final UserRepository userRepository;
    private final UserDiscordRepository userDiscordRepository;
    private final UserWarningRepository warningRepository;
    private final UserBanRepository banRepository;

    public InternalIdentityService(
        ConfirmedIdentityClusterService clusterService,
        UserRepository userRepository,
        UserDiscordRepository userDiscordRepository,
        UserWarningRepository warningRepository,
        UserBanRepository banRepository
    ) {
        this.clusterService = clusterService;
        this.userRepository = userRepository;
        this.userDiscordRepository = userDiscordRepository;
        this.warningRepository = warningRepository;
        this.banRepository = banRepository;
    }

    public InternalIdentityResponse getByUserId(Integer userId, Integer communityId) {
        if (!userRepository.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado");
        }
        return resolve(userId, communityId);
    }

    public InternalIdentityResponse getByDiscordUserId(Long discordUserId, Integer communityId) {
        Integer userId = userDiscordRepository.findByDiscordUserId(discordUserId)
            .map(link -> link.getUser().getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Identidade Discord não encontrada"));
        return resolve(userId, communityId);
    }

    private InternalIdentityResponse resolve(Integer requestedUserId, Integer communityId) {
        Set<Integer> confirmedUserIds = clusterService.resolveConfirmedUserIds(requestedUserId);
        Map<Integer, List<String>> discordByUser = userDiscordRepository.findByUserIdIn(confirmedUserIds).stream()
            .collect(Collectors.groupingBy(
                link -> link.getUser().getId(),
                Collectors.mapping(
                    link -> String.valueOf(link.getDiscordUserId()),
                    Collectors.collectingAndThen(Collectors.toList(), ids -> ids.stream().sorted().toList())
                )
            ));

        List<ConfirmedIdentity> identities = confirmedUserIds.stream()
            .sorted()
            .map(userId -> new ConfirmedIdentity(userId, discordByUser.getOrDefault(userId, List.of())))
            .toList();

        CommunityModerationSummary moderation = communityId == null ? null : new CommunityModerationSummary(
            communityId,
            warningRepository.countByUserIdInAndCommunityId(confirmedUserIds, communityId),
            warningRepository.countByUserIdInAndCommunityIdAndExpiredFalse(confirmedUserIds, communityId),
            banRepository.countActiveBansByUserIdsAndCommunityId(confirmedUserIds, communityId, LocalDate.now())
        );

        return new InternalIdentityResponse(
            requestedUserId,
            identities,
            Math.max(0, identities.size() - 1),
            moderation
        );
    }
}
