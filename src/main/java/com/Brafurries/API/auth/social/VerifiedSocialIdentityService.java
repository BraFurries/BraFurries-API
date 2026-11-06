package com.Brafurries.API.auth.social;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserSocialIdentity;
import com.Brafurries.API.repository.user.UserSocialIdentityRepository;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Persist immutable provider subjects only after provider authentication AND
 * the caller has proven control of the target site account.
 *
 * Never use a matching e-mail to claim an existing provider subject or to
 * consolidate distinct users. No permissions or Community data are moved.
 */
@Service
public class VerifiedSocialIdentityService {
    private final UserSocialIdentityRepository identities;

    public VerifiedSocialIdentityService(UserSocialIdentityRepository identities) {
        this.identities = identities;
    }

    @Transactional(readOnly = true)
    public Optional<User> ownerOf(String provider, String providerSubject) {
        String normalizedProvider = normalizeProvider(provider);
        String subject = normalizeSubject(providerSubject);
        return identities.findByProviderAndProviderSubject(normalizedProvider, subject)
            .map(UserSocialIdentity::getUser);
    }

    /**
     * Caller is responsible for OAuth verification and proving site-account
     * ownership. Both identities must be checked before invoking this method.
     * Re-verification of the same subject is idempotent.
     */
    @Transactional
    public void bindProvenSubject(User user, String provider, String providerSubject, String email) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("A persisted site user must be authenticated");
        }
        String p = normalizeProvider(provider);
        String sub = normalizeSubject(providerSubject);
        String verifiedEmail = email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
        if (verifiedEmail != null && verifiedEmail.length() > 255) {
            throw new IllegalArgumentException("Verified e-mail is too long");
        }

        Optional<UserSocialIdentity> existing = identities.findByProviderAndProviderSubject(p, sub);
        if (existing.isPresent()) {
            UserSocialIdentity identity = existing.get();
            if (!user.getId().equals(identity.getUser().getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "ACCOUNT_LINK_REQUIRED");
            }
            identity.setLastVerifiedAt(LocalDateTime.now(ZoneOffset.UTC));
            if (verifiedEmail != null) identity.setVerifiedEmail(verifiedEmail);
            identities.save(identity);
            return;
        }

        UserSocialIdentity identity = new UserSocialIdentity();
        identity.setUser(user);
        identity.setProvider(p);
        identity.setProviderSubject(sub);
        identity.setVerifiedEmail(verifiedEmail);
        try {
            // Unique (provider, provider_subject) is enforced by Database #48.
            // Races fail closed; a conflicting transaction is rolled back.
            identities.saveAndFlush(identity);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ACCOUNT_LINK_REQUIRED", ex);
        }
    }

    private String normalizeProvider(String provider) {
        String p = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (!"google".equals(p) && !"discord".equals(p)) {
            throw new IllegalArgumentException("Unsupported identity provider");
        }
        return p;
    }

    private String normalizeSubject(String providerSubject) {
        if (providerSubject == null || providerSubject.isBlank()
            || providerSubject.length() > 128 || !providerSubject.equals(providerSubject.trim())) {
            throw new IllegalArgumentException("Invalid immutable provider subject");
        }
        return providerSubject;
    }
}
