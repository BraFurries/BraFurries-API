package com.Brafurries.API.user;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.user.UserBirthdayRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserLocaleRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTelegramRepository;
import com.Brafurries.API.user.dto.UserGeneralInfoDtos.UserGeneralInfoResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserGeneralInfoServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserBirthdayRepository userBirthdayRepository;

    @Mock
    private UserLocaleRepository userLocaleRepository;

    @Mock
    private UserDiscordRepository userDiscordRepository;

    @Mock
    private UserTelegramRepository userTelegramRepository;

    @Test
    void getLoggedUserGeneralInfoReturnsProfileImageUrl() {
        User user = new User();
        user.setId(42);
        user.setEmail("user@example.com");
        user.setDisplayName("User");
        user.setProfileImageUrl("https://cdn.example.com/users/42/profile/avatar.webp");

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(userDiscordRepository.findByUser(user)).thenReturn(Optional.empty());
        when(userTelegramRepository.findByUser(user)).thenReturn(Optional.empty());
        when(userBirthdayRepository.findByUser(user)).thenReturn(Optional.empty());
        when(userLocaleRepository.findByUser(user)).thenReturn(Optional.empty());

        UserGeneralInfoService service = new UserGeneralInfoService(
            userRepository,
            userBirthdayRepository,
            userLocaleRepository,
            userDiscordRepository,
            userTelegramRepository
        );

        UserGeneralInfoResponse response = service.getLoggedUserGeneralInfo("user@example.com");

        assertEquals("https://cdn.example.com/users/42/profile/avatar.webp", response.profileImageUrl());
        assertNull(response.discord());
        assertNull(response.telegram());
    }
}
