package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.common.TokenService;
import com.Brafurries.API.entity.user.UserSocialOnboardingChallenge;
import com.Brafurries.API.repository.user.UserSocialOnboardingChallengeRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Cross-replica, one-use challenge backed by Database Flyway.
 * No raw OAuth access token or provider e-mail is sent to the browser URL.
 */
@Service
public class SocialOnboardingChallengeService {
    private static final int TTL_MINUTES = 15;
    private final UserSocialOnboardingChallengeRepository challenges;
    private final TokenService tokenService;

    public SocialOnboardingChallengeService(
        UserSocialOnboardingChallengeRepository challenges, TokenService tokenService
    ) {
        this.challenges = challenges;
        this.tokenService = tokenService;
    }

    public record Created(String token, String provider) {}
    public record Preview(String provider, String suggestedEmail, String displayName) {}

    @Transactional
    public Created create(String provider, String subject, String suggestedEmail, String displayName) {
        if (!("discord".equals(provider) || "google".equals(provider))
            || subject == null || subject.isBlank() || subject.length() > 128) {
            throw new IllegalArgumentException("Invalid OAuth identity");
        }
        String token = tokenService.generateToken(48);
        UserSocialOnboardingChallenge row = new UserSocialOnboardingChallenge();
        row.setTokenHash(hash(token));
        row.setProvider(provider);
        row.setProviderSubject(subject);
        row.setSuggestedEmail(suggestedEmail);
        row.setDisplayName(displayName);
        row.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(TTL_MINUTES));
        challenges.saveAndFlush(row);
        return new Created(token, provider);
    }

    @Transactional(readOnly = true)
    public Preview preview(String token) {
        var challenge = valid(challenges.findByTokenHash(hash(token))
            .orElseThrow(this::expired));
        return new Preview(challenge.getProvider(), challenge.getSuggestedEmail(),
            challenge.getDisplayName());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UserSocialOnboardingChallenge lockVerified(String token) {
        return valid(challenges.findByTokenHashForUpdate(hash(token))
            .orElseThrow(this::expired));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void consume(UserSocialOnboardingChallenge challenge) {
        valid(challenge);
        challenge.setConsumedAt(LocalDateTime.now(ZoneOffset.UTC));
        challenges.saveAndFlush(challenge);
    }

    @Scheduled(fixedDelayString = "${app.auth.social-onboarding-cleanup-ms:3600000}")
    @Transactional
    public void cleanup() {
        challenges.deleteExpired(LocalDateTime.now(ZoneOffset.UTC).minusDays(1));
    }

    private UserSocialOnboardingChallenge valid(UserSocialOnboardingChallenge challenge) {
        if (challenge.getConsumedAt() != null
            || challenge.getExpiresAt() == null
            || !challenge.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw expired();
        }
        return challenge;
    }

    private String hash(String raw) {
        if (raw == null || raw.isBlank() || raw.length() > 512) throw expired();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private ResponseStatusException expired() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "SOCIAL_REGISTRATION_EXPIRED");
    }
}
