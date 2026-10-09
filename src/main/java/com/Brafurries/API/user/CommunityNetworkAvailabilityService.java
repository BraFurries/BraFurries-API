package com.Brafurries.API.user;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.portaria.PortariaBaseConfigRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CommunityNetworkAvailabilityService {
    private final CommunityDiscordRepository discordRepository;
    private final PortariaBaseConfigRepository portariaRepository;

    public CommunityNetworkAvailabilityService(
        CommunityDiscordRepository discordRepository,
        PortariaBaseConfigRepository portariaRepository
    ) {
        this.discordRepository = discordRepository;
        this.portariaRepository = portariaRepository;
    }

    @Transactional(readOnly = true)
    public CommunityDiscord requireActiveDiscord(Community community) {
        return discordRepository.findByCommunity(community)
            .filter(link -> Boolean.TRUE.equals(link.getActive()))
            .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Community não encontrada"
            ));
    }

    @Transactional(readOnly = true)
    public boolean isAvailable(Community community) {
        return community != null
            && community.getId() != null
            && discordRepository.existsByCommunityIdAndActiveTrue(community.getId());
    }

    @Transactional(readOnly = true)
    public boolean isPortariaEnabled(Community community) {
        return isPortariaEnabled(requireActiveDiscord(community).getGuildId());
    }

    @Transactional(readOnly = true)
    public boolean isPortariaEnabled(Long guildId) {
        return portariaRepository.findByServerGuildId(guildId)
            .map(config -> Boolean.TRUE.equals(config.getPortariaEnabled()))
            .orElse(false);
    }
}
