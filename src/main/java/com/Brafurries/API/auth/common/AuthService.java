package com.Brafurries.API.auth.common;

import com.Brafurries.API.auth.common.dto.AuthDtos.ClientTokenRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.ClientTokenResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.ConfirmEmailRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.ConfirmEmailResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.ForgotPasswordRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.ForgotPasswordResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.LoginRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.LoginResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.LogoutRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.LogoutResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.RefreshTokenRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.RefreshTokenResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.RegisterRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.RegisterResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.ResetPasswordRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.ResetPasswordResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.UserResponse;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserRefreshToken;
import com.Brafurries.API.entity.user.UserToken;
import com.Brafurries.API.entity.user.UserTokenType;
import com.Brafurries.API.repository.api.ApiRolePermissionRepository;
import com.Brafurries.API.repository.api.ApiUserRoleRepository;
import com.Brafurries.API.repository.user.UserRefreshTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final UserTokenRepository userTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserRefreshTokenRepository userRefreshTokenRepository;
    private final ApiUserRoleRepository apiUserRoleRepository;
    private final ApiRolePermissionRepository apiRolePermissionRepository;
    private final TokenService tokenService;
    private final AccessTokenService accessTokenService;
    private final JavaMailSender mailSender;
    private final String frontEndUrl;
    private final String mailFrom;
    private final long emailVerificationExpirationHours;

    public AuthService(
        UserRepository userRepository,
        UserTokenRepository userTokenRepository,
        PasswordEncoder passwordEncoder,
        UserRefreshTokenRepository userRefreshTokenRepository,
        ApiUserRoleRepository apiUserRoleRepository,
        ApiRolePermissionRepository apiRolePermissionRepository,
        TokenService tokenService,
        AccessTokenService accessTokenService,
        JavaMailSender mailSender,
        @Value("${app.frontend-url}") String frontEndUrl,
        @Value("${spring.mail.username}") String mailFrom,
        @Value("${app.auth.email-verification-expiration-hours:24}") long emailVerificationExpirationHours
    ) {
        this.userRepository = userRepository;
        this.userTokenRepository = userTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.userRefreshTokenRepository = userRefreshTokenRepository;
        this.apiUserRoleRepository = apiUserRoleRepository;
        this.apiRolePermissionRepository = apiRolePermissionRepository;
        this.tokenService = tokenService;
        this.accessTokenService = accessTokenService;
        this.mailSender = mailSender;
        this.frontEndUrl = frontEndUrl;
        this.mailFrom = mailFrom;
        this.emailVerificationExpirationHours = emailVerificationExpirationHours;
    }

    @Transactional
    public LoginResponse loginCommon(LoginRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();
        User user = userRepository.findByEmail(normalizedEmail)
            .orElseThrow(() -> new BadCredentialsException("Credenciais inválidas"));

        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BadCredentialsException("Credenciais inválidas");
        }
        ensureAccountConfirmed(user);

        userRefreshTokenRepository.deleteByUser(user);

        List<String> roles = apiUserRoleRepository.findRoleNamesByUserId(user.getId());
        List<String> permissions = apiRolePermissionRepository.findPermissionNamesByUserId(user.getId());
        String accessToken = tokenService.generateAccessToken(user.getEmail(), roles, permissions);

        String refreshToken = null;
        if (request.rememberMe() == null || request.rememberMe()) {
            refreshToken = tokenService.generateToken(64);

            UserRefreshToken refreshEntity = new UserRefreshToken();
            refreshEntity.setUser(user);
            refreshEntity.setToken(refreshToken);
            refreshEntity.setExpiresAt(tokenService.refreshExpiresAt());
            userRefreshTokenRepository.save(refreshEntity);
        }

        Instant accessExpiresAt = tokenService.accessExpiresAt();
        accessTokenService.save(accessToken, user.getEmail(), roles, permissions, accessExpiresAt);

        return new LoginResponse(
            accessToken,
            refreshToken,
            "Bearer",
            tokenService.accessExpiresInSeconds(),
            new UserResponse(
                user.getId().longValue(),
                Optional.ofNullable(user.getDisplayName()).orElse("Usuário"),
                user.getEmail(),
                user.getProfileImageUrl(),
                roles,
                permissions
            )
        );
    }
    @Transactional
    public RegisterResponse registerCommon(RegisterRequest request) {
        cleanupExpiredPendingEmailRegistrations();

        String normalizedDisplayName = request.displayName().trim();
        String normalizedEmail = request.email().trim().toLowerCase();

        if (!request.password().equals(request.passwordConfirmation())) {
            throw new IllegalArgumentException("A confirmação de senha não confere");
        }

        if (userRepository.findByEmail(normalizedEmail).isPresent()) {
            throw new IllegalArgumentException("E-mail já cadastrado");
        }

        User user = new User();
        user.setDisplayName(normalizedDisplayName);
        user.setEmail(normalizedEmail);
        user.setPasswordHash(passwordEncoder.encode(request.password()));

        User saved = userRepository.save(user);
        UserToken verificationToken = new UserToken();
        verificationToken.setUser(saved);
        verificationToken.setType(UserTokenType.EMAIL_VERIFICATION);
        verificationToken.setToken(tokenService.generateToken(48));
        verificationToken.setExpiresAt(Instant.now().plus(emailVerificationExpirationHours, ChronoUnit.HOURS));

        UserToken savedToken = userTokenRepository.save(verificationToken);
        sendEmailVerificationEmail(saved.getEmail(), savedToken.getToken());

        return new RegisterResponse(
            saved.getId().longValue(),
            saved.getEmail(),
            "Cadastro recebido. Confirme seu e-mail para ativar a conta."
        );
    }

    @Transactional
    public ConfirmEmailResponse confirmEmail(ConfirmEmailRequest request) {
        UserToken verificationToken = userTokenRepository.findByTokenAndType(request.token(), UserTokenType.EMAIL_VERIFICATION)
            .orElseThrow(() -> new IllegalArgumentException("Token invalido"));

        if (verificationToken.getUsedAt() != null
                || verificationToken.getRevokedAt() != null
                || verificationToken.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Token expirado ou ja utilizado");
        }

        User user = verificationToken.getUser();
        verificationToken.setUsedAt(Instant.now());
        userTokenRepository.save(verificationToken);
        userTokenRepository.deleteByUserAndType(user, UserTokenType.EMAIL_VERIFICATION);

        return new ConfirmEmailResponse("E-mail confirmado com sucesso");
    }


    public ForgotPasswordResponse forgotPassword(ForgotPasswordRequest request) {
        String normalizedEmail = request.email().trim().toLowerCase();

        userRepository.findByEmail(normalizedEmail).ifPresent(user -> {
            if (isEmailVerificationPending(user)) {
                return;
            }

            userTokenRepository.deleteByUserAndType(user, UserTokenType.PASSWORD_RESET);

            UserToken resetToken = new UserToken();
            resetToken.setUser(user);
            resetToken.setType(UserTokenType.PASSWORD_RESET);
            resetToken.setToken(tokenService.generateToken(48));
            resetToken.setExpiresAt(Instant.now().plus(30, ChronoUnit.MINUTES));

            UserToken savedToken = userTokenRepository.save(resetToken);
            sendResetPasswordEmail(user.getEmail(), savedToken.getToken());
        });

        return new ForgotPasswordResponse("Se o e-mail existir, enviaremos o link para redefinição de senha.");
    }

    @Transactional
    public ResetPasswordResponse resetPassword(ResetPasswordRequest request) {
        UserToken resetToken = userTokenRepository.findByTokenAndType(request.token(), UserTokenType.PASSWORD_RESET)
            .orElseThrow(() -> new IllegalArgumentException("Token inválido"));

        if (resetToken.getUsedAt() != null
                || resetToken.getRevokedAt() != null
                || resetToken.getExpiresAt().isBefore(Instant.now())) {
            throw new IllegalArgumentException("Token expirado ou já utilizado");
        }

        User user = resetToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        userRepository.save(user);

        resetToken.setUsedAt(Instant.now());
        userTokenRepository.save(resetToken);

        return new ResetPasswordResponse("Senha alterada com sucesso");
    }

    public RefreshTokenResponse refreshToken(RefreshTokenRequest request) {
        UserRefreshToken savedToken = userRefreshTokenRepository.findByToken(request.refreshToken())
            .orElseThrow(() -> new BadCredentialsException("Refresh token inválido"));

        if (savedToken.getRevokedAt() != null || savedToken.getExpiresAt().isBefore(Instant.now())) {
            throw new BadCredentialsException("Refresh token inválido ou expirado");
        }

        ensureAccountConfirmed(savedToken.getUser());

        savedToken.setRevokedAt(Instant.now());
        userRefreshTokenRepository.save(savedToken);

        String newRefreshToken = tokenService.generateToken(64);
        List<String> roles = apiUserRoleRepository.findRoleNamesByUserId(savedToken.getUser().getId());
        List<String> permissions = apiRolePermissionRepository.findPermissionNamesByUserId(savedToken.getUser().getId());
        String newAccessToken = tokenService.generateAccessToken(savedToken.getUser().getEmail(), roles, permissions);
        UserRefreshToken rotatedToken = new UserRefreshToken();
        rotatedToken.setUser(savedToken.getUser());
        rotatedToken.setToken(newRefreshToken);
        rotatedToken.setExpiresAt(tokenService.refreshExpiresAt());
        userRefreshTokenRepository.save(rotatedToken);
        
        accessTokenService.save(newAccessToken, savedToken.getUser().getEmail(), roles, permissions, tokenService.accessExpiresAt());

        return new RefreshTokenResponse(
            newAccessToken,
            newRefreshToken,
            "Bearer",
            tokenService.accessExpiresInSeconds()
        );
    }

    public LogoutResponse logout(LogoutRequest request) {
        userRefreshTokenRepository.findByToken(request.refreshToken()).ifPresent(token -> {
            token.setRevokedAt(Instant.now());
            userRefreshTokenRepository.save(token);
        });

        return new LogoutResponse("Logout realizado com sucesso");
    }

    public ClientTokenResponse clientToken(ClientTokenRequest request) {
        if (!"client_credentials".equals(request.grantType())) {
            throw new IllegalArgumentException("grant_type inválido: use client_credentials");
        }

        return new ClientTokenResponse(
            "jwt_access_token",
            "Bearer",
            3600,
            "events:create events:view"
        );
    }

    private void sendResetPasswordEmail(String recipient, String token) {
        String resetLink = String.format("%s?token=%s", frontEndUrl + "/reset-password", token);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(recipient);
        message.setSubject("BraFurries - Recuperação de Conta");
        message.setText(
            "Olá!\n\n" +
                "Recebemos um pedido para redefinir a senha da sua conta na BraFurries.\n\n" +
                "Para criar uma nova senha, clique no link abaixo:\n" +
                resetLink + "\n\n" +
                "Este link é válido por 30 minutos e pode ser usado apenas uma vez.\n\n" +
                "Se não foi você quem solicitou, pode ignorar este e-mail com segurança. " +
                "Sua senha atual permanecerá a mesma.\n\n" +
                "Atenciosamente,\n" +
                "Equipe BraFurries"
        );

        sendMailOrFail(message);
    }

    private void sendEmailVerificationEmail(String recipient, String token) {
        String confirmationLink = String.format("%s?token=%s", frontEndUrl + "/confirm-email", token);

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(recipient);
        message.setSubject("BraFurries - Confirmacao de e-mail");
        message.setText(
            "Ola!\n\n" +
                "Recebemos um cadastro com este e-mail na BraFurries.\n\n" +
                "Para ativar sua conta, confirme o e-mail pelo link abaixo:\n" +
                confirmationLink + "\n\n" +
                "Este link e valido por " + emailVerificationExpirationHours + " horas. " +
                "Cadastros nao confirmados dentro desse prazo sao removidos automaticamente.\n\n" +
                "Se nao foi voce quem solicitou, ignore este e-mail.\n\n" +
                "Atenciosamente,\n" +
                "Equipe BraFurries"
        );

        sendMailOrFail(message);
    }

    private void sendMailOrFail(SimpleMailMessage message) {
        try {
            mailSender.send(message);
        } catch (MailException ex) {
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Nao foi possivel enviar o e-mail. Tente novamente mais tarde.",
                ex
            );
        }
    }

    private void ensureAccountConfirmed(User user) {
        if (isEmailVerificationPending(user)) {
            throw new BadCredentialsException("Confirme seu e-mail antes de acessar a conta");
        }
    }

    private boolean isEmailVerificationPending(User user) {
        return userTokenRepository.existsByUserAndTypeAndUsedAtIsNullAndRevokedAtIsNull(
            user,
            UserTokenType.EMAIL_VERIFICATION
        );
    }

    @Scheduled(fixedDelayString = "${app.auth.email-verification-cleanup-ms:3600000}")
    @Transactional
    public void cleanupExpiredPendingEmailRegistrations() {
        Instant now = Instant.now();
        List<UserToken> expiredTokens = userTokenRepository
            .findByTypeAndUsedAtIsNullAndRevokedAtIsNullAndExpiresAtBefore(
                UserTokenType.EMAIL_VERIFICATION,
                now
            );

        for (UserToken expiredToken : expiredTokens) {
            User user = expiredToken.getUser();
            userTokenRepository.deleteByUser(user);
            userRefreshTokenRepository.deleteByUser(user);
            userRepository.delete(user);
        }

        userTokenRepository.deleteByTypeAndExpiresAtBefore(UserTokenType.PASSWORD_RESET, now);
        userTokenRepository.deleteByUsedAtIsNotNullAndExpiresAtBefore(now);
        userTokenRepository.deleteByRevokedAtIsNotNullAndExpiresAtBefore(now);
    }
}
