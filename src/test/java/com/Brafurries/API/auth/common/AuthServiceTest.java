package com.Brafurries.API.auth.common;

import com.Brafurries.API.auth.common.dto.AuthDtos.ConfirmEmailRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.LoginRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.RegisterRequest;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserToken;
import com.Brafurries.API.entity.user.UserTokenType;
import com.Brafurries.API.repository.api.ApiRolePermissionRepository;
import com.Brafurries.API.repository.api.ApiUserRoleRepository;
import com.Brafurries.API.repository.user.UserRefreshTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserSocialIdentityRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserDiscordRepository userDiscordRepository;
    @Mock
    private UserSocialIdentityRepository socialIdentityRepository;

    @Mock
    private UserTokenRepository userTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserRefreshTokenRepository userRefreshTokenRepository;

    @Mock
    private ApiUserRoleRepository apiUserRoleRepository;

    @Mock
    private ApiRolePermissionRepository apiRolePermissionRepository;

    @Mock
    private TokenService tokenService;

    @Mock
    private AccessTokenService accessTokenService;

    @Mock
    private JavaMailSender mailSender;

    private AuthService authService;
    private Validator validator;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
            userRepository,
            userDiscordRepository,
            socialIdentityRepository,
            userTokenRepository,
            passwordEncoder,
            userRefreshTokenRepository,
            apiUserRoleRepository,
            apiRolePermissionRepository,
            tokenService,
            accessTokenService,
            mailSender,
            "https://app.example",
            "noreply@example.com",
            24
        );
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void expiredLocalEmailConfirmationMustNotDeleteAnyLegacyAccount() {
        authService.cleanupExpiredPendingEmailRegistrations();
        verify(userRepository, never()).delete(org.mockito.ArgumentMatchers.any(User.class));
        verify(userTokenRepository, never()).deleteByUser(org.mockito.ArgumentMatchers.any(User.class));
        verify(userRefreshTokenRepository, never()).deleteByUser(org.mockito.ArgumentMatchers.any(User.class));
        verify(userTokenRepository, never())
            .findByTypeAndUsedAtIsNullAndRevokedAtIsNullAndExpiresAtBefore(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void passwordOwnerCanRequestNewVerificationAfterEarlierTokenExpired() {
        User pending = new User();
        pending.setId(42);
        pending.setEmail("chosen@example.com");
        pending.setPasswordHash("hash");
        when(userRepository.findByEmail("chosen@example.com")).thenReturn(Optional.of(pending));
        when(userRepository.findByIdForUpdate(42)).thenReturn(Optional.of(pending));
        when(passwordEncoder.matches("strong-password", "hash")).thenReturn(true);
        when(userTokenRepository.existsByUserAndTypeAndUsedAtIsNullAndRevokedAtIsNull(
            pending, UserTokenType.EMAIL_VERIFICATION)).thenReturn(true);
        when(userTokenRepository.findByUserAndTypeAndUsedAtIsNullAndRevokedAtIsNull(
            pending, UserTokenType.EMAIL_VERIFICATION)).thenReturn(List.of());
        when(tokenService.generateToken(48)).thenReturn("new-verification-token");
        when(userTokenRepository.save(org.mockito.ArgumentMatchers.any(UserToken.class)))
            .thenAnswer(call -> call.getArgument(0));

        var result = authService.resendEmailConfirmation(
            new com.Brafurries.API.auth.common.dto.AuthDtos.ResendEmailConfirmationRequest(
                "chosen@example.com", "strong-password"));

        assertTrue(result.message().contains("enviarmos") || result.message().contains("enviaremos"));
        verify(userTokenRepository, org.mockito.Mockito.atLeastOnce())
            .deleteByUserAndType(pending, UserTokenType.EMAIL_VERIFICATION);
        verify(mailSender).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
    }

    @Test
    void registerCommonCreatesPendingEmailVerificationTokenAndSendsEmail() {
        when(userTokenRepository.findByTypeAndUsedAtIsNullAndRevokedAtIsNullAndExpiresAtBefore(any(), any())).thenReturn(List.of());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret")).thenReturn("hash");
        when(tokenService.generateToken(48)).thenReturn("verification-token");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(10);
            return user;
        });
        when(userTokenRepository.save(any(UserToken.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var response = authService.registerCommon(new RegisterRequest(
            "User",
            "USER@example.com",
            "secret",
            "secret",
            true
        ));

        assertEquals(10L, response.id());
        assertEquals("user@example.com", response.email());

        ArgumentCaptor<UserToken> tokenCaptor = ArgumentCaptor.forClass(UserToken.class);
        verify(userTokenRepository).save(tokenCaptor.capture());
        assertEquals(UserTokenType.EMAIL_VERIFICATION, tokenCaptor.getValue().getType());
        assertEquals("verification-token", tokenCaptor.getValue().getToken());
        assertEquals("user@example.com", tokenCaptor.getValue().getUser().getEmail());

        ArgumentCaptor<SimpleMailMessage> messageCaptor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        assertTrue(messageCaptor.getValue().getText().contains("https://app.example/confirm-email?token=verification-token"));
    }

    @Test
    void registerCommonRequiresTermsAgreement() {
        var violations = validator.validate(new RegisterRequest(
            "User",
            "user@example.com",
            "secret",
            "secret",
            null
        ));

        assertTrue(violations.stream().anyMatch(violation -> violation.getPropertyPath().toString().equals("termsAgreement")));
    }

    @Test
    void registerCommonReturnsServiceUnavailableWhenVerificationEmailCannotBeSent() {
        when(userTokenRepository.findByTypeAndUsedAtIsNullAndRevokedAtIsNullAndExpiresAtBefore(any(), any())).thenReturn(List.of());
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret")).thenReturn("hash");
        when(tokenService.generateToken(48)).thenReturn("verification-token");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId(10);
            return user;
        });
        when(userTokenRepository.save(any(UserToken.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doThrow(new MailSendException("smtp unavailable")).when(mailSender).send(any(SimpleMailMessage.class));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () ->
            authService.registerCommon(new RegisterRequest(
                "User",
                "user@example.com",
                "secret",
                "secret",
                true
            ))
        );

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
        assertEquals("Nao foi possivel enviar o e-mail. Tente novamente mais tarde.", exception.getReason());
    }

    @Test
    void loginCommonRejectsUserWithPendingEmailVerificationToken() {
        User user = new User();
        user.setId(10);
        user.setEmail("user@example.com");
        user.setPasswordHash("hash");

        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);
        when(userTokenRepository.existsByUserAndTypeAndUsedAtIsNullAndRevokedAtIsNull(
            user,
            UserTokenType.EMAIL_VERIFICATION
        )).thenReturn(true);

        assertThrows(BadCredentialsException.class, () ->
            authService.loginCommon(new LoginRequest("user@example.com", "secret", true))
        );

        verify(userRefreshTokenRepository, never()).deleteByUser(user);
    }

    @Test
    void socialOnlyAccountCanRecoverSiteAccessByExistingEmailResetFlow() {
        User originalGoogle = new User();
        originalGoogle.setId(10);
        originalGoogle.setEmail("google@example.com");
        originalGoogle.setPasswordHash(null);
        when(userRepository.findByEmail("google@example.com")).thenReturn(Optional.of(originalGoogle));
        when(tokenService.generateToken(48)).thenReturn("recovery-token");
        when(userTokenRepository.save(any(UserToken.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        var resetRequest = new com.Brafurries.API.auth.common.dto.AuthDtos.ForgotPasswordRequest(
            "GOOGLE@EXAMPLE.COM");

        authService.forgotPassword(resetRequest);

        ArgumentCaptor<UserToken> created = ArgumentCaptor.forClass(UserToken.class);
        verify(userTokenRepository).save(created.capture());
        assertEquals(UserTokenType.PASSWORD_RESET, created.getValue().getType());
        assertEquals(originalGoogle, created.getValue().getUser());
        assertEquals("recovery-token", created.getValue().getToken());
        assertTrue(created.getValue().getExpiresAt().isAfter(Instant.now()));
        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(sent.capture());
        assertTrue(sent.getValue().getText().contains("/reset-password?token=recovery-token"));

        when(userTokenRepository.findByTokenAndType("recovery-token", UserTokenType.PASSWORD_RESET))
            .thenReturn(Optional.of(created.getValue()));
        when(passwordEncoder.encode("new-secure-password")).thenReturn("new-password-hash");
        authService.resetPassword(
            new com.Brafurries.API.auth.common.dto.AuthDtos.ResetPasswordRequest(
                "recovery-token", "new-secure-password"));

        assertEquals("new-password-hash", originalGoogle.getPasswordHash());
        assertTrue(created.getValue().getUsedAt() != null);
        verify(userRepository).save(originalGoogle);
    }

    @Test
    void previouslyMergedAccountCannotAuthenticateWithOldPassword() {
        User absorbed = new User();
        absorbed.setId(10);
        absorbed.setEmail("absorbed@example.com");
        absorbed.setPasswordHash("legacy-hash");
        User canonical = new User();
        canonical.setId(20);
        absorbed.setMergedIntoUser(canonical);
        when(userRepository.findByEmail("absorbed@example.com"))
            .thenReturn(Optional.of(absorbed));
        when(passwordEncoder.matches("secret", "legacy-hash")).thenReturn(true);

        assertThrows(BadCredentialsException.class, () ->
            authService.loginCommon(new LoginRequest("absorbed@example.com", "secret", true)));
        verify(userRefreshTokenRepository, never()).deleteByUser(absorbed);
    }

    @Test
    void passwordResetCannotReactivateHistoricalAbsorbedIdentity() {
        User absorbed = new User();
        absorbed.setId(10);
        User canonical = new User();
        canonical.setId(20);
        absorbed.setMergedIntoUser(canonical);
        UserToken oldRecovery = new UserToken();
        oldRecovery.setUser(absorbed);
        oldRecovery.setType(UserTokenType.PASSWORD_RESET);
        oldRecovery.setExpiresAt(Instant.now().plusSeconds(60));
        when(userTokenRepository.findByTokenAndType("old-token", UserTokenType.PASSWORD_RESET))
            .thenReturn(Optional.of(oldRecovery));

        assertThrows(BadCredentialsException.class, () ->
            authService.resetPassword(
                new com.Brafurries.API.auth.common.dto.AuthDtos.ResetPasswordRequest(
                    "old-token", "new-password")));
        verify(userRepository, never()).save(absorbed);
    }

    @Test
    void confirmEmailConsumesTokenAndAllowsAccountActivation() {
        User user = new User();
        user.setId(10);
        user.setEmail("user@example.com");

        UserToken token = new UserToken();
        token.setUser(user);
        token.setType(UserTokenType.EMAIL_VERIFICATION);
        token.setToken("verification-token");
        token.setExpiresAt(Instant.now().plusSeconds(60));

        when(userTokenRepository.findByTokenAndType("verification-token", UserTokenType.EMAIL_VERIFICATION)).thenReturn(Optional.of(token));

        var response = authService.confirmEmail(new ConfirmEmailRequest("verification-token"));

        assertEquals("E-mail confirmado com sucesso", response.message());
        assertTrue(token.getUsedAt() != null);
        verify(userTokenRepository).save(token);
        verify(userTokenRepository).deleteByUserAndType(user, UserTokenType.EMAIL_VERIFICATION);
    }
}
