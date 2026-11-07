package com.Brafurries.API.auth.social;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserSocialIdentity;
import com.Brafurries.API.entity.user.UserSocialLoginEvent;
import com.Brafurries.API.repository.user.UserSocialLoginEventRepository;
import java.time.LocalDateTime;
import com.Brafurries.API.entity.user.UserTokenType;
import com.Brafurries.API.repository.user.UserRefreshTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserSocialIdentityRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(MockitoExtension.class)
class SocialLoginControlServiceTest {
    @Mock UserRepository users;
    @Mock UserSocialIdentityRepository identities;
    @Mock UserSocialLoginEventRepository events;
    @Mock UserRefreshTokenRepository refreshTokens;
    @Mock UserTokenRepository userTokens;
    @Mock PasswordEncoder passwords;
    @Mock AuthSocialService social;
    @Mock DiscordSelfServiceLinkService discord;
    @Mock SocialLoginOAuthProofService oauthProofs;

    SocialLoginControlService service;
    User owner;
    Authentication auth;

    @BeforeEach
    void setup() {
        service = new SocialLoginControlService(users, identities, events, refreshTokens, userTokens,
            passwords, social, discord, oauthProofs);
        owner = new User();
        owner.setId(9);
        owner.setEmail("site@example.com");
        owner.setPasswordHash("hashed-local");
        owner.setAuthVersion(4L);
        owner.setEmailVerifiedAt(LocalDateTime.now());
        auth = new UsernamePasswordAuthenticationToken("site@example.com", null, List.of());
    }

    private void confirmedLocalAccount() {
        when(users.findByEmailForUpdate("site@example.com")).thenReturn(Optional.of(owner));
        when(passwords.matches("local-password", "hashed-local")).thenReturn(true);
    }

    private UserSocialIdentity identity(Long id, String provider, User user, boolean enabled) {
        UserSocialIdentity result = new UserSocialIdentity();
        result.setId(id);
        result.setUser(user);
        result.setProvider(provider);
        result.setProviderSubject("provider-subject");
        result.setLoginEnabled(enabled);
        return result;
    }

    @Test
    void disablingRevokesEveryPreviousSessionButPreservesExternalSubject() {
        confirmedLocalAccount();
        var linked = identity(11L, "discord", owner, true);
        when(identities.findById(11L)).thenReturn(Optional.of(linked));
        assertEquals("RELOGIN_REQUIRED", service.disable(auth, 11L, "local-password").status());
        assertFalse(linked.getLoginEnabled());
        assertEquals(5L, owner.getAuthVersion());
        verify(refreshTokens).deleteByUser(owner);
        verify(identities).save(linked);
        verify(events).save(argThat(event ->
            Boolean.TRUE.equals(event.getPreviousLoginEnabled())
                && Boolean.FALSE.equals(event.getNextLoginEnabled())
                && Long.valueOf(4L).equals(event.getPreviousAuthVersion())
                && Long.valueOf(5L).equals(event.getNextAuthVersion())));
        assertEquals("provider-subject", linked.getProviderSubject());
        assertSame(owner, linked.getUser());
    }

    @Test
    void passwordIsRequiredEvenWithAnAuthenticatedSession() {
        when(users.findByEmailForUpdate("site@example.com")).thenReturn(Optional.of(owner));
        assertThrows(BadCredentialsException.class, () -> service.disable(auth, 11L, "wrong"));
        verifyNoInteractions(identities, refreshTokens, events);
        assertEquals(4L, owner.getAuthVersion());
    }

