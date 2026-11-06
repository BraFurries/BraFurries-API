package com.Brafurries.API.auth.social;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserAccountConsolidation;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserDiscordLinkChallenge;
import com.Brafurries.API.entity.user.UserSocialIdentity;
import com.Brafurries.API.repository.user.UserAccountConsolidationRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserIdentityLinkRepository;
import com.Brafurries.API.repository.user.UserRefreshTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserSocialIdentityRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Single, intentionally narrow automatic merge:
 * authenticated website user with no operating data -> proven Discord
 * history owner. The website user may have a LOCAL password, a verified
 * Google subject, or both; no specific OAuth provider is mandatory. The ownership guard verifies every known live users FK
 * before any write, including foreign keys introduced by later migrations.
 *
 * Caller MUST lock the verified one-time OAuth challenge in the transaction.
 * This service locks both user IDs in deterministic order, checks again under
 * lock, and only then moves independently verified provider subjects and
 * compatible local login credentials.
 * Discord and Community/XP/assets/moderation data NEVER change owner here.
 */
@Service
public class SparseAccountConsolidationExecutor {
    private final UserRepository users;
    private final UserDiscordRepository discord;
    private final UserIdentityLinkRepository links;
    private final UserSocialIdentityRepository identities;
    private final UserRefreshTokenRepository refreshTokens;
    private final UserTokenRepository recoveryTokens;
    private final UserAccountConsolidationRepository audit;
    private final SparseAccountOwnershipGuard ownership;

