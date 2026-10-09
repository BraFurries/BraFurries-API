package com.Brafurries.API.user;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ManagedServersDiscordIdentityService {

    private final UserRepository userRepository;
    private final UserDiscordRepository userDiscordRepository;

    public ManagedServersDiscordIdentityService(
        UserRepository userRepository,
        UserDiscordRepository userDiscordRepository
    ) {
        this.userRepository = userRepository;
        this.userDiscordRepository = userDiscordRepository;
    }

    @Transactional(readOnly = true)
    public Long findDiscordUserId(String email) {
        User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário autenticado não encontrado"));
        UserDiscord discord = userDiscordRepository.findByUser(user).orElse(null);
        return discord == null ? null : discord.getDiscordUserId();
    }
}
