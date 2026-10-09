package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.common.AccessTokenService;
import com.Brafurries.API.auth.common.TokenService;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.repository.api.ApiRolePermissionRepository;
import com.Brafurries.API.repository.api.ApiUserRoleRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRefreshTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthSocialServiceConflictTest {

    @Mock UserRepository users;
    @Mock UserRefreshTokenRepository refreshTokens;
    @Mock TokenService tokens;
    @Mock AccessTokenService accessTokens;
    @Mock ApiUserRoleRepository roles;
    @Mock ApiRolePermissionRepository permissions;
    @Mock UserDiscordRepository discord;
    @Mock UserTokenRepository userTokens;
    @Mock OAuth2AuthenticationToken authentication;
    @Mock OAuth2User principal;

    @Test
    void discordEmailConflictFailsClosedWithoutDestructiveMutation() {
        User emailUser = user(10, "person@example.com");
        User providerUser = user(20, null);
        UserDiscord providerLink = new UserDiscord();
        providerLink.setUser(providerUser);
        providerLink.setDiscordUserId(123456789L);

        when(authentication.getAuthorizedClientRegistrationId()).thenReturn("discord");
        when(authentication.getPrincipal()).thenReturn(principal);
        when(principal.getAttributes()).thenReturn(Map.of(
            "id", "123456789", "email", "person@example.com", "verified", true,
            "username", "person", "global_name", "Person"
        ));
        when(users.findByEmail("person@example.com")).thenReturn(Optional.of(emailUser));
        when(discord.findByDiscordUserId(123456789L)).thenReturn(Optional.of(providerLink));

        AuthSocialService service = new AuthSocialService(
            users, refreshTokens, tokens, accessTokens, roles, permissions, discord, userTokens
        );

        assertThrows(BadCredentialsException.class, () -> service.loginOAuth2(authentication));
        verify(users, never()).deleteById(20);
        verify(users, never()).save(providerUser);
        verify(discord, never()).save(providerLink);
        verify(refreshTokens, never()).save(org.mockito.ArgumentMatchers.any());
        verify(roles, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void existingUserDiscordIsNeverReassignedToANewProviderId() {
        User emailUser = user(10, "person@example.com");
        emailUser.setDisplayName("Person");
        UserDiscord oldLink = new UserDiscord();
        oldLink.setUser(emailUser);
        oldLink.setDiscordUserId(111L);

        when(authentication.getAuthorizedClientRegistrationId()).thenReturn("discord");
        when(authentication.getPrincipal()).thenReturn(principal);
        when(principal.getAttributes()).thenReturn(Map.of(
            "id", "222", "email", "person@example.com", "verified", true,
            "username", "person", "global_name", "Person"
        ));
        when(users.findByEmail("person@example.com")).thenReturn(Optional.of(emailUser));
        when(discord.findByDiscordUserId(222L)).thenReturn(Optional.empty());
        when(discord.findByUser(emailUser)).thenReturn(Optional.of(oldLink));

        AuthSocialService service = new AuthSocialService(
            users, refreshTokens, tokens, accessTokens, roles, permissions, discord, userTokens
        );

        assertThrows(BadCredentialsException.class, () -> service.loginOAuth2(authentication));
        verify(discord, never()).save(org.mockito.ArgumentMatchers.any());
        verify(users, never()).deleteById(org.mockito.ArgumentMatchers.any());
        verify(refreshTokens, never()).save(org.mockito.ArgumentMatchers.any());
        verify(roles, never()).save(org.mockito.ArgumentMatchers.any());
    }

    private User user(int id, String email) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        return user;
    }
}
