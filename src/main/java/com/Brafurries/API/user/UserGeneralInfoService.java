package com.Brafurries.API.user;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.user.UserBirthdayRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserLocaleRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTelegramRepository;
import com.Brafurries.API.user.dto.UserGeneralInfoDtos.UserDiscordInfo;
import com.Brafurries.API.user.dto.UserGeneralInfoDtos.UserGeneralInfoResponse;
import com.Brafurries.API.user.dto.UserGeneralInfoDtos.UserTelegramInfo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserGeneralInfoService {

    private final UserRepository userRepository;
    private final UserBirthdayRepository userBirthdayRepository;
    private final UserLocaleRepository userLocaleRepository;
    private final UserDiscordRepository userDiscordRepository;
    private final UserTelegramRepository userTelegramRepository;

    public UserGeneralInfoService(
        UserRepository userRepository,
        UserBirthdayRepository userBirthdayRepository,
        UserLocaleRepository userLocaleRepository,
        UserDiscordRepository userDiscordRepository,
        UserTelegramRepository userTelegramRepository
    ) {
        this.userRepository = userRepository;
        this.userBirthdayRepository = userBirthdayRepository;
        this.userLocaleRepository = userLocaleRepository;
        this.userDiscordRepository = userDiscordRepository;
        this.userTelegramRepository = userTelegramRepository;
    }

    @Transactional(readOnly = true)
    public UserGeneralInfoResponse getLoggedUserGeneralInfo(String email) {
        User user = userRepository.findByEmail(email.trim().toLowerCase())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuário autenticado não encontrado"));

        UserDiscordInfo discord = userDiscordRepository.findByUser(user)
            .map(d -> new UserDiscordInfo(d.getDiscordUserId(), d.getUsername(), d.getDisplayName()))
            .orElse(null);

        UserTelegramInfo telegram = userTelegramRepository.findByUser(user)
            .map(t -> new UserTelegramInfo(t.getTelegramUserId(), t.getUsername(), t.getDisplayName()))
            .orElse(null);

        return new UserGeneralInfoResponse(
            user.getId(),
            user.getDisplayName(),
            user.getEmail(),
            user.getProfileImageUrl(),
            userBirthdayRepository.findByUser(user).map(b -> b.getBirthDate()).orElse(null),
            userLocaleRepository.findByUser(user).map(l -> l.getLocale().getLocaleAbbrev()).orElse(null),
            discord,
            telegram
        );
    }
}
