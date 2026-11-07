package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.common.AuthService;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserTokenType;
import com.Brafurries.API.repository.user.UserRefreshTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import java.time.LocalDateTime;
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
class LocalEmailVerificationServiceTest {
    @Mock UserRepository users;
    @Mock UserRefreshTokenRepository refreshTokens;
    @Mock UserTokenRepository tokens;
    @Mock PasswordEncoder passwords;
    @Mock AuthService auth;
    LocalEmailVerificationService service;
    User legacy;
    Authentication session;

    @BeforeEach void setup() {
        service = new LocalEmailVerificationService(users, refreshTokens, tokens, passwords, auth);
        legacy = new User();
        legacy.setId(9);
        legacy.setEmail("imported@example.com");
        legacy.setPasswordHash("hash");
        legacy.setAuthVersion(0L);
        session = new UsernamePasswordAuthenticationToken("imported@example.com", null, List.of());
    }

    private void authenticated() {
        when(users.findByEmail("imported@example.com")).thenReturn(Optional.of(legacy));
    }

    private void passwordVerified() {
        authenticated();
        when(users.findByIdForUpdate(9)).thenReturn(Optional.of(legacy));
        when(passwords.matches("correct-password", "hash")).thenReturn(true);
    }

    @Test void unknownLegacyEmailIsNotReportedAsVerified() {
        authenticated();
        var status = service.status(session);
        assertEquals("imported@example.com", status.email());
        assertFalse(status.verified());
    }

    @Test void userCanExplicitlySelectAndVerifyAChosenAddress() {
        passwordVerified();
        var result = service.request(session, "New@Example.com", "correct-password");
        assertEquals("VERIFICATION_SENT_RELOGIN_REQUIRED", result.status());
        assertEquals("new@example.com", legacy.getEmail());
        assertNull(legacy.getEmailVerifiedAt());
        assertEquals(1L, legacy.getAuthVersion());
        verify(users).saveAndFlush(legacy);
        verify(refreshTokens).deleteByUser(legacy);
        verify(auth).requireLocalEmailConfirmation(legacy);
    }

    @Test void competingEmailOwnerIsNeverClaimed() {
        passwordVerified();
        User other = new User();
        other.setId(17);
        when(users.findByEmail("other@example.com")).thenReturn(Optional.of(other));
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.request(session, "other@example.com", "correct-password"));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertEquals("imported@example.com", legacy.getEmail());
        verifyNoInteractions(refreshTokens, auth);
    }

    @Test void missingLocalPasswordCannotProveLegacyEmail() {
        authenticated();
        when(users.findByIdForUpdate(9)).thenReturn(Optional.of(legacy));
        assertThrows(BadCredentialsException.class, () ->
            service.request(session, "new@example.com", "incorrect"));
        verifyNoInteractions(refreshTokens, auth);
    }

    @Test void confirmedEmailDoesNotNeedAnUnpromptedChange() {
        passwordVerified();
        legacy.setEmailVerifiedAt(LocalDateTime.now());
        assertEquals("ALREADY_VERIFIED",
            service.request(session, "new@example.com", "correct-password").status());
        assertEquals("imported@example.com", legacy.getEmail());
        verifyNoInteractions(refreshTokens, auth);
    }

    @Test void anotherPendingLocalVerificationCannotBeBypassed() {
        passwordVerified();
        when(tokens.existsByUserAndTypeAndUsedAtIsNullAndRevokedAtIsNull(
            legacy, UserTokenType.EMAIL_VERIFICATION)).thenReturn(true);
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
            () -> service.request(session, "new@example.com", "correct-password"));
        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verifyNoInteractions(refreshTokens, auth);
    }
}
