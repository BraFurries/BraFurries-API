package com.Brafurries.API.user;

import com.Brafurries.API.user.dto.GuildManagementDtos.GuildResources;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GuildManagementAccessService {
    private final ManagedServersDiscordIdentityService identityService;
    private final CoddyGuildManagementClient coddyClient;

    public GuildManagementAccessService(ManagedServersDiscordIdentityService identityService,
                                        CoddyGuildManagementClient coddyClient) {
        this.identityService = identityService;
        this.coddyClient = coddyClient;
    }

    public AuthorizedGuild authorize(Authentication authentication, String guildId) {
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário não autenticado");
        }
        if (guildId == null || !guildId.matches("[1-9]\\d*")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Servidor não encontrado");
        }
        Long discordUserId = identityService.findDiscordUserId(authentication.getName().trim().toLowerCase());
        if (discordUserId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Vincule uma conta Discord para gerenciar servidores");
        }
        GuildResources resources = coddyClient.getResources(guildId, discordUserId);
        if (!guildId.equals(resources.guildId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Resposta de servidor inválida");
        }
        return new AuthorizedGuild(discordUserId, resources);
    }

    public record AuthorizedGuild(Long discordUserId, GuildResources resources) {}
}
