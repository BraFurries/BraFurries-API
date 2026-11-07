package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.common.AccessTokenService;
import com.Brafurries.API.auth.common.AuthService;
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
import com.Brafurries.API.entity.user.UserSocialOnboardingChallenge;
import com.Brafurries.API.repository.api.ApiRolePermissionRepository;
import com.Brafurries.API.repository.api.ApiUserRoleRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserRefreshTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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
    private final DiscordSelfServiceLinkService accountLinks;
    private final VerifiedSocialIdentityService verifiedIdentities;
    private final SocialOnboardingChallengeService onboarding;
    private final AuthService localAuth;
    private final PasswordEncoder passwordEncoder;

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
        UserTokenRepository userTokenRepository,
        DiscordSelfServiceLinkService accountLinks,
        VerifiedSocialIdentityService verifiedIdentities,
        SocialOnboardingChallengeService onboarding,
        @Lazy AuthService localAuth,
        @Lazy PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.userRefreshTokenRepository = userRefreshTokenRepository;
        this.tokenService = tokenService;
        this.accessTokenService = accessTokenService;
        this.apiUserRoleRepository = apiUserRoleRepository;
        this.apiRolePermissionRepository = apiRolePermissionRepository;
        this.userDiscordRepository = userDiscordRepository;
        this.userTokenRepository = userTokenRepository;
        this.accountLinks = accountLinks;
        this.verifiedIdentities = verifiedIdentities;
        this.onboarding = onboarding;
        this.localAuth = localAuth;
        this.passwordEncoder = passwordEncoder;
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
        throw new BadCredentialsException("SOCIAL_ONBOARDING_REQUIRED");
    }

    @Transactional
    public RegisterResponse registerDiscord(SocialRegisterRequest request) {
        validateTermsAgreement(request.termsAgreement());
        ProviderProfile profile = validateDiscordToken(request.providerToken(), request.email(), request.displayName());
        throw new BadCredentialsException("SOCIAL_ONBOARDING_REQUIRED");
    }

    /**
     * Two independent proofs: a valid BraFurries site session and a Google
     * ID token verified for this application's configured client ID.
     * Does not change the current session or merge any other users.
     */
    @Transactional
    public String linkGoogleToAuthenticatedAccount(Authentication authentication, String googleIdToken) {
        if (authentication == null || !authentication.isAuthenticated()
            || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Faça login antes de vincular o Google");
        }
        if (googleClientId == null || googleClientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "A vinculação Google está temporariamente indisponível");
        }

        // Verify OAuth first; never treat a supplied e-mail or subject as proof.
        ProviderProfile verifiedGoogle = validateGoogleToken(googleIdToken, null, null);
        User actor = userRepository.findByEmail(authentication.getName().trim().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão não reconhecida"));
        User canonical = userRepository.findByIdForUpdate(actor.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Conta alterada"));

        verifiedIdentities.bindProvenSubject(canonical, "google",
            verifiedGoogle.providerUserId(), verifiedGoogle.email());
        return "VERIFIED";
    }

    @Transactional
    public OAuth2LoginResult loginOAuth2(OAuth2AuthenticationToken authentication) {
        ProviderProfile profile = extractProviderProfile(
            authentication.getAuthorizedClientRegistrationId(),
            authentication.getPrincipal()
        );

        Optional<User> existingUser = findExistingSocialUser(profile);
        if (existingUser.isPresent()) {
            User user = existingUser.get();
            if (user.getMergedIntoUser() != null) {
                throw new BadCredentialsException("ACCOUNT_CONSOLIDATED");
            }
            if (needsOnboarding(user)) {
                return new RegistrationRequired(createPendingRegistration(profile));
            }
            updateExistingSocialUser(user, profile);
            return new ExistingUserLogin(issueLoginResponse(user));
        }

        return new RegistrationRequired(createPendingRegistration(profile));
    }

    @Transactional(readOnly = true)
    public SocialOnboardingChallengeService.Preview previewSocialRegistration(String token) {
        return onboarding.preview(token);
    }

    @Transactional
    public RegisterResponse completeSocialRegistration(CompleteSocialRegisterRequest request) {
        validateTermsAgreement(request.termsAgreement());
        if (!request.password().equals(request.passwordConfirmation())
            || request.password().length() < 8) {
            throw new IllegalArgumentException("Confirme uma senha de pelo menos oito caracteres");
        }
        String chosenEmail = request.email().trim().toLowerCase(Locale.ROOT);
        UserSocialOnboardingChallenge proof = onboarding.lockVerified(request.registrationToken());
        if (Boolean.FALSE.equals(verifiedIdentities.loginAllowed(
            proof.getProvider(), proof.getProviderSubject()))) {
            throw new BadCredentialsException("PROVIDER_LOGIN_DISABLED");
        }
        ProviderProfile profile = new ProviderProfile(proof.getProvider(),
            proof.getProviderSubject(), proof.getSuggestedEmail(),
            request.displayName(), null);
        Optional<User> owner = findUserByProviderId(profile);
        // The optional provider e-mail NEVER claims another website user.
        // A pre-existing provider-owned legacy account may replace its old,
        // provider-imported e-mail only through locally verified onboarding.
        Optional<User> emailOwner = userRepository.findByEmail(chosenEmail);
        if (emailOwner.isPresent() && (owner.isEmpty()
            || !emailOwner.get().getId().equals(owner.get().getId()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ACCOUNT_RECONCILIATION_REQUIRED");
        }
        User user;
        if (owner.isPresent()) {
            // An existing Community-only Discord identity can be activated
            // in-place. Never overwrite an existing web login with OAuth.
            user = userRepository.findByIdForUpdate(owner.get().getId())
                .orElseThrow(() -> new BadCredentialsException("ACCOUNT_CHANGED"));
            if (user.getMergedIntoUser() != null || !needsOnboarding(user)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "ACCOUNT_RECONCILIATION_REQUIRED");
            }
            // Reusing a legacy Discord/Google identity for first web access
            // revokes any previous provider-issued sessions atomically.
            if (user.getAuthVersion() == null || user.getAuthVersion() == Long.MAX_VALUE) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "ACCOUNT_CHANGED");
            }
            user.setAuthVersion(user.getAuthVersion() + 1);
            userRefreshTokenRepository.deleteByUser(user);
        } else {
            user = new User();
        }
        user.setEmail(chosenEmail);
        user.setDisplayName(normalizeDisplayName(request.displayName()));
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user = userRepository.saveAndFlush(user);
        linkProviderAccount(user, profile);
        verifiedIdentities.bindProvenSubject(user, proof.getProvider(),
            proof.getProviderSubject(), proof.getSuggestedEmail());
        localAuth.requireLocalEmailConfirmation(user);
        onboarding.consume(proof);
        // Full login only after the chosen *local* e-mail is confirmed.
        return new RegisterResponse(user.getId().longValue(), chosenEmail,
            "Cadastro recebido. Confirme o e-mail escolhido antes de entrar.");
    }

    private boolean needsOnboarding(User user) {
        return user.getEmail() == null || user.getEmail().isBlank()
            || user.getPasswordHash() == null || user.getPasswordHash().isBlank();
    }

    private LoginResponse socialLogin(ProviderProfile profile) {
        User user = findExistingSocialUser(profile)
            .orElseThrow(() -> new BadCredentialsException("SOCIAL_ONBOARDING_REQUIRED"));
        if (needsOnboarding(user)) throw new BadCredentialsException("SOCIAL_ONBOARDING_REQUIRED");
        updateExistingSocialUser(user, profile);
        return issueLoginResponse(user);
    }

    private User updateExistingSocialUser(User user, ProviderProfile profile) {
        if (user.getMergedIntoUser() != null) {
            throw new BadCredentialsException("ACCOUNT_CONSOLIDATED");
        }
        boolean changed = false;
        if ((user.getDisplayName() == null || user.getDisplayName().isBlank())
                && profile.displayName() != null && !profile.displayName().isBlank()) {
            user.setDisplayName(normalizeDisplayName(profile.displayName()));
            changed = true;
        }

        if (changed) {
            user = userRepository.save(user);
        }

        // OAuth proof NEVER marks the independently chosen local e-mail verified.
        linkProviderAccount(user, profile);
        return user;
    }

    private Optional<User> findExistingSocialUser(ProviderProfile profile) {
        // Provider subject, not provider e-mail, is the only OAuth identity.
        if (Boolean.FALSE.equals(verifiedIdentities.loginAllowed(profile.provider(), profile.providerUserId()))) {
            throw new BadCredentialsException("PROVIDER_LOGIN_DISABLED");
        }
        return findUserByProviderId(profile);
    }

    private Optional<User> findUserByProviderId(ProviderProfile profile) {
        if ("google".equals(profile.provider())) {
            return verifiedIdentities.ownerOf("google", profile.providerUserId());
        }
        if (!"discord".equals(profile.provider()) || profile.providerUserId() == null
            || profile.providerUserId().isBlank()) {
            return Optional.empty();
        }

        Long discordUserId;
        try {
            discordUserId = Long.valueOf(profile.providerUserId());
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }

        return userDiscordRepository.findByDiscordUserId(discordUserId).map(UserDiscord::getUser);
    }

    private void validateTermsAgreement(Boolean termsAgreement) {
        if (!Boolean.TRUE.equals(termsAgreement)) {
            throw new IllegalArgumentException("Aceite dos termos e obrigatorio");
        }
    }

    private PendingSocialRegistration createPendingRegistration(ProviderProfile profile) {
        var challenge = onboarding.create(profile.provider(), profile.providerUserId(),
            profile.email(), profile.displayName());
        return new PendingSocialRegistration(challenge.token(), profile.provider(),
            profile.providerUserId(), profile.email(), profile.email() != null,
            profile.displayName(), profile.avatarUrl(), Instant.now().plus(15, ChronoUnit.MINUTES));
    }

    private LoginResponse issueLoginResponse(User user) {
        if (user.getMergedIntoUser() != null) {
            throw new BadCredentialsException("ACCOUNT_CONSOLIDATED");
        }
        if (userTokenRepository.existsByUserAndTypeAndUsedAtIsNullAndRevokedAtIsNull(
                user,
                UserTokenType.EMAIL_VERIFICATION
        )) {
            throw new BadCredentialsException("Confirme seu e-mail antes de acessar a conta");
        }

        userRefreshTokenRepository.deleteByUser(user);

        List<String> roles = apiUserRoleRepository.findRoleNamesByUserId(user.getId());
        List<String> permissions = apiRolePermissionRepository.findPermissionNamesByUserId(user.getId());
        String accessToken = tokenService.generateAccessToken(user.getEmail(), roles, permissions, user.getAuthVersion());
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
        String email = suggestedEmail(attributeAsString(attributes, "email"),
            attributeAsBoolean(attributes, "email_verified"));
        String displayName = firstNonBlank(
            attributeAsString(attributes, "name"),
            attributeAsString(attributes, "given_name"),
            "Usuário"
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
        String email = suggestedEmail(attributeAsString(attributes, "email"),
            attributeAsBoolean(attributes, "verified"));
        String displayName = firstNonBlank(
            attributeAsString(attributes, "global_name"),
            attributeAsString(attributes, "username"),
            "Usuário"
        );

        return new ProviderProfile(
            "discord",
            attributeAsString(attributes, "id"),
            email,
            displayName,
            discordAvatarUrl(attributeAsString(attributes, "id"), attributeAsString(attributes, "avatar"))
        );
    }

    private String suggestedEmail(String email, boolean verified) {
        if (!verified || email == null || email.isBlank() || email.length() > 255
            || !email.contains("@")) return null;
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
                if (accountLinks.isVerifiedLink(user, link.getUser())) {
                    // Keep the original Discord row with its legacy account.
                    // The two identities are authenticated but never merged.
                    return;
                }
                throw new BadCredentialsException("ACCOUNT_LINK_REQUIRED");
            }
            updateDiscordLink(link, profile);
            return;
        }

        Optional<UserDiscord> existingForUser = userDiscordRepository.findByUser(user);
        if (existingForUser.isPresent()) {
            throw new BadCredentialsException(
                "ACCOUNT_LINK_REQUIRED"
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
        // Refresh an already-proven provider subject, or a newly connected
        // Discord that is now owned by this user. Never bootstrap a Google
        // subject from a matching e-mail alone.
        findUserByProviderId(profile)
            .filter(known -> known.getId().equals(user.getId()))
            .ifPresent(known -> verifiedIdentities.bindProvenSubject(
                user, profile.provider(), profile.providerUserId(), profile.email()));
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

        if (response == null || response.subject() == null || response.subject().isBlank()) {
            throw new BadCredentialsException("Token Google invalido");
        }

        if (response.subject() == null || response.subject().isBlank()
            || response.subject().length() > 128) {
            throw new BadCredentialsException("Identidade Google incompleta");
        }

        if (googleClientId == null || googleClientId.isBlank()
            || !googleClientId.equals(response.audience())) {
            throw new BadCredentialsException("Token Google emitido para outro client_id");
        }

        String normalizedEmail = suggestedEmail(response.email(),
            Boolean.parseBoolean(response.emailVerified()));
        String displayName = firstNonBlank(
            response.name(),
            response.givenName(),
            requestedDisplayName,
            "Usuário"
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

        if (response == null || response.id() == null || response.id().isBlank()) {
            throw new BadCredentialsException("Token Discord invalido");
        }


        String normalizedEmail = suggestedEmail(response.email(),
            response.verified());
        String displayName = firstNonBlank(
            response.globalName(),
            response.username(),
            requestedDisplayName,
            "Usuário"
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
