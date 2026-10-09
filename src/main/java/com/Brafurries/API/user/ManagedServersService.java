package com.Brafurries.API.user;

import com.Brafurries.API.user.dto.UserManagedServerDtos.ManagedServerResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ManagedServersService {

    private final ManagedServersDiscordIdentityService discordIdentityService;
    private final CoddyManagedGuildsClient coddyManagedGuildsClient;

    public ManagedServersService(
        ManagedServersDiscordIdentityService discordIdentityService,
        CoddyManagedGuildsClient coddyManagedGuildsClient
    ) {
        this.discordIdentityService = discordIdentityService;
        this.coddyManagedGuildsClient = coddyManagedGuildsClient;
    }

    public List<ManagedServerResponse> getLoggedUserManagedServers(Authentication authentication) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário não autenticado");
        }

        Long discordUserId = discordIdentityService.findDiscordUserId(authentication.getName().trim().toLowerCase());
        if (discordUserId == null) {
            return List.of();
        }

        return coddyManagedGuildsClient.findManagedGuilds(discordUserId).stream()
            .filter(guild -> guild.guildId() != null && guild.guildId().matches("[1-9]\\d*") && guild.name() != null && !guild.name().isBlank())
            .map(guild -> new ManagedServerResponse(guild.guildId(), guild.name(), guild.memberCount(), true, guild.iconUrl()))
            .toList();
    }
}
