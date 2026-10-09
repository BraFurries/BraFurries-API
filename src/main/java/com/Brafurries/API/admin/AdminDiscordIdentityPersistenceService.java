package com.Brafurries.API.admin;

import com.Brafurries.API.admin.dto.AdminDiscordIdentityDtos.DiscordUserState;
import com.Brafurries.API.admin.dto.AdminDiscordIdentityDtos.LinkDiscordIdentityResponse;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserIdentityLinkStatus;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AdminDiscordIdentityPersistenceService {

    private final UserRepository users;
    private final UserDiscordRepository discordAccounts;
    private final UserIdentityLinkService identityLinks;

    public AdminDiscordIdentityPersistenceService(
        UserRepository users,
        UserDiscordRepository discordAccounts,
        UserIdentityLinkService identityLinks
    ) {
        this.users = users;
        this.discordAccounts = discordAccounts;
        this.identityLinks = identityLinks;
    }

    @Transactional
    public LinkDiscordIdentityResponse createOrLink(
        Integer sourceUserId,
        Long discordUserId,
        DiscordUserState state,
        String reason,
        String actorEmail
    ) {
        users.findByIdForUpdate(sourceUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário de origem não encontrado"));

        var existing = discordAccounts.findByDiscordUserId(discordUserId);
        boolean createdUser = existing.isEmpty();
        User target;

        if (existing.isPresent()) {
            target = existing.get().getUser();
        } else {
            target = new User();
            target.setUsername(truncate(state.username(), 32));
            target.setDisplayName(truncate(firstNonBlank(state.displayName(), state.username()), 32));
            target.setProfileImageUrl(blankToNull(state.avatarUrl()));
            target = users.saveAndFlush(target);

            UserDiscord discord = new UserDiscord();
            discord.setUser(target);
            discord.setDiscordUserId(discordUserId);
            discord.setUsername(truncate(firstNonBlank(state.username(), "discord"), 32));
            discord.setDisplayName(truncate(firstNonBlank(state.displayName(), state.username()), 32));
            try {
                discordAccounts.saveAndFlush(discord);
            } catch (DataIntegrityViolationException exception) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A conta Discord foi registrada por outra operação; atualize o preview e tente novamente"
                );
            }
        }

        if (target.getId().equals(sourceUserId)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Esta conta Discord já pertence ao mesmo User da ficha"
            );
        }

        var link = identityLinks.create(
            sourceUserId,
            target.getId(),
            UserIdentityLinkStatus.CONFIRMED,
            reason,
            actorEmail
        );
        return new LinkDiscordIdentityResponse(target.getId(), createdUser, link);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
