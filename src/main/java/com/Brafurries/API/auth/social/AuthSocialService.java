package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.common.AccessTokenService;
import com.Brafurries.API.auth.common.TokenService;
import com.Brafurries.API.auth.common.dto.AuthDtos.LoginResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.RegisterResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.UserResponse;
import com.Brafurries.API.auth.social.dto.AuthSocialDtos.CompleteSocialRegisterRequest;
import com.Brafurries.API.auth.social.dto.AuthSocialDtos.SocialLoginRequest;
import com.Brafurries.API.auth.social.dto.AuthSocialDtos.SocialRegisterRequest;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserRefreshToken;
import com.Brafurries.API.entity.user.UserTokenType;
import com.Brafurries.API.repository.api.ApiRolePermissionRepository;
import com.Brafurries.API.repository.api.ApiUserRoleRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRefreshTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class AuthSocialService {

    private final UserRepository userRepository;
    private final UserRefreshTokenRepository userRefreshTokenRepository;
    private final TokenService tokenService;
    private final AccessTokenService accessTokenService;
    private final ApiUserRoleRepository apiUserRoleRepository;
    private final ApiRolePermissionRepository apiRolePermissionRepository;
    private final UserDiscordRepository userDiscordRepository;
    private final UserTokenRepository userTokenRepository;
    private final Map<String, PendingSocialRegistration> pendingRegistrations = new ConcurrentHashMap<>();

    @Value("${GOOGLE_CLIENT_ID:}")
    private String googleClientId;

    private final RestClient restClient = RestClient.builder().build();

    public AuthSocialService(
        UserRepository userRepository,
        UserRefreshTokenRepository userRefreshTokenRepository,
        TokenService tokenService,
        AccessTokenService accessTokenService,
        ApiUserRoleRepository apiUserRoleRepository,
        ApiRolePermissionRepository apiRolePermissionRepository,
        UserDiscordRepository userDiscordRepository,
        UserTokenRepository userTokenRepository
    ) {
        this.userRepository = userRepository;
        this.userRefreshTokenRepository = userRefreshTokenRepository;
        this.tokenService = tokenService;
        this.accessTokenService = accessTokenService;
        this.apiUserRoleRepository = apiUserRoleRepository;
        this.apiRolePermissionRepository = apiRolePermissionRepository;
        this.userDiscordRepository = userDiscordRepository;
        this.userTokenRepository = userTokenRepository;
    }

    @Transactional
    public LoginResponse loginGoogle(SocialLoginRequest request) {
        ProviderProfile profile = validateGoogleToken(request.providerToken(), request.email(), request.displayName());
        return socialLogin(profile);
    }

    @Transactional
    public LoginResponse loginDiscord(SocialLoginRequest request) {
        ProviderProfile profile = validateDiscordToken(request.providerToken(), request.email(), request.displayName());
        return socialLogin(profile);
    }

    @Transactional
    public RegisterResponse registerGoogle(SocialRegisterRequest request) {
        validateTermsAgreement(request.termsAgreement());
        ProviderProfile profile = validateGoogleToken(request.providerToken(), request.email(), request.displayName());
        return registerSocial(profile);
    }

    @Transactional
    public RegisterResponse registerDiscord(SocialRegisterRequest request) {
        validateTermsAgreement(request.termsAgreement());
        ProviderProfile profile = validateDiscordToken(request.providerToken(), request.email(), request.displayName());
        return registerSocial(profile);
    }

    @Transactional
    public OAuth2LoginResult loginOAuth2(OAuth2AuthenticationToken authentication) {
        ProviderProfile profile = extractProviderProfile(
            authentication.getAuthorizedClientRegistrationId(),
            authentication.getPrincipal()
        );

        Optional<User> existingUser = findExistingSocialUser(profile);
        if (existingUser.isPresent()) {
            User user = updateExistingSocialUser(existingUser.get(), profile);
            return new ExistingUserLogin(issueLoginResponse(user));
        }

        return new RegistrationRequired(createPendingRegistration(profile));
    }

    @Transactional
    public LoginResponse completeSocialRegistration(CompleteSocialRegisterRequest request) {
        validateTermsAgreement(request.termsAgreement());

        PendingSocialRegistration pending = consumePendingRegistration(request.registrationToken());
        ProviderProfile profile = pending.toProviderProfile(request.displayName());
        Optional<User> existingUser = findExistingSocialUser(profile);
        if (existingUser.isPresent()) {
            User user = updateExistingSocialUser(existingUser.get(), profile);
            return issueLoginResponse(user);
        }

        User user = createSocialUser(pending.email(), request.displayName());
        linkProviderAccount(user, profile);
        return issueLoginResponse(user);
    }

    private RegisterResponse registerSocial(ProviderProfile profile) {
        Optional<User> existingUser = findExistingSocialUser(profile);
        if (existingUser.isPresent()) {
            User saved = updateExistingSocialUser(existingUser.get(), profile);
            return new RegisterResponse(saved.getId().longValue(), saved.getEmail(), "Conta ativada pelo provedor");
        }

        User saved = findOrCreateSocialUser(profile);
        return new RegisterResponse(saved.getId().longValue(), saved.getEmail(), "Conta criada e ativada pelo provedor");
    }

    private LoginResponse socialLogin(ProviderProfile profile) {
        User user = findExistingSocialUser(profile)
            .map(existingUser -> updateExistingSocialUser(existingUser, profile))
            .orElseThrow(() -> new BadCredentialsException("Credenciais invalidas"));
        return issueLoginResponse(user);
    }

    private User findOrCreateSocialUser(ProviderProfile profile) {
        User user = findExistingSocialUser(profile)
            .orElseGet(() -> createSocialUser(profile.email(), profile.displayName()));

        boolean changed = false;
        if ((user.getDisplayName() == null || user.getDisplayName().isBlank())
                && profile.displayName() != null && !profile.displayName().isBlank()) {
            user.setDisplayName(normalizeDisplayName(profile.displayName()));
            changed = true;
        }

        if (user.getEmail() != null) {
            String normalizedEmail = user.getEmail().trim().toLowerCase(Locale.ROOT);
            if (!normalizedEmail.equals(user.getEmail())) {
                user.setEmail(normalizedEmail);
                changed = true;
            }
        }

        if (changed) {
            user = userRepository.save(user);
        }

        activateVerifiedProviderAccount(user);
        linkProviderAccount(user, profile);

        return user;
    }

    private User updateExistingSocialUser(User user, ProviderProfile profile) {
        boolean changed = false;
        if ((user.getEmail() == null || user.getEmail().isBlank()) && profile.email() != null && !profile.email().isBlank()) {
            user.setEmail(profile.email());
            changed = true;
        }

        if ((user.getDisplayName() == null || user.getDisplayName().isBlank())
                && profile.displayName() != null && !profile.displayName().isBlank()) {
            user.setDisplayName(normalizeDisplayName(profile.displayName()));
            changed = true;
        }

        if (changed) {
            user = userRepository.save(user);
        }

        activateVerifiedProviderAccount(user);
        linkProviderAccount(user, profile);
        return user;
    }

    private Optional<User> findExistingSocialUser(ProviderProfile profile) {
        Optional<User> byEmail = userRepository.findByEmail(profile.email());
        Optional<User> byProviderId = findUserByProviderId(profile);

        if (byEmail.isPresent() && byProviderId.isPresent()
                && !byEmail.get().getId().equals(byProviderId.get().getId())) {
            throw new BadCredentialsException(
                "E-mail e provedor pertencem a contas distintas; regularização administrativa necessária"
            );
        }

        return byEmail.or(() -> byProviderId);
    }

    private Optional<User> findUserByProviderId(ProviderProfile profile) {
        if (!"discord".equals(profile.provider()) || profile.providerUserId() == null || profile.providerUserId().isBlank()) {
            return Optional.empty();
        }

        Long discordUserId;
        try {
            discordUserId = Long.valueOf(profile.providerUserId());
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }

        return userDiscordRepository.findByDiscordUserId(discordUserId)
            .map(UserDiscord::getUser);
    }

    private User createSocialUser(String email, String displayName) {
        User user = new User();
        user.setEmail(email.trim().toLowerCase(Locale.ROOT));
        user.setDisplayName(normalizeDisplayName(displayName));
        user.setPasswordHash(null);
        return userRepository.save(user);
    }

    private void activateVerifiedProviderAccount(User user) {
        userTokenRepository.deleteByUserAndType(user, UserTokenType.EMAIL_VERIFICATION);
    }

    private void validateTermsAgreement(Boolean termsAgreement) {
        if (!Boolean.TRUE.equals(termsAgreement)) {
            throw new IllegalArgumentException("Aceite dos termos e obrigatorio");
        }
    }

    private PendingSocialRegistration createPendingRegistration(ProviderProfile profile) {
        cleanupExpiredPendingRegistrations();
        String token = tokenService.generateToken(32);
        PendingSocialRegistration pending = new PendingSocialRegistration(
            token,
            profile.provider(),
            profile.providerUserId(),
            profile.email(),
            true,
            profile.displayName(),
            profile.avatarUrl(),
            Instant.now().plus(15, ChronoUnit.MINUTES)
        );
        pendingRegistrations.put(token, pending);
        return pending;
    }

    private PendingSocialRegistration consumePendingRegistration(String registrationToken) {
        if (registrationToken == null || registrationToken.isBlank()) {
            throw new BadCredentialsException("Token de cadastro social obrigatorio");
        }

        PendingSocialRegistration pending = pendingRegistrations.remove(registrationToken.trim());
        if (pending == null || pending.expiresAt().isBefore(Instant.now())) {
            throw new BadCredentialsException("Token de cadastro social invalido ou expirado");
        }

        return pending;
    }

    private void cleanupExpiredPendingRegistrations() {
        Instant now = Instant.now();
        pendingRegistrations.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private LoginResponse issueLoginResponse(User user) {
        if (userTokenRepository.existsByUserAndTypeAndUsedAtIsNullAndRevokedAtIsNull(
                user,
                UserTokenType.EMAIL_VERIFICATION
        )) {
            throw new BadCredentialsException("Confirme seu e-mail antes de acessar a conta");
        }

        userRefreshTokenRepository.deleteByUser(user);

        List<String> roles = apiUserRoleRepository.findRoleNamesByUserId(user.getId());
        List<String> permissions = apiRolePermissionRepository.findPermissionNamesByUserId(user.getId());
        String accessToken = tokenService.generateAccessToken(user.getEmail(), roles, permissions);
        String refreshToken = tokenService.generateToken(64);

        UserRefreshToken refreshEntity = new UserRefreshToken();
        refreshEntity.setUser(user);
        refreshEntity.setToken(refreshToken);
        refreshEntity.setExpiresAt(tokenService.refreshExpiresAt());
        userRefreshTokenRepository.save(refreshEntity);

        accessTokenService.save(accessToken, user.getEmail(), roles, permissions, tokenService.accessExpiresAt());

        return new LoginResponse(
            accessToken,
            refreshToken,
            "Bearer",
            tokenService.accessExpiresInSeconds(),
            new UserResponse(
                user.getId().longValue(),
                Optional.ofNullable(user.getDisplayName()).orElse("Usuario"),
                user.getEmail(),
                user.getProfileImageUrl(),
                roles,
                permissions
            )
        );
    }

    private ProviderProfile extractProviderProfile(String registrationId, OAuth2User oauth2User) {
        String provider = registrationId.toLowerCase(Locale.ROOT);
        Map<String, Object> attributes = oauth2User.getAttributes();

        return switch (provider) {
            case "google" -> googleProfile(attributes);
            case "discord" -> discordProfile(attributes);
            default -> throw new BadCredentialsException("Provedor social nao suportado: " + registrationId);
        };
    }

    private ProviderProfile googleProfile(Map<String, Object> attributes) {
        String email = requireVerifiedEmail(
            attributeAsString(attributes, "email"),
            attributeAsBoolean(attributes, "email_verified"),
            "Google"
        );
        String displayName = firstNonBlank(
            attributeAsString(attributes, "name"),
            attributeAsString(attributes, "given_name"),
            email.substring(0, email.indexOf('@'))
        );

        return new ProviderProfile(
            "google",
            attributeAsString(attributes, "sub"),
            email,
            displayName,
            attributeAsString(attributes, "picture")
        );
    }

    private ProviderProfile discordProfile(Map<String, Object> attributes) {
        String email = requireVerifiedEmail(
            attributeAsString(attributes, "email"),
            attributeAsBoolean(attributes, "verified"),
            "Discord"
        );
        String displayName = firstNonBlank(
            attributeAsString(attributes, "global_name"),
            attributeAsString(attributes, "username"),
            email.substring(0, email.indexOf('@'))
        );

        return new ProviderProfile(
            "discord",
            attributeAsString(attributes, "id"),
            email,
            displayName,
            discordAvatarUrl(attributeAsString(attributes, "id"), attributeAsString(attributes, "avatar"))
        );
    }

    private String requireVerifiedEmail(String email, boolean verified, String provider) {
        if (email == null || email.isBlank() || !verified) {
            throw new BadCredentialsException("Conta " + provider + " sem e-mail verificado");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void linkDiscordAccount(User user, ProviderProfile profile) {
        Long discordUserId;
        try {
            discordUserId = Long.valueOf(profile.providerUserId());
        } catch (NumberFormatException ex) {
            return;
        }

        Optional<UserDiscord> existingByDiscordId = userDiscordRepository.findByDiscordUserId(discordUserId);
        if (existingByDiscordId.isPresent()) {
            UserDiscord link = existingByDiscordId.get();
            if (!link.getUser().getId().equals(user.getId())) {
                throw new BadCredentialsException("Conta Discord ja vinculada a outro usuario");
            }
            updateDiscordLink(link, profile);
            return;
        }

        Optional<UserDiscord> existingForUser = userDiscordRepository.findByUser(user);
        if (existingForUser.isPresent()) {
            throw new BadCredentialsException(
                "E-mail e provedor pertencem a contas distintas; regularização administrativa necessária"
            );
        }

        UserDiscord link = new UserDiscord();
        link.setUser(user);
        link.setDiscordUserId(discordUserId);
        updateDiscordLink(link, profile);
    }

    private void updateDiscordLink(UserDiscord link, ProviderProfile profile) {
        String username = firstNonBlank(profile.displayName(), "discord");
        link.setUsername(truncate(username, 32));
        link.setDisplayName(truncate(profile.displayName(), 32));
        userDiscordRepository.save(link);
    }

    private void linkProviderAccount(User user, ProviderProfile profile) {
        if ("discord".equals(profile.provider()) && profile.providerUserId() != null) {
            linkDiscordAccount(user, profile);
        }
    }

    private ProviderProfile validateGoogleToken(String providerToken, String requestedEmail, String requestedDisplayName) {
        String token = requireProviderToken(providerToken);
        GoogleTokenInfoResponse response;
        try {
            response = restClient.get()
                .uri("https://oauth2.googleapis.com/tokeninfo?id_token={token}", token)
                .retrieve()
                .body(GoogleTokenInfoResponse.class);
        } catch (RuntimeException ex) {
            throw new BadCredentialsException("Token Google invalido", ex);
        }

        if (response == null || response.email() == null || !Boolean.parseBoolean(response.emailVerified())) {
            throw new BadCredentialsException("Token Google invalido");
        }

        if (!response.email().equalsIgnoreCase(requestedEmail)) {
            throw new BadCredentialsException("E-mail nao confere com o token Google");
        }

        if (!googleClientId.isBlank() && !googleClientId.equals(response.audience())) {
            throw new BadCredentialsException("Token Google emitido para outro client_id");
        }

        String normalizedEmail = response.email().trim().toLowerCase(Locale.ROOT);
        String displayName = firstNonBlank(
            response.name(),
            response.givenName(),
            requestedDisplayName,
            normalizedEmail.substring(0, normalizedEmail.indexOf('@'))
        );
        return new ProviderProfile("google", response.subject(), normalizedEmail, displayName, response.picture());
    }

    private ProviderProfile validateDiscordToken(String providerToken, String requestedEmail, String requestedDisplayName) {
        String token = requireProviderToken(providerToken);
        DiscordUserResponse response;
        try {
            response = restClient.get()
                .uri("https://discord.com/api/users/@me")
                .header("Authorization", "Bearer " + token)
                .retrieve()
                .body(DiscordUserResponse.class);
        } catch (RuntimeException ex) {
            throw new BadCredentialsException("Token Discord invalido", ex);
        }

        if (response == null || response.email() == null || !response.verified()) {
            throw new BadCredentialsException("Conta Discord sem e-mail verificado");
        }

        if (!response.email().equalsIgnoreCase(requestedEmail)) {
            throw new BadCredentialsException("E-mail nao confere com o token Discord");
        }

        String normalizedEmail = response.email().trim().toLowerCase(Locale.ROOT);
        String displayName = firstNonBlank(
            response.globalName(),
            response.username(),
            requestedDisplayName,
            normalizedEmail.substring(0, normalizedEmail.indexOf('@'))
        );
        return new ProviderProfile(
            "discord",
            response.id(),
            normalizedEmail,
            displayName,
            discordAvatarUrl(response.id(), response.avatar())
        );
    }

    private String requireProviderToken(String providerToken) {
        if (providerToken == null || providerToken.isBlank()) {
            throw new BadCredentialsException("Token do provedor e obrigatorio");
        }
        return providerToken.trim();
    }

    private String attributeAsString(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        return value == null ? null : value.toString();
    }

    private boolean attributeAsBoolean(Map<String, Object> attributes, String key) {
        Object value = attributes.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private String normalizeDisplayName(String value) {
        String normalized = firstNonBlank(value);
        return normalized == null ? null : truncate(normalized, 32);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private String discordAvatarUrl(String discordUserId, String avatarHash) {
        if (discordUserId == null || discordUserId.isBlank() || avatarHash == null || avatarHash.isBlank()) {
            return null;
        }

        String extension = avatarHash.startsWith("a_") ? "gif" : "png";
        return "https://cdn.discordapp.com/avatars/" + discordUserId + "/" + avatarHash + "." + extension;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GoogleTokenInfoResponse(
        @JsonProperty("sub") String subject,
        String email,
        String name,
        @JsonProperty("given_name") String givenName,
        String picture,
        @JsonProperty("email_verified") String emailVerified,
        @JsonProperty("aud") String audience
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record DiscordUserResponse(
        String id,
        String email,
        String username,
        @JsonProperty("global_name") String globalName,
        String avatar,
        boolean verified
    ) {
    }

    public sealed interface OAuth2LoginResult permits ExistingUserLogin, RegistrationRequired {
    }

    public record ExistingUserLogin(LoginResponse loginResponse) implements OAuth2LoginResult {
    }

    public record RegistrationRequired(PendingSocialRegistration pendingRegistration) implements OAuth2LoginResult {
    }

    public record PendingSocialRegistration(
        String token,
        String provider,
        String providerUserId,
        String email,
        boolean emailVerified,
        String displayName,
        String avatarUrl,
        Instant expiresAt
    ) {
        private ProviderProfile toProviderProfile(String selectedDisplayName) {
            return new ProviderProfile(provider, providerUserId, email, firstNonBlank(selectedDisplayName, displayName), avatarUrl);
        }

        private String firstNonBlank(String... values) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    return value.trim();
                }
            }
            return null;
        }
    }

    private record ProviderProfile(String provider, String providerUserId, String email, String displayName, String avatarUrl) {
    }
}
