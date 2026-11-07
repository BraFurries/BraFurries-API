package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.common.AccessTokenService;
import com.Brafurries.API.auth.common.AuthService;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

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
    @Mock DiscordSelfServiceLinkService accountLinks;
    @Mock VerifiedSocialIdentityService verifiedIdentities;
    @Mock SocialOnboardingChallengeService onboarding;
    @Mock AuthService localAuth;
    @Mock PasswordEncoder passwords;
    @Mock OAuth2AuthenticationToken authentication;
    @Mock OAuth2User principal;

    private AuthSocialService service() {
        return new AuthSocialService(users, refreshTokens, tokens, accessTokens,
            roles, permissions, discord, userTokens, accountLinks,
            verifiedIdentities, onboarding, localAuth, passwords);
    }

    private User user(int id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    private void principal(String provider, Map<String, Object> attrs) {
        when(authentication.getAuthorizedClientRegistrationId()).thenReturn(provider);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(principal.getAttributes()).thenReturn(attrs);
    }

    @Test
    void legacyDiscordWithSameEmailAsLocalAccountCannotClaimIt() {
        User legacy = user(20, null);
        UserDiscord record = new UserDiscord();
        record.setUser(legacy);
        record.setDiscordUserId(123456789L);
        principal("discord", Map.of(
            "id", "123456789", "email", "person@example.com",
            "verified", true, "username", "person"));
        when(verifiedIdentities.loginAllowed("discord", "123456789")).thenReturn(true);
        when(discord.findByDiscordUserId(123456789L)).thenReturn(Optional.of(record));
        when(onboarding.create(eq("discord"), eq("123456789"), eq("person@example.com"), anyString()))
            .thenReturn(new SocialOnboardingChallengeService.Created("secure-ticket", "discord"));

        var result = service().loginOAuth2(authentication);

        assertInstanceOf(AuthSocialService.RegistrationRequired.class, result);
        verify(users, never()).findByEmail("person@example.com");
        verify(users, never()).save(any());
        verify(verifiedIdentities, never()).bindProvenSubject(any(), any(), any(), any());
    }

    @Test
    void googleMatchingEmailNeverClaimsExistingPasswordAccount() {
        principal("google", Map.of(
            "sub", "g-sub", "email", "person@example.com",
            "email_verified", true, "name", "Person"));
        when(verifiedIdentities.loginAllowed("google", "g-sub")).thenReturn(true);
        when(verifiedIdentities.ownerOf("google", "g-sub")).thenReturn(Optional.empty());
        when(onboarding.create(eq("google"), eq("g-sub"), eq("person@example.com"), anyString()))
            .thenReturn(new SocialOnboardingChallengeService.Created("secure-ticket", "google"));

        assertInstanceOf(AuthSocialService.RegistrationRequired.class,
            service().loginOAuth2(authentication));
        verify(users, never()).findByEmail("person@example.com");
        verify(users, never()).save(any());
    }

    @Test
    void discordIdentifyOnlyCanCreateProofWithoutProviderEmail() {
        principal("discord", Map.of("id", "9999", "username", "Furry"));
        when(verifiedIdentities.loginAllowed("discord", "9999")).thenReturn(true);
        when(discord.findByDiscordUserId(9999L)).thenReturn(Optional.empty());
        when(onboarding.create(eq("discord"), eq("9999"), isNull(), eq("Furry")))
            .thenReturn(new SocialOnboardingChallengeService.Created("secure-ticket", "discord"));

        var pending = (AuthSocialService.RegistrationRequired) service().loginOAuth2(authentication);

        assertNull(pending.pendingRegistration().email());
        assertEquals("secure-ticket", pending.pendingRegistration().token());
        verify(users, never()).save(any());
    }

    @Test
    void disabledDiscordLoginCannotCreateNewAccountOrProof() {
        principal("discord", Map.of("id", "9999", "username", "Furry"));
        when(verifiedIdentities.loginAllowed("discord", "9999")).thenReturn(false);

        BadCredentialsException ex = assertThrows(BadCredentialsException.class,
            () -> service().loginOAuth2(authentication));

        assertEquals("PROVIDER_LOGIN_DISABLED", ex.getMessage());
        verifyNoInteractions(onboarding, users, discord);
    }

    @Test
    void googleOwnerWithNoLocalPasswordRequiresOnboardingRatherThanJwt() {
        User oldOwner = user(20, "legacy@example.com");
        principal("google", Map.of(
            "sub", "g-sub", "email", "provider@example.com", "email_verified", true));
        when(verifiedIdentities.loginAllowed("google", "g-sub")).thenReturn(true);
        when(verifiedIdentities.ownerOf("google", "g-sub")).thenReturn(Optional.of(oldOwner));
        when(onboarding.create(eq("google"), eq("g-sub"), eq("provider@example.com"), anyString()))
            .thenReturn(new SocialOnboardingChallengeService.Created("secure-ticket", "google"));

        assertInstanceOf(AuthSocialService.RegistrationRequired.class,
            service().loginOAuth2(authentication));
        verify(refreshTokens, never()).save(any());
    }
}