    @Test
    void ownershipCannotBeChangedByAnIdentityId() {
        confirmedLocalAccount();
        User other = new User();
        other.setId(99);
        when(identities.findById(11L)).thenReturn(Optional.of(identity(11L, "discord", other, true)));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.disable(auth, 11L, "local-password"));
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(refreshTokens, never()).deleteByUser(any());
        verifyNoInteractions(events);
    }

    @Test
    void pendingEmailConfirmationPreventsDisablingLastRecoverableLogin() {
        confirmedLocalAccount();
        when(userTokens.existsByUserAndTypeAndUsedAtIsNullAndRevokedAtIsNull(
            owner, UserTokenType.EMAIL_VERIFICATION)).thenReturn(true);
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.disable(auth, 11L, "local-password"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verifyNoInteractions(identities, refreshTokens);
    }

    @Test
    void legacyImportedEmailNeedsPositiveLocalReverification() {
        owner.setEmailVerifiedAt(null);
        confirmedLocalAccount();
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.disable(auth, 11L, "local-password"));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verifyNoInteractions(events, identities, refreshTokens);
    }

    @Test
    void GoogleReactivationNeedsFreshGoogleSubjectOwnedBySession() {
        confirmedLocalAccount();
        when(social.verifiedGoogleSubject("fresh-id-token")).thenReturn("provider-subject");
        var linked = identity(12L, "google", owner, false);
        when(identities.findByProviderAndProviderSubject("google", "provider-subject"))
            .thenReturn(Optional.of(linked));
        assertEquals("RELOGIN_REQUIRED",
            service.enableGoogle(auth, "fresh-id-token", "local-password").status());
        assertTrue(linked.getLoginEnabled());
        assertEquals(5L, owner.getAuthVersion());
        verify(refreshTokens).deleteByUser(owner);
    }

    @Test
    void CrossOwnedGoogleTokenCannotReenableOrTransferIdentity() {
        confirmedLocalAccount();
        when(social.verifiedGoogleSubject("fresh-id-token")).thenReturn("provider-subject");
        User other = new User();
        other.setId(99);
        var linked = identity(12L, "google", other, false);
        when(identities.findByProviderAndProviderSubject("google", "provider-subject"))
            .thenReturn(Optional.of(linked));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.enableGoogle(auth, "fresh-id-token", "local-password"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertFalse(linked.getLoginEnabled());
        verify(refreshTokens, never()).deleteByUser(any());
    }

    @Test
    void GoogleOAuthTicketReactivationRequiresSameLocallyVerifiedOwner() {
        confirmedLocalAccount();
        when(oauthProofs.consumeVerifiedGoogle(owner, "one-use-ticket")).thenReturn("provider-subject");
        var linked = identity(12L, "google", owner, false);
        when(identities.findByProviderAndProviderSubject("google", "provider-subject"))
            .thenReturn(Optional.of(linked));
        assertEquals("RELOGIN_REQUIRED",
            service.enableGoogleOAuth(auth, "one-use-ticket", "local-password").status());
        assertTrue(linked.getLoginEnabled());
        verify(events).save(any());
        verify(refreshTokens).deleteByUser(owner);
    }

    @Test
    void DiscordReactivationNeedsTicketBoundToTheSameLocalAccount() {
        confirmedLocalAccount();
        when(discord.consumeVerifiedDiscordForLogin(owner, "ticket")).thenReturn("provider-subject");
        var linked = identity(13L, "discord", owner, false);
        when(identities.findByProviderAndProviderSubject("discord", "provider-subject"))
            .thenReturn(Optional.of(linked));
        assertEquals("RELOGIN_REQUIRED",
            service.enableDiscord(auth, "ticket", "local-password").status());
        assertTrue(linked.getLoginEnabled());
        verify(refreshTokens).deleteByUser(owner);
    }

    @Test
    void CannotDisableLoginFromAnotherUsersAccount() {
        User stranger = new User();
        stranger.setId(9);
        stranger.setMergedIntoUser(owner);
        when(users.findByEmailForUpdate("site@example.com")).thenReturn(Optional.of(stranger));
        assertThrows(ResponseStatusException.class,
            () -> service.disable(auth, 11L, "local-password"));
        verifyNoInteractions(identities, refreshTokens);
    }
}
