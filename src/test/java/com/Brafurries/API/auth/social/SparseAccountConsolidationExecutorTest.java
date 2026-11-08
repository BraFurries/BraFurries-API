package com.Brafurries.API.auth.social;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserDiscordLinkChallenge;
import com.Brafurries.API.entity.user.UserSocialIdentity;
import com.Brafurries.API.entity.user.UserTokenType;
import java.time.LocalDateTime;
import com.Brafurries.API.entity.user.UserAccountConsolidation;
import com.Brafurries.API.repository.user.UserAccountConsolidationRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserIdentityLinkRepository;
import com.Brafurries.API.repository.user.UserRefreshTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserSocialIdentityRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SparseAccountConsolidationExecutorTest {
    @Mock UserRepository users;
    @Mock UserDiscordRepository discord;
    @Mock UserIdentityLinkRepository links;
    @Mock UserSocialIdentityRepository identities;
    @Mock UserRefreshTokenRepository refresh;
    @Mock UserTokenRepository tokens;
    @Mock UserAccountConsolidationRepository audit;
    @Mock SparseAccountOwnershipGuard ownership;

    private SparseAccountConsolidationExecutor service;
    private User website;
    private User legacy;
    private UserDiscordLinkChallenge ticket;

    @BeforeEach
    void setup() {
        service = new SparseAccountConsolidationExecutor(users, discord, links,
            identities, refresh, tokens, audit, ownership);
        website = new User();
        website.setId(10);
        website.setEmail("google@example.com");
        website.setDisplayName("Google");
        legacy = new User();
        legacy.setId(20);
        legacy.setDisplayName("Discord");
        UserDiscord existingDiscord = new UserDiscord();
        existingDiscord.setUser(legacy);
        existingDiscord.setDiscordUserId(123456789L);
        ticket = new UserDiscordLinkChallenge();
        ticket.setRequester(website);
        ticket.setDiscordUserId(123456789L);
        ticket.setStatus("VERIFIED");
    }

    private void mockValidLockedPair() {
        UserDiscord existingDiscord = new UserDiscord();
        existingDiscord.setUser(legacy);
        existingDiscord.setDiscordUserId(123456789L);
        when(discord.findByDiscordUserId(123456789L))
            .thenReturn(Optional.of(existingDiscord));
        when(users.findByIdForUpdate(10)).thenReturn(Optional.of(website));
        when(users.findByIdForUpdate(20)).thenReturn(Optional.of(legacy));
    }

    @Test
    void sparseGoogleAccountMovesVerifiedProviderSubjectsAndKeepsDiscordHistoryInPlace() {
        mockValidLockedPair();
        UserSocialIdentity verifiedGoogle = new UserSocialIdentity();
        verifiedGoogle.setProvider("google");
        verifiedGoogle.setProviderSubject("immutable-google-sub");
        verifiedGoogle.setUser(website);
        when(identities.findByUserIdAndProvider(10, "google"))
            .thenReturn(List.of(verifiedGoogle));
        when(identities.findByUserIdAndProvider(10, "discord"))
            .thenReturn(List.of());

        LocalDateTime independentlyVerified = LocalDateTime.of(2026, 11, 7, 12, 0);
        website.setEmailVerifiedAt(independentlyVerified);
        Integer canonicalId = service.consolidate(website, ticket);

        assertEquals(independentlyVerified, legacy.getEmailVerifiedAt());
        assertNull(website.getEmailVerifiedAt());
        assertEquals(20, canonicalId);
        verify(ownership).assertOnlyTransferableReferences(10);
        assertEquals("google@example.com", legacy.getEmail());
        assertNull(website.getEmail());
        assertNull(website.getPasswordHash());
        assertEquals(legacy, website.getMergedIntoUser());
        assertEquals(legacy, verifiedGoogle.getUser());
        assertEquals(1L, website.getAuthVersion());
        assertEquals(1L, legacy.getAuthVersion());
        verify(refresh).deleteByUser(website);
        verify(refresh).deleteByUser(legacy);
        verify(tokens).deleteByUser(website);
        verify(tokens).deleteByUser(legacy);
        verify(identities).flush();

        ArgumentCaptor<UserAccountConsolidation> auditEntry =
            ArgumentCaptor.forClass(UserAccountConsolidation.class);
        verify(audit).saveAndFlush(auditEntry.capture());
        assertEquals(website, auditEntry.getValue().getAbsorbedUser());
        assertEquals(legacy, auditEntry.getValue().getCanonicalUser());
        assertEquals(website, auditEntry.getValue().getInitiatedByUser());
        assertEquals(123456789L, auditEntry.getValue().getProvedDiscordUserId());
        verify(discord, never()).save(any());
        verify(discord, never()).saveAndFlush(any());
    }

    @Test
    void unknownOperationalReferenceRejectsBeforeAnyMutation() {
        mockValidLockedPair();
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT,
            "ACCOUNT_DATA_RECONCILIATION_REQUIRED"))
            .when(ownership).assertOnlyTransferableReferences(10);

        var failure = assertThrows(ResponseStatusException.class,
            () -> service.consolidate(website, ticket));

        assertEquals(HttpStatus.CONFLICT, failure.getStatusCode());
        assertEquals("google@example.com", website.getEmail());
        assertNull(website.getMergedIntoUser());
        verify(users, never()).saveAndFlush(any());
        verifyNoInteractions(refresh, tokens);
        verify(audit, never()).saveAndFlush(any());
    }

    @Test
    void alreadyAbsorbedOrReplayedAccountCannotBeMergedTwice() {
        mockValidLockedPair();
        when(audit.existsByAbsorbedUserId(10)).thenReturn(true);

        var failure = assertThrows(ResponseStatusException.class,
            () -> service.consolidate(website, ticket));

        assertEquals("ACCOUNT_ALREADY_CONSOLIDATED", failure.getReason());
        verifyNoInteractions(ownership, refresh, tokens);
    }

    @Test
    void accountWithoutPasswordOrVerifiedProviderMustRecoverBeforeMerge() {
        mockValidLockedPair();
        when(identities.findByUserIdAndProvider(10, "google")).thenReturn(List.of());
        when(identities.findByUserIdAndProvider(10, "discord")).thenReturn(List.of());

        var failure = assertThrows(ResponseStatusException.class,
            () -> service.consolidate(website, ticket));

        assertEquals("VERIFY_GOOGLE_OR_RECOVER_LOCAL_LOGIN", failure.getReason());
        verifyNoInteractions(refresh, tokens);
        verify(audit, never()).saveAndFlush(any());
    }

    @Test
    void localEmailAndPasswordAccountMergesWithProvenDiscordWithoutGoogleOAuth() {
        mockValidLockedPair();
        website.setPasswordHash("safe-local-password-hash");
        website.setEmail("shared-with-discord@example.com");
        // The verified Discord login has the same address at its provider,
        // but the legacy users row has no e-mail. Equality alone is NOT proof.
        // The authenticated website session and one-time Discord OAuth ticket
        // independently prove ownership of both identities.
        when(identities.findByUserIdAndProvider(10, "google")).thenReturn(List.of());
        when(identities.findByUserIdAndProvider(10, "discord")).thenReturn(List.of());

        Integer canonicalId = service.consolidate(website, ticket);

        assertEquals(20, canonicalId);
        assertEquals("shared-with-discord@example.com", legacy.getEmail());
        assertEquals("safe-local-password-hash", legacy.getPasswordHash());
        assertNull(website.getEmail());
        assertEquals(legacy, website.getMergedIntoUser());
        assertEquals(1L, website.getAuthVersion());
        assertEquals(1L, legacy.getAuthVersion());
        verify(ownership).assertOnlyTransferableReferences(10);
        verify(refresh).deleteByUser(website);
        verify(refresh).deleteByUser(legacy);
        verify(audit).saveAndFlush(any(UserAccountConsolidation.class));
        verify(identities, never()).save(any());
    }

    @Test
    void mergeCannotErasePendingLocalVerificationAndAccidentallyGrantAccess() {
        mockValidLockedPair();
        website.setPasswordHash("local-password");
        when(tokens.existsByUserAndTypeAndUsedAtIsNullAndRevokedAtIsNull(
            website, UserTokenType.EMAIL_VERIFICATION)).thenReturn(true);

        var error = assertThrows(ResponseStatusException.class,
            () -> service.consolidate(website, ticket));

        assertEquals("LOCAL_EMAIL_CONFIRMATION_REQUIRED", error.getReason());
        assertEquals("google@example.com", website.getEmail());
        assertNull(legacy.getEmail());
        verify(users, never()).saveAndFlush(any());
        verify(refresh, never()).deleteByUser(any());
        verify(tokens, never()).deleteByUser(any());
    }

    @Test
    void mergePreservesAlreadyVerifiedCanonicalEmailWithoutGuessingWebOwnership() {
        mockValidLockedPair();
        website.setPasswordHash("local-password");
        website.setEmail("same@example.com");
        legacy.setEmail("same@example.com");
        LocalDateTime proof = LocalDateTime.of(2026, 11, 7, 10, 0);
        legacy.setEmailVerifiedAt(proof);

        Integer canonicalId = service.consolidate(website, ticket);

        assertEquals(20, canonicalId);
        assertEquals(proof, legacy.getEmailVerifiedAt());
        assertNull(website.getEmailVerifiedAt());
    }

    @Test
    void conflictingCanonicalEmailRequiresExplicitResolution() {
        mockValidLockedPair();
        legacy.setEmail("discord@example.com");
        UserSocialIdentity verifiedGoogle = new UserSocialIdentity();
        verifiedGoogle.setProvider("google");
        verifiedGoogle.setProviderSubject("immutable-google-sub");
        verifiedGoogle.setUser(website);
        when(identities.findByUserIdAndProvider(10, "google"))
            .thenReturn(List.of(verifiedGoogle));
        when(identities.findByUserIdAndProvider(10, "discord")).thenReturn(List.of());

        var failure = assertThrows(ResponseStatusException.class,
            () -> service.consolidate(website, ticket));

        assertEquals("EMAIL_RECONCILIATION_REQUIRED", failure.getReason());
        assertEquals("google@example.com", website.getEmail());
        verifyNoInteractions(refresh, tokens);
        verify(audit, never()).saveAndFlush(any());
    }

    @Test
    void requiresTwoDifferentProvenUsers() {
        ticket.setStatus("STARTED");

        var failure = assertThrows(ResponseStatusException.class,
            () -> service.consolidate(website, ticket));

        assertEquals("ACCOUNT_PROOF_REQUIRED", failure.getReason());
        verifyNoInteractions(users, ownership, refresh, tokens, audit);
    }
}