    public SparseAccountConsolidationExecutor(UserRepository users,
        UserDiscordRepository discord, UserIdentityLinkRepository links,
        UserSocialIdentityRepository identities, UserRefreshTokenRepository refreshTokens,
        UserTokenRepository recoveryTokens, UserAccountConsolidationRepository audit,
        SparseAccountOwnershipGuard ownership) {
        this.users = users;
        this.discord = discord;
        this.links = links;
        this.identities = identities;
        this.refreshTokens = refreshTokens;
        this.recoveryTokens = recoveryTokens;
        this.audit = audit;
        this.ownership = ownership;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Integer consolidate(User requester, UserDiscordLinkChallenge challenge) {
        if (requester == null || requester.getId() == null || challenge == null
            || !"VERIFIED".equals(challenge.getStatus())
            || challenge.getRequester() == null
            || !requester.getId().equals(challenge.getRequester().getId())
            || challenge.getDiscordUserId() == null) {
            throw blocked("ACCOUNT_PROOF_REQUIRED");
        }
        UserDiscord observed = discord.findByDiscordUserId(challenge.getDiscordUserId())
            .orElseThrow(() -> blocked("DISCORD_HISTORY_NOT_FOUND"));
        Integer websiteId = requester.getId();
        Integer legacyId = observed.getUser().getId();
        if (websiteId.equals(legacyId)) throw blocked("ALREADY_CONNECTED");

        // The lock order prevents two opposite consolidation requests from
        // causing a users-row deadlock. All writes share this transaction.
        Integer first = Math.min(websiteId, legacyId);
        Integer second = Math.max(websiteId, legacyId);
        User lockedFirst = users.findByIdForUpdate(first)
            .orElseThrow(() -> blocked("ACCOUNT_CHANGED"));
        User lockedSecond = users.findByIdForUpdate(second)
            .orElseThrow(() -> blocked("ACCOUNT_CHANGED"));
        User web = first.equals(websiteId) ? lockedFirst : lockedSecond;
        User canonical = first.equals(legacyId) ? lockedFirst : lockedSecond;

        if (web.getMergedIntoUser() != null || canonical.getMergedIntoUser() != null
            || audit.existsByAbsorbedUserId(websiteId)) {
            throw blocked("ACCOUNT_ALREADY_CONSOLIDATED");
        }
        UserDiscord current = discord.findByDiscordUserId(challenge.getDiscordUserId())
            .orElseThrow(() -> blocked("ACCOUNT_CHANGED"));
        if (!legacyId.equals(current.getUser().getId()) || discord.findByUser(web).isPresent()) {
            throw blocked("ACCOUNT_CHANGED");
        }
        if (!links.findActiveLinksContainingUser(websiteId).isEmpty()
            || !links.findActiveLinksContainingUser(legacyId).isEmpty()) {
            throw blocked("IDENTITY_LINK_REVIEW_REQUIRED");
        }

        // This rejects any website-owned Community, finance, progression,
        // moderation, API grant, admin ownership, or future unknown FK.
        // The live schema is rechecked under transaction locks.
        ownership.assertOnlyTransferableReferences(websiteId);

        List<UserSocialIdentity> google = identities.findByUserIdAndProvider(websiteId, "google");
        if (!identities.findByUserIdAndProvider(websiteId, "discord").isEmpty()) {
            throw blocked("MULTIPLE_DISCORD_REVIEW_REQUIRED");
        }
        if (google.isEmpty() && (web.getPasswordHash() == null
            || web.getPasswordHash().isBlank())) {
            // Accounts created with local e-mail/password do NOT need Google
            // OAuth: the authenticated site session and independently proven
            // Discord OAuth are the two required ownership proofs.
            // But never absorb a Google-only/no-password account without
            // preserving at least one way to log in to the canonical user.
            throw blocked("VERIFY_GOOGLE_OR_RECOVER_LOCAL_LOGIN");
        }

        if (web.getEmail() == null || web.getEmail().isBlank()
            || (canonical.getEmail() != null
                && !canonical.getEmail().equalsIgnoreCase(web.getEmail()))) {
            throw blocked("EMAIL_RECONCILIATION_REQUIRED");
        }
        if (canonical.getPasswordHash() != null && !canonical.getPasswordHash().isBlank()
            && web.getPasswordHash() != null && !web.getPasswordHash().isBlank()
            && !canonical.getPasswordHash().equals(web.getPasswordHash())) {
            throw blocked("PASSWORD_RECONCILIATION_REQUIRED");
        }
        if (canonical.getProfileImageKey() != null && web.getProfileImageKey() != null
            && !canonical.getProfileImageKey().equals(web.getProfileImageKey())) {
            throw blocked("PROFILE_RECONCILIATION_REQUIRED");
        }
        if (web.getAuthVersion() == null || canonical.getAuthVersion() == null
            || web.getAuthVersion() == Long.MAX_VALUE
            || canonical.getAuthVersion() == Long.MAX_VALUE) {
            throw blocked("AUTH_VERSION_INVALID");
        }

        // Revoke credentials in BOTH accounts before transferring site login.
        refreshTokens.deleteByUser(web);
        refreshTokens.deleteByUser(canonical);
        recoveryTokens.deleteByUser(web);
        recoveryTokens.deleteByUser(canonical);

        String email = web.getEmail();
        // Clear the globally unique email on absorbed account first, then flush:
        // no transaction may temporarily expose two owners for one address.
        web.setEmail(null);
        web.setAuthVersion(web.getAuthVersion() + 1);
        users.saveAndFlush(web);

        if (canonical.getEmail() == null) canonical.setEmail(email);
        if (canonical.getPasswordHash() == null) canonical.setPasswordHash(web.getPasswordHash());
        if (canonical.getDisplayName() == null) canonical.setDisplayName(web.getDisplayName());
        if (canonical.getUsername() == null) canonical.setUsername(web.getUsername());
        if (canonical.getProfileImageKey() == null) {
            canonical.setProfileImageKey(web.getProfileImageKey());
            canonical.setProfileImageUrl(web.getProfileImageUrl());
            canonical.setProfileImageContentType(web.getProfileImageContentType());
            canonical.setProfileImageUpdatedAt(web.getProfileImageUpdatedAt());
        }
        canonical.setAuthVersion(canonical.getAuthVersion() + 1);
        users.saveAndFlush(canonical);

        for (UserSocialIdentity identity : google) {
            // Exactly the same immutable provider subject, new canonical
            // owner. No email-only provider claims and no new OAuth grants.
            identity.setUser(canonical);
            identities.save(identity);
        }
        identities.flush();

        // Preserve the historical website row and every audit actor FK.
        web.setMergedIntoUser(canonical);
        users.saveAndFlush(web);

        UserAccountConsolidation event = new UserAccountConsolidation();
        event.setCanonicalUser(canonical);
        event.setAbsorbedUser(web);
        event.setInitiatedByUser(web);
        event.setProvedDiscordUserId(challenge.getDiscordUserId());
        audit.saveAndFlush(event);
        return canonical.getId();
    }

    private ResponseStatusException blocked(String code) {
        return new ResponseStatusException(HttpStatus.CONFLICT, code);
    }
}
