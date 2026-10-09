package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminDiscordMemberDtos.MemberState;
import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.repository.community.CommunityRepository;
import com.Brafurries.API.repository.user.UserCommunityStatusRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class AdminDiscordMemberService {

    private final UserRepository users;
    private final CommunityRepository communities;
    private final UserCommunityStatusRepository memberships;
    private final UserDiscordRepository discordAccounts;
    private final AdminDiscordMemberClient client;

    public AdminDiscordMemberService(
        UserRepository users,
        CommunityRepository communities,
        UserCommunityStatusRepository memberships,
        UserDiscordRepository discordAccounts,
        AdminDiscordMemberClient client
    ) {
        this.users = users;
        this.communities = communities;
        this.memberships = memberships;
        this.discordAccounts = discordAccounts;
        this.client = client;
    }

    public MemberState getMember(Integer userId, Integer communityId) {
        if (!users.existsById(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário não encontrado");
        }
        Community community = communities.findById(communityId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Comunidade não encontrada"));
        if (memberships.findAllByUserIdAndCommunityIdOrderByMemberSinceAscIdAsc(userId, communityId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário sem vínculo conhecido com esta comunidade");
        }
        CommunityDiscord discord = community.getDiscord();
        if (discord == null || discord.getGuildId() == null || !Boolean.TRUE.equals(discord.getActive())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Comunidade sem integração Discord ativa");
        }

        List<UserDiscord> accounts = discordAccounts.findByUserIdIn(List.of(userId));
        if (accounts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário sem Discord vinculado");
        }
        if (accounts.size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Usuário possui vínculos Discord legados ambíguos");
        }
        return client.getMember(discord.getGuildId(), accounts.getFirst().getDiscordUserId());
    }
}
