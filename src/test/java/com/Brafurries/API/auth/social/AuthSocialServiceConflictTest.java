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
import java.time.LocalDateTime;
import java.util.List;
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
        when(discord.findByDiscordUserId(123456789L)).thenReturn(Optional.of(record));
        when(users.findByIdForUpdate(20)).thenReturn(Optional.of(legacy));
        when(verifiedIdentities.loginAllowedAfterUserLock("discord", "123456789"))
            .thenReturn(true);
        when(onboarding.create(eq("discord"), eq("123456789"), eq("person@example.com"), anyString()))
            .thenReturn(new SocialOnboardingChallengeService.Created("secure-ticket", "discord"));

        var result = service().loginOAuth2(authentication);

        assertInstanceOf(AuthSocialService.RegistrationRequired.class, result);
        verify(users, never()).findByEmail("person@example.com");
        verify(users, never()).save(any());
        verify(verifiedIdentities, never()).bindProvenSubject(any(), any(), any(), any());
    }

    private void unlinkedGoogle(Map<String, Object> attrs) {
        principal("google", attrs);
        // Mockito's default Boolean is false, which represents an explicitly
        // disabled login. These scenarios have no prior Google identity.
        when(verifiedIdentities.loginAllowed("google", "g-sub")).thenReturn(true);
    }

    @Test
    void googleThirdPartyEmailCannotClaimAnExistingPasswordAccount() {
        User local = verifiedLocalUser(21, "person@example.com");
        unlinkedGoogle(Map.of(
            "sub", "g-sub", "email", "person@example.com",
            "email_verified", true, "name", "Person"));
        when(users.findByEmailForUpdate("person@example.com")).thenReturn(Optional.of(local));

        BadCredentialsException error = assertThrows(BadCredentialsException.class,
            () -> service().loginOAuth2(authentication));

        assertEquals("GOOGLE_ACCOUNT_LINK_REQUIRED", error.getMessage());
        verify(verifiedIdentities, never()).bindProvenSubject(any(), any(), any(), any());
        verifyNoInteractions(onboarding, refreshTokens);
    }

    private User verifiedLocalUser(int id, String email) {
        User result = user(id, email);
        result.setPasswordHash("local-hash");
        result.setEmailVerifiedAt(LocalDateTime.now());
        return result;
    }

    private void assertAutomaticallyLinkedGoogleAccount(User local, Map<String, Object> claims) {
        unlinkedGoogle(claims);
        when(users.findByEmailForUpdate(local.getEmail())).thenReturn(Optional.of(local));
        when(roles.findRoleNamesByUserId(local.getId())).thenReturn(List.of("member"));
        when(permissions.findPermissionNamesByUserId(local.getId())).thenReturn(List.of());
        AuthSocialService.ExistingUserLogin result =
            assertInstanceOf(AuthSocialService.ExistingUserLogin.class,
                service().loginOAuth2(authentication));

        assertEquals(local.getId().longValue(), result.loginResponse().user().id());
        verify(verifiedIdentities).bindProvenSubject(local, "google", "g-sub", local.getEmail());
        verify(refreshTokens).deleteByUser(local);
        verify(onboarding, never()).create(any(), any(), any(), any());
        verify(users, never()).save(any());
    }

    @Test
    void verifiedGmailAutomaticallyLinksToTheExistingUserWithoutCreatingAnotherOne() {
        User local = verifiedLocalUser(21, "person@gmail.com");
        assertAutomaticallyLinkedGoogleAccount(local, Map.of(
            "sub", "g-sub", "email", "PERSON@gmail.com",
            "email_verified", true, "name", "Person"));
        verify(verifiedIdentities).hasDifferentSubject(local, "google", "g-sub");
        verify(verifiedIdentities).loginDisabledAfterUserLock("google", "g-sub");
    }

    @Test
    void GoogleWorkspaceDomainClaimAllowsAutoLinkForVerifiedLocalAddress() {
        User local = verifiedLocalUser(22, "person@workspace.example");
        assertAutomaticallyLinkedGoogleAccount(local, Map.of(
            "sub", "g-sub", "email", "person@workspace.example",
            "email_verified", true, "hd", "workspace.example"));
    }

    @Test
    void missingLocalEmailVerificationNeverTriggersAutomaticLink() {
        User legacy = user(23, "legacy@gmail.com");
        legacy.setPasswordHash("legacy-hash");
        unlinkedGoogle(Map.of(
            "sub", "g-sub", "email", "legacy@gmail.com", "email_verified", true));
        when(users.findByEmailForUpdate("legacy@gmail.com")).thenReturn(Optional.of(legacy));

        BadCredentialsException error = assertThrows(BadCredentialsException.class,
            () -> service().loginOAuth2(authentication));
        assertEquals("GOOGLE_ACCOUNT_LINK_REQUIRED", error.getMessage());
        verify(verifiedIdentities, never()).bindProvenSubject(any(), any(), any(), any());
        verifyNoInteractions(refreshTokens);
    }

    @Test
    void pendingLocalEmailConfirmationAlsoBlocksAutoLink() {
        User local = verifiedLocalUser(24, "person@gmail.com");
        unlinkedGoogle(Map.of(
            "sub", "g-sub", "email", "person@gmail.com", "email_verified", true));
        when(users.findByEmailForUpdate(local.getEmail())).thenReturn(Optional.of(local));
        when(userTokens.existsByUserAndTypeAndUsedAtIsNullAndRevokedAtIsNull(
            local, com.Brafurries.API.entity.user.UserTokenType.EMAIL_VERIFICATION)).thenReturn(true);

        assertThrows(BadCredentialsException.class, () -> service().loginOAuth2(authentication));
        verify(verifiedIdentities, never()).bindProvenSubject(any(), any(), any(), any());
    }

    @Test
    void WorkspaceClaimForOtherDomainDoesNotAuthorizeAutoLink() {
        User local = verifiedLocalUser(25, "person@outsider.example");
        unlinkedGoogle(Map.of(
            "sub", "g-sub", "email", "person@outsider.example",
            "email_verified", true, "hd", "workspace.example"));
        when(users.findByEmailForUpdate(local.getEmail())).thenReturn(Optional.of(local));

        assertThrows(BadCredentialsException.class, () -> service().loginOAuth2(authentication));
        verify(verifiedIdentities, never()).bindProvenSubject(any(), any(), any(), any());
    }

    @Test
    void revokedGoogleLoginCannotBeRevivedByMatchingGmail() {
        principal("google", Map.of(
            "sub", "g-sub", "email", "person@gmail.com", "email_verified", true));
        when(verifiedIdentities.loginAllowed("google", "g-sub")).thenReturn(false);

        BadCredentialsException error = assertThrows(BadCredentialsException.class,
            () -> service().loginOAuth2(authentication));
        assertEquals("PROVIDER_LOGIN_DISABLED", error.getMessage());
        verifyNoInteractions(users, onboarding, refreshTokens);
    }

    @Test
    void alternateGoogleSubjectCannotReplaceAnExistingGoogleIdentity() {
        User local = verifiedLocalUser(26, "person@gmail.com");
        unlinkedGoogle(Map.of(
            "sub", "g-sub", "email", "person@gmail.com", "email_verified", true));
        when(users.findByEmailForUpdate(local.getEmail())).thenReturn(Optional.of(local));
        when(verifiedIdentities.hasDifferentSubject(local, "google", "g-sub")).thenReturn(true);

        BadCredentialsException error = assertThrows(BadCredentialsException.class,
            () -> service().loginOAuth2(authentication));
        assertEquals("GOOGLE_ACCOUNT_LINK_REQUIRED", error.getMessage());
        verify(verifiedIdentities, never()).bindProvenSubject(any(), any(), any(), any());
    }

    @Test
    void raceWithDisabledGoogleIdentityCannotCreateSession() {
        User local = verifiedLocalUser(27, "person@gmail.com");
        unlinkedGoogle(Map.of(
            "sub", "g-sub", "email", "person@gmail.com", "email_verified", true));
        when(users.findByEmailForUpdate(local.getEmail())).thenReturn(Optional.of(local));
        when(verifiedIdentities.loginDisabledAfterUserLock("google", "g-sub")).thenReturn(true);

        BadCredentialsException error = assertThrows(BadCredentialsException.class,
            () -> service().loginOAuth2(authentication));
        assertEquals("PROVIDER_LOGIN_DISABLED", error.getMessage());
        verify(verifiedIdentities, never()).bindProvenSubject(any(), any(), any(), any());
        verifyNoInteractions(refreshTokens, onboarding);
    }

    @Test
    void unverifiedGoogleEmailDoesNotSearchForAUserToAutoLink() {
        unlinkedGoogle(Map.of(
            "sub", "g-sub", "email", "person@gmail.com", "email_verified", false));
        when(onboarding.create(eq("google"), eq("g-sub"), isNull(), anyString()))
            .thenReturn(new SocialOnboardingChallengeService.Created("secure-ticket", "google"));

        assertInstanceOf(AuthSocialService.RegistrationRequired.class,
            service().loginOAuth2(authentication));
        verify(users, never()).findByEmailForUpdate(anyString());
        verify(verifiedIdentities, never()).bindProvenSubject(any(), any(), any(), any());
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
        verifyNoInteractions(onboarding, users);
        verify(discord).findByDiscordUserId(9999L);
    }

    @Test
    void oauthCallbackStartedBeforeDisableCannotRecreateSessionsAfterLoginWasDisabled() {
        User previouslyLinked = user(20, "site@example.com");
        previouslyLinked.setPasswordHash("hash");
        UserDiscord record = new UserDiscord();
        record.setUser(previouslyLinked);
        record.setDiscordUserId(123456789L);
        principal("discord", Map.of("id", "123456789", "username", "Member"));
        when(discord.findByDiscordUserId(123456789L)).thenReturn(Optional.of(record));
        when(users.findByIdForUpdate(20)).thenReturn(Optional.of(previouslyLinked));
        // The current locking read sees the disable transaction's committed state.
        when(verifiedIdentities.loginAllowedAfterUserLock("discord", "123456789"))
            .thenReturn(false);

        BadCredentialsException error = assertThrows(BadCredentialsException.class,
            () -> service().loginOAuth2(authentication));

        assertEquals("PROVIDER_LOGIN_DISABLED", error.getMessage());
        verify(verifiedIdentities).loginAllowedAfterUserLock("discord", "123456789");
        verifyNoInteractions(refreshTokens, tokens, accessTokens);
        verify(onboarding, never()).create(any(), any(), any(), any());
    }

    @Test
    void googleOwnerWithNoLocalPasswordRequiresOnboardingRatherThanJwt() {
        User oldOwner = user(20, "legacy@example.com");
        principal("google", Map.of(
            "sub", "g-sub", "email", "provider@example.com", "email_verified", true));
        when(verifiedIdentities.ownerOf("google", "g-sub")).thenReturn(Optional.of(oldOwner));
        when(users.findByIdForUpdate(20)).thenReturn(Optional.of(oldOwner));
        when(verifiedIdentities.loginAllowedAfterUserLock("google", "g-sub"))
            .thenReturn(true);
        when(onboarding.create(eq("google"), eq("g-sub"), eq("provider@example.com"), anyString()))
            .thenReturn(new SocialOnboardingChallengeService.Created("secure-ticket", "google"));

        assertInstanceOf(AuthSocialService.RegistrationRequired.class,
            service().loginOAuth2(authentication));
        verify(refreshTokens, never()).save(any());
    }
}
