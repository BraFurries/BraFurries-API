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
