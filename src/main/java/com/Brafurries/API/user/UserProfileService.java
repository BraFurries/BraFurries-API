package com.Brafurries.API.user;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserCommunityStatus;
import com.Brafurries.API.entity.user.UserEconomy;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserEconomyRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.user.dto.UserProfileDtos.UserProfileResponse;
import com.Brafurries.API.user.dto.UserProfileDtos.UserServerData;
import com.Brafurries.API.user.dto.UserProfileDtos.UserServerSummary;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class UserProfileService {

    private static final long MAIN_SERVER_GUILD_ID = 753321055682035712L;

    private final UserRepository userRepository;
    private final UserCommunityStatusRepository userCommunityStatusRepository;
    private final UserEconomyRepository userEconomyRepository;
    private final CommunityDiscordRepository communityDiscordRepository;

    public UserProfileService(
        UserRepository userRepository,
        UserCommunityStatusRepository userCommunityStatusRepository,
        UserEconomyRepository userEconomyRepository,
        CommunityDiscordRepository communityDiscordRepository
    ) {
        this.userRepository = userRepository;
        this.userCommunityStatusRepository = userCommunityStatusRepository;
        this.userEconomyRepository = userEconomyRepository;
        this.communityDiscordRepository = communityDiscordRepository;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getLoggedUserProfile(String email, Long guildId) {
        User user = userRepository.findByEmail(email.trim().toLowerCase())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário autenticado não encontrado"));

        List<UserCommunityStatus> allMemberships = userCommunityStatusRepository.findByUser(user);
        Set<Integer> activeCommunityIds = allMemberships.isEmpty()
            ? Set.of()
            : communityDiscordRepository.findByCommunityIds(
                    allMemberships.stream()
                        .map(status -> status.getCommunity().getId())
                        .collect(Collectors.toSet())
                ).stream()
                .filter(link -> Boolean.TRUE.equals(link.getActive()))
                .map(link -> link.getCommunity().getId())
                .collect(Collectors.toSet());
        List<UserCommunityStatus> memberships = allMemberships.stream()
            .filter(status -> activeCommunityIds.contains(status.getCommunity().getId()))
            .toList();

        CommunityDiscord selectedServer = resolveServer(guildId);
        UserServerData selectedServerData = buildServerData(user, selectedServer);

        List<UserServerSummary> servers = memberships.stream()
            .map(m -> new UserServerSummary(
                m.getCommunity().getId(),
                m.getCommunity().getName(),
                m.getCommunity().getDiscord() != null ? m.getCommunity().getDiscord().getGuildId() : null
            ))
            .toList();

        return new UserProfileResponse(
            user.getId(),
            user.getDisplayName(),
            user.getUsername(),
            user.getEmail(),
            user.getProfileImageUrl(),
            servers,
            selectedServerData
        );
    }

    private CommunityDiscord resolveServer(Long guildId) {
        if (guildId != null) {
            return communityDiscordRepository.findByGuildId(guildId)
                .filter(server -> Boolean.TRUE.equals(server.getActive()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Servidor informado não encontrado"));
        }

        return communityDiscordRepository.findByGuildId(MAIN_SERVER_GUILD_ID)
            .filter(server -> Boolean.TRUE.equals(server.getActive()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Servidor principal da BraFurries não encontrado"));
    }

    private UserServerData buildServerData(User user, CommunityDiscord server) {
        Community community = server.getCommunity();

        UserCommunityStatus membership = userCommunityStatusRepository.findByUserAndCommunity(user, community)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não está presente no servidor informado"));

        Integer bankBalance = userEconomyRepository.findByUserAndCommunityDiscord(user, server)
            .map(UserEconomy::getBankBalance)
            .orElse(0);

        return new UserServerData(
            community.getId(),
            community.getName(),
            server.getGuildId(),
            membership.getMemberSince(),
            membership.getLastJoinDate(),
            membership.getApproved(),
            membership.getApprovedAt(),
            membership.getIsVip(),
            membership.getIsPartner(),
            membership.getBanned(),
            membership.getBirthdayMentionable(),
            bankBalance
        );
    }
}
