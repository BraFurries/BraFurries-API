package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.common.AuthService;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserTokenType;
import com.Brafurries.API.repository.user.UserRefreshTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Migrates only the proof of a locally chosen e-mail, never an OAuth subject.
 * Historical accounts have unknown proof until a user explicitly confirms.
 */
@Service
public class LocalEmailVerificationService {
    private final UserRepository users;
    private final UserRefreshTokenRepository refreshTokens;
    private final UserTokenRepository userTokens;
    private final PasswordEncoder passwords;
    private final AuthService localAuth;

    public LocalEmailVerificationService(
        UserRepository users, UserRefreshTokenRepository refreshTokens,
        UserTokenRepository userTokens, PasswordEncoder passwords, AuthService localAuth
    ) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.userTokens = userTokens;
        this.passwords = passwords;
        this.localAuth = localAuth;
    }

    public record Status(String email, boolean verified) {}
    public record RequestResult(String status) {}

    @Transactional(readOnly = true)
    public Status status(Authentication authentication) {
        User user = currentUser(authentication);
        return new Status(user.getEmail(), user.getEmailVerifiedAt() != null);
    }

    @Transactional
    public RequestResult request(Authentication authentication, String chosenEmail, String password) {
        User found = currentUser(authentication);
        User user = users.findByIdForUpdate(found.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão inválida"));
        if (user.getMergedIntoUser() != null || user.getPasswordHash() == null
            || user.getPasswordHash().isBlank() || password == null
            || !passwords.matches(password, user.getPasswordHash())) {
            throw new BadCredentialsException("Confirme sua senha BraFurries");
        }
        if (user.getEmailVerifiedAt() != null) {
            return new RequestResult("ALREADY_VERIFIED");
        }

        String normalized = chosenEmail == null ? "" : chosenEmail.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 255 || !normalized.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "E-mail inválido");
        }
        // Never claim another account because of provider or e-mail coincidence.
        users.findByEmail(normalized)
            .filter(other -> !other.getId().equals(user.getId()))
            .ifPresent(other -> {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "EMAIL_ALREADY_IN_USE");
            });

        if (userTokens.existsByUserAndTypeAndUsedAtIsNullAndRevokedAtIsNull(
                user, UserTokenType.EMAIL_VERIFICATION)) {
            // Existing unconfirmed challenge can be reissued through the
            // password-authenticated /auth/resend-confirmation route.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "EMAIL_VERIFICATION_ALREADY_PENDING");
        }

        Long version = user.getAuthVersion();
        if (version == null || version == Long.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ACCOUNT_CHANGED");
        }
        user.setEmail(normalized);
        user.setEmailVerifiedAt(null);
        user.setAuthVersion(version + 1);
        users.saveAndFlush(user);
        refreshTokens.deleteByUser(user);
        // A live pending token blocks stale bearer/refresh after the change.
        localAuth.requireLocalEmailConfirmation(user);
        return new RequestResult("VERIFICATION_SENT_RELOGIN_REQUIRED");
    }

    private User currentUser(Authentication authentication) {
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
}
