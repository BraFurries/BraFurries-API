package com.Brafurries.API.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class ManagedServersDiscordIdentityServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserDiscordRepository userDiscordRepository;

    @InjectMocks
    private ManagedServersDiscordIdentityService service;

    @Test
    void resolvesDiscordUserIdInsideAReadOnlyTransactionBoundary() throws NoSuchMethodException {
        User user = new User();
        user.setEmail("user@example.com");
        UserDiscord discord = new UserDiscord();
        discord.setUser(user);
        discord.setDiscordUserId(42L);
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(userDiscordRepository.findByUser(user)).thenReturn(Optional.of(discord));

        assertEquals(42L, service.findDiscordUserId("user@example.com"));

        Transactional boundary = ManagedServersDiscordIdentityService.class
            .getMethod("findDiscordUserId", String.class)
            .getAnnotation(Transactional.class);
        assertEquals(true, boundary.readOnly());
        assertNull(ManagedServersService.class
            .getMethod("getLoggedUserManagedServers", org.springframework.security.core.Authentication.class)
            .getAnnotation(Transactional.class));
    }
}
