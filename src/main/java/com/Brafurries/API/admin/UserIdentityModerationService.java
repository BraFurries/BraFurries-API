package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.UserIdentityDtos.BanHistory;
import com.Brafurries.API.admin.dto.UserIdentityDtos.ModerationHistoryResponse;
import com.Brafurries.API.admin.dto.UserIdentityDtos.NoteHistory;
import com.Brafurries.API.admin.dto.UserIdentityDtos.UserIdentityResponse;
import com.Brafurries.API.admin.dto.UserIdentityDtos.WarningHistory;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserBan;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserNote;
import com.Brafurries.API.entity.user.UserWarning;
import com.Brafurries.API.repository.user.UserBanRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserNoteRepository;
import com.Brafurries.API.repository.user.UserWarningRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserIdentityModerationService {

    private final ConfirmedIdentityClusterService clusterService;
    private final UserIdentityLinkService identityLinkService;
    private final UserDiscordRepository userDiscordRepository;
    private final UserWarningRepository warningRepository;
    private final UserBanRepository banRepository;
    private final UserNoteRepository noteRepository;

    public UserIdentityModerationService(
        ConfirmedIdentityClusterService clusterService,
        UserIdentityLinkService identityLinkService,
        UserDiscordRepository userDiscordRepository,
        UserWarningRepository warningRepository,
        UserBanRepository banRepository,
        UserNoteRepository noteRepository
    ) {
        this.clusterService = clusterService;
        this.identityLinkService = identityLinkService;
        this.userDiscordRepository = userDiscordRepository;
        this.warningRepository = warningRepository;
        this.banRepository = banRepository;
        this.noteRepository = noteRepository;
    }

    public ModerationHistoryResponse getHistory(Integer requestedUserId, Integer communityId) {
        Set<Integer> confirmedIds = clusterService.resolveConfirmedUserIds(requestedUserId);
        List<Integer> orderedConfirmedIds = confirmedIds.stream().sorted().toList();
        UserIdentityResponse identity = identityLinkService.getIdentity(requestedUserId, confirmedIds);

        Map<Integer, List<String>> discordIds = userDiscordRepository.findByUserIdIn(confirmedIds).stream()
            .collect(Collectors.groupingBy(
                link -> link.getUser().getId(),
                Collectors.mapping(link -> String.valueOf(link.getDiscordUserId()), Collectors.toList())
            ));
        discordIds.values().forEach(ids -> ids.sort(String::compareTo));

        List<WarningHistory> warnings = warningRepository
            .findByUserIdInAndCommunityId(confirmedIds, communityId).stream()
            .sorted(Comparator.comparing(UserWarning::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(UserWarning::getId, Comparator.reverseOrder()))
            .map(warning -> new WarningHistory(
                warning.getId(), warning.getUser().getId(), sourceDiscordIds(discordIds, warning.getUser()),
                warning.getCommunity().getId(), warning.getCommunity().getName(), warning.getDate(), warning.getReason(),
                Boolean.TRUE.equals(warning.getExpired()), warning.getAppliedBy().getId(), displayName(warning.getAppliedBy())
            ))
            .toList();

        List<BanHistory> bans = banRepository.findByUserIdInAndCommunityId(confirmedIds, communityId).stream()
            .sorted(Comparator.comparing(UserBan::getDate, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(UserBan::getId, Comparator.reverseOrder()))
            .map(ban -> new BanHistory(
                ban.getId(), ban.getUser().getId(), sourceDiscordIds(discordIds, ban.getUser()),
                ban.getCommunity().getId(), ban.getCommunity().getName(), ban.getDate(), ban.getReason(), ban.getCanAppeal(),
                ban.getValidUntil(), ban.getAppliedBy() == null ? null : ban.getAppliedBy().getId(), displayName(ban.getAppliedBy()),
                ban.getRegisteredAt(), ban.getRevokedAt(), ban.getRevokedBy() == null ? null : ban.getRevokedBy().getId(),
                ban.getRevocationReason()
            ))
            .toList();

        // Notes have no community_id in the shared schema, so they are not
        // propagated across identities as if they were tenant-scoped records.
        List<NoteHistory> notes = noteRepository.findByUserIdIn(List.of(requestedUserId)).stream()
            .sorted(Comparator.comparing(UserNote::getId).reversed())
            .map(note -> new NoteHistory(
                note.getId(), note.getUser().getId(), sourceDiscordIds(discordIds, note.getUser()), note.getNote(),
                note.getAuthorUser().getId(), displayName(note.getAuthorUser())
            ))
            .toList();

        return new ModerationHistoryResponse(
            requestedUserId,
            communityId,
            orderedConfirmedIds,
            warnings,
            bans,
            notes,
            identity.suspectedLinks()
        );
    }

    private List<String> sourceDiscordIds(Map<Integer, List<String>> discordIds, User user) {
        return List.copyOf(discordIds.getOrDefault(user.getId(), new ArrayList<>()));
    }

    private String displayName(User user) {
        if (user == null) {
            return null;
        }
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            return user.getUsername();
        }
        return user.getEmail();
    }
}
