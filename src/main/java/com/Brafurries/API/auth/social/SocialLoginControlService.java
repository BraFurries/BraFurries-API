package com.Brafurries.API.auth.social;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserSocialIdentity;
import com.Brafurries.API.entity.user.UserSocialLoginEvent;
import com.Brafurries.API.entity.user.UserTokenType;
import com.Brafurries.API.repository.user.UserRefreshTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserSocialIdentityRepository;
import com.Brafurries.API.repository.user.UserSocialLoginEventRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * A provider subject remains reserved for its verified account even when
 * login through that provider is disabled. No Community association is changed.
 */
@Service
public class SocialLoginControlService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SocialLoginControlService.class);
    private final UserRepository users;
    private final UserSocialIdentityRepository identities;
    private final UserSocialLoginEventRepository events;
    private final UserRefreshTokenRepository refreshTokens;
    private final UserTokenRepository userTokens;
    private final PasswordEncoder passwords;
    private final DiscordSelfServiceLinkService discord;
    private final SocialLoginOAuthProofService oauthProofs;

    public SocialLoginControlService(
        UserRepository users,
        UserSocialIdentityRepository identities,
        UserSocialLoginEventRepository events,
        UserRefreshTokenRepository refreshTokens,
        UserTokenRepository userTokens,
        PasswordEncoder passwords,
        DiscordSelfServiceLinkService discord,
        SocialLoginOAuthProofService oauthProofs
    ) {
        this.users = users;
        this.identities = identities;
        this.events = events;
        this.refreshTokens = refreshTokens;
        this.userTokens = userTokens;
        this.passwords = passwords;
        this.discord = discord;
        this.oauthProofs = oauthProofs;
    }

    public record ProviderLogin(Long identityId, String provider, boolean loginEnabled) {}
    public record ChangeResult(String status) {}

    @Transactional(readOnly = true)
    public List<ProviderLogin> list(Authentication authentication) {
        User actor = requireActor(authentication);
        return identities.findByUserId(actor.getId()).stream()
            .map(identity -> new ProviderLogin(identity.getId(), identity.getProvider(),
                Boolean.TRUE.equals(identity.getLoginEnabled())))
            .toList();
    }

    @Transactional
    public ChangeResult disable(Authentication authentication, Long identityId, String password) {
        User actor = requireLockedActor(authentication, password);
        UserSocialIdentity identity = identities.findById(identityId)
            .filter(item -> item.getUser().getId().equals(actor.getId()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Método não encontrado"));
        if (!Boolean.TRUE.equals(identity.getLoginEnabled())) {
            return new ChangeResult("ALREADY_DISABLED");
        }
        recordChange(actor, identity, false);
        auditAfterCommit("DISABLED", actor, identity);
        return new ChangeResult("RELOGIN_REQUIRED");
    }

    /** A fresh server-side Google OAuth callback proves the stable subject. */
    @Transactional
    public ChangeResult enableGoogleOAuth(Authentication authentication, String ticket, String password) {
        User actor = requireLockedActor(authentication, password);
        String subject = oauthProofs.consumeVerifiedGoogle(actor, ticket);
        UserSocialIdentity identity = ownedIdentity(actor, "google", subject);
        return enable(actor, identity);
    }

    @Transactional
    public ChangeResult enableDiscord(Authentication authentication, String ticket, String password) {
        User actor = requireLockedActor(authentication, password);
        // The ticket is a one-use proof from a separate Discord OAuth flow,
        // already bound to this authenticated BraFurries account.
        String subject = discord.consumeVerifiedDiscordForLogin(actor, ticket);
        UserSocialIdentity identity = ownedIdentity(actor, "discord", subject);
        return enable(actor, identity);
    }

    private ChangeResult enable(User actor, UserSocialIdentity identity) {
        if (Boolean.TRUE.equals(identity.getLoginEnabled())) {
            return new ChangeResult("ALREADY_ENABLED");
        }
        recordChange(actor, identity, true);
        auditAfterCommit("ENABLED", actor, identity);
        return new ChangeResult("RELOGIN_REQUIRED");
    }

    private UserSocialIdentity ownedIdentity(User actor, String provider, String subject) {
        return identities.findByProviderAndProviderSubject(provider, subject)
            .filter(identity -> identity.getUser().getId().equals(actor.getId()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "PROVIDER_IDENTITY_NOT_OWNED"));
    }

    private User requireActor(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
            || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida");
        }
        User user = users.findByEmail(authentication.getName().trim().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida"));
        if (user.getMergedIntoUser() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ACCOUNT_CONSOLIDATED");
        }
        return user;
    }

    private User requireLockedActor(Authentication authentication, String password) {
        if (authentication == null || !authentication.isAuthenticated()
            || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida");
        }
        // Lock before reading auth_version or modifying any provider identity.
        User actor = users.findByEmailForUpdate(
                authentication.getName().trim().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida"));
        if (actor.getMergedIntoUser() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ACCOUNT_CONSOLIDATED");
        }
        if (actor.getPasswordHash() == null
            || actor.getPasswordHash().isBlank() || password == null
            || !passwords.matches(password, actor.getPasswordHash())) {
            throw new BadCredentialsException("Confirme sua senha BraFurries para alterar o método de login");
        }
        if (actor.getEmail() == null || actor.getEmail().isBlank()
            || actor.getEmailVerifiedAt() == null
            || userTokens.existsByUserAndTypeAndUsedAtIsNullAndRevokedAtIsNull(
                actor, UserTokenType.EMAIL_VERIFICATION)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "LOCAL_EMAIL_CONFIRMATION_REQUIRED");
        }
        return actor;
    }

    private void recordChange(User actor, UserSocialIdentity identity, boolean enabled) {
        Long priorVersion = actor.getAuthVersion();
        boolean previousState = Boolean.TRUE.equals(identity.getLoginEnabled());
        identity.setLoginEnabled(enabled);
        identities.save(identity);
        revokeSessions(actor);

        UserSocialLoginEvent event = new UserSocialLoginEvent();
        event.setUser(actor);
        event.setIdentity(identity);
        event.setProvider(identity.getProvider());
        event.setPreviousLoginEnabled(previousState);
        event.setNextLoginEnabled(enabled);
        event.setPreviousAuthVersion(priorVersion);
        event.setNextAuthVersion(actor.getAuthVersion());
        // The event and account mutation must commit or roll back together.
        events.save(event);
    }

    private void revokeSessions(User actor) {
        Long version = actor.getAuthVersion();
        if (version == null || version == Long.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ACCOUNT_CHANGED");
        }
        actor.setAuthVersion(version + 1);
        refreshTokens.deleteByUser(actor);
        // The bearer filter checks the database auth_version on every request.
    }

    private void auditAfterCommit(String action, User actor, UserSocialIdentity identity) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        Integer userId = actor.getId();
        Long identityId = identity.getId();
        String provider = identity.getProvider();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                LOGGER.info("SOCIAL_LOGIN_{} user_id={} social_identity_id={} provider={}",
                    action, userId, identityId, provider);
            }
        });
    }
}
