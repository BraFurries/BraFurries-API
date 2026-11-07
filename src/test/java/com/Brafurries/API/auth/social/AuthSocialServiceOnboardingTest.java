package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.common.AccessTokenService;
import com.Brafurries.API.auth.common.AuthService;
import com.Brafurries.API.auth.common.TokenService;
import com.Brafurries.API.auth.social.dto.AuthSocialDtos.CompleteSocialRegisterRequest;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserSocialOnboardingChallenge;
import com.Brafurries.API.repository.api.ApiRolePermissionRepository;
import com.Brafurries.API.repository.api.ApiUserRoleRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRefreshTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthSocialServiceOnboardingTest {
    @Mock UserRepository users;
    @Mock UserRefreshTokenRepository refreshTokens;
    @Mock TokenService tokens;
    @Mock AccessTokenService accessTokens;
    @Mock ApiUserRoleRepository roles;
    @Mock ApiRolePermissionRepository permissions;
    @Mock UserDiscordRepository discord;
    @Mock UserTokenRepository userTokens;
    @Mock DiscordSelfServiceLinkService discordLinks;
    @Mock VerifiedSocialIdentityService identities;
    @Mock SocialOnboardingChallengeService onboarding;
    @Mock AuthService localAuth;
    @Mock PasswordEncoder passwords;

    private AuthSocialService service() {
        return new AuthSocialService(users, refreshTokens, tokens, accessTokens,
            roles, permissions, discord, userTokens, discordLinks,
            identities, onboarding, localAuth, passwords);
    }

    private UserSocialOnboardingChallenge proof(String provider, String subject, String suggestion) {
        var proof = new UserSocialOnboardingChallenge();
        proof.setProvider(provider);
        proof.setProviderSubject(subject);
        proof.setSuggestedEmail(suggestion);
        return proof;
    }

    private CompleteSocialRegisterRequest request(String email) {
        return new CompleteSocialRegisterRequest("opaque", "Dragon",
            email, "strong-password", "strong-password", true);
    }

    @Test
    void googleSuggestionCanBeReplacedBySelectedSiteEmailWithoutIssuingJwt() {
        var proof = proof("google", "google-sub", "provider@gmail.com");
        when(onboarding.lockVerified("opaque")).thenReturn(proof);
        when(identities.loginAllowed(proof.getProvider(), proof.getProviderSubject())).thenReturn(true);
        when(users.findByEmail("chosen@example.com")).thenReturn(Optional.empty());
        when(identities.ownerOf("google", "google-sub")).thenReturn(Optional.empty());
        when(users.saveAndFlush(any(User.class))).thenAnswer(call -> {
            User u = call.getArgument(0);
            u.setId(42);
            return u;
        });
        when(passwords.encode("strong-password")).thenReturn("encoded-local-password");

        var response = service().completeSocialRegistration(request("CHOSEN@example.com"));

        assertEquals(42L, response.id());
        assertEquals("chosen@example.com", response.email());
        verify(localAuth).requireLocalEmailConfirmation(argThat(u ->
            u.getId().equals(42) && u.getEmail().equals("chosen@example.com")
                && u.getPasswordHash().equals("encoded-local-password")));
        verify(identities).bindProvenSubject(any(User.class),
            eq("google"), eq("google-sub"), eq("provider@gmail.com"));
        verify(onboarding).consume(proof);
        verifyNoInteractions(refreshTokens, accessTokens);
    }

    @Test
    void discordLegacyUserIsActivatedInPlaceWithoutLosingItsHistoricalIdentity() {
        User legacy = new User();
        legacy.setId(20);
        UserDiscord linked = new UserDiscord();
        linked.setUser(legacy);
        linked.setDiscordUserId(987654321L);
        var proof = proof("discord", "987654321", null);
        when(onboarding.lockVerified("opaque")).thenReturn(proof);
        when(users.findByEmail("chosen@example.com")).thenReturn(Optional.empty());
        when(identities.loginAllowed("discord", "987654321")).thenReturn(true);
        when(discord.findByDiscordUserId(987654321L)).thenReturn(Optional.of(linked));
        when(users.findByIdForUpdate(20)).thenReturn(Optional.of(legacy));
        when(users.saveAndFlush(legacy)).thenReturn(legacy);
        when(passwords.encode("strong-password")).thenReturn("hash");

        var response = service().completeSocialRegistration(request("chosen@example.com"));

        assertEquals(20L, response.id());
        assertEquals("chosen@example.com", legacy.getEmail());
        assertEquals("hash", legacy.getPasswordHash());
        assertEquals(987654321L, linked.getDiscordUserId());
        verify(discord).save(linked);
        verify(localAuth).requireLocalEmailConfirmation(legacy);
        verify(onboarding).consume(proof);
        verifyNoInteractions(refreshTokens, accessTokens);
    }

    @Test
    void matchingEmailDoesNotClaimAnUnrelatedSiteAccount() {
        User unrelated = new User();
        unrelated.setId(44);
        when(onboarding.lockVerified("opaque"))
            .thenReturn(proof("google", "google-sub", "provider@gmail.com"));
        when(identities.loginAllowed("google", "google-sub")).thenReturn(true);
        when(users.findByEmail("chosen@example.com")).thenReturn(Optional.of(unrelated));

        var failure = assertThrows(ResponseStatusException.class,
            () -> service().completeSocialRegistration(request("chosen@example.com")));

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertEquals("ACCOUNT_RECONCILIATION_REQUIRED", failure.getReason());
        verify(onboarding, never()).consume(any());
        verify(identities, never()).bindProvenSubject(any(), any(), any(), any());
        verifyNoInteractions(localAuth, refreshTokens);
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void proofOfOAuthDoesNotAllowABypassWithoutChosenPassword() {
        var invalid = new CompleteSocialRegisterRequest("opaque", "Dragon",
            "chosen@example.com", "", "", true);
        assertThrows(IllegalArgumentException.class,
            () -> service().completeSocialRegistration(invalid));
        verifyNoInteractions(onboarding, localAuth, users);
    }
}
