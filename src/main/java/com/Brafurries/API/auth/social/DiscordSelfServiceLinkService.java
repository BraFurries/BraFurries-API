package com.Brafurries.API.auth.social;

import com.Brafurries.API.admin.IdentityBanPropagationService;
import com.Brafurries.API.auth.common.TokenService;
import com.Brafurries.API.entity.user.*;
import com.Brafurries.API.repository.user.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

/**
 * Proof of possession of A (an authenticated BraFurries account) and B
 * (Discord OAuth, verified by Spring Security). Linking never rewrites
 * ownership of money, inventory, moderation, Community grants or identities.
 */
@Service
public class DiscordSelfServiceLinkService {
    public static final String SESSION_TICKET = "BRF_DISCORD_SELF_LINK_TICKET";
    private static final int TICKET_TTL_MINUTES = 10;

    private final UserRepository users;
    private final UserDiscordRepository discord;
    private final UserCommunityStatusRepository memberships;
    private final UserAccountReconciliationPreflightService preflight;
    private final UserAccountConsolidationPlanner planner;
    private final VerifiedSocialIdentityService verifiedIdentities;
    private final SparseAccountConsolidationExecutor consolidation;
    private final UserIdentityLinkRepository links;
    private final UserDiscordLinkChallengeRepository challenges;
    private final IdentityBanPropagationService banPropagation;
    private final TokenService tokens;

    public DiscordSelfServiceLinkService(
        UserRepository users,
        UserDiscordRepository discord,
        UserCommunityStatusRepository memberships,
        UserAccountReconciliationPreflightService preflight,
        UserAccountConsolidationPlanner planner,
        VerifiedSocialIdentityService verifiedIdentities,
        SparseAccountConsolidationExecutor consolidation,
        UserIdentityLinkRepository links,
        UserDiscordLinkChallengeRepository challenges,
        IdentityBanPropagationService banPropagation,
        TokenService tokens
    ) {
        this.users = users;
        this.discord = discord;
        this.memberships = memberships;
        this.preflight = preflight;
        this.planner = planner;
        this.verifiedIdentities = verifiedIdentities;
        this.consolidation = consolidation;
        this.links = links;
        this.challenges = challenges;
        this.banPropagation = banPropagation;
        this.tokens = tokens;
    }

    public record Start(String ticket) {}
    /**
     * PRECHECK_REQUIRED is intentionally not MERGE_READY: grants, balances,
     * moderation, and identity-owned rows still require a complete conflict scan.
     * No preview response authorizes a transfer of data or permissions.
     */
    public record Preview(
        String discordUsername,
        boolean hasExistingHistory,
        String status,
        List<String> nextActions,
        String notice,
        UserAccountReconciliationPreflightService.Findings assessment,
        UserAccountConsolidationPlanner.Plan consolidationPlan
    ) {}
    public record Confirmation(String status, String notice) {}
    public record ConsolidationResult(String status, Integer canonicalUserId, String notice) {}

    @Transactional
    public Start start(Authentication authentication) {
        User actor = requireAuthenticated(authentication);
        String ticket = tokens.generateToken(32);
        UserDiscordLinkChallenge challenge = new UserDiscordLinkChallenge();
        challenge.setTicketHash(hash(ticket));
        challenge.setRequester(actor);
        challenge.setStatus("STARTED");
        challenge.setExpiresAt(now().plusMinutes(TICKET_TTL_MINUTES));
        challenges.save(challenge);
        return new Start(ticket);
    }

    @Transactional(readOnly = true)
    public void authorizeOAuthStart(String ticket) {
        UserDiscordLinkChallenge challenge = requireChallenge(ticket);
        if (!"STARTED".equals(challenge.getStatus())) throw invalidTicket();
    }

    @Transactional
    public void verifiedDiscordOAuth(String ticket, OAuth2AuthenticationToken authentication) {
        if (!"discord".equals(authentication.getAuthorizedClientRegistrationId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "É necessário autenticar pelo Discord");
        }
        String discordId = String.valueOf(authentication.getPrincipal().getAttributes().get("id"));
        Long id = parseDiscordId(discordId);
        Object usernameValue = authentication.getPrincipal().getAttributes().get("username");
        String username = usernameValue == null ? "discord" : usernameValue.toString().trim();
        if (username.isBlank()) username = "discord";

        UserDiscordLinkChallenge challenge = requireLockedChallenge(ticket);
        if (!"STARTED".equals(challenge.getStatus())) throw invalidTicket();
        challenge.setDiscordUserId(id);
        challenge.setDiscordUsername(username.substring(0, Math.min(32, username.length())));
        challenge.setVerifiedAt(now());
        challenge.setStatus("VERIFIED");
        challenges.save(challenge);
    }

    @Transactional(readOnly = true)
    public Preview preview(Authentication authentication, String ticket) {
        User actor = requireAuthenticated(authentication);
        UserDiscordLinkChallenge challenge = requireVerifiedChallenge(actor, requireChallenge(ticket));
        UserDiscord existing = discord.findByDiscordUserId(challenge.getDiscordUserId()).orElse(null);
        UserDiscord own = discord.findByUser(actor).orElse(null);
        if (own != null && !own.getDiscordUserId().equals(challenge.getDiscordUserId())) {
            return new Preview(challenge.getDiscordUsername(), existing != null,
                "RESOLUTION_REQUIRED", List.of("REVIEW_EXISTING_DISCORD", "RETRY_WITH_CORRECT_ACCOUNT"),
                "Esta conta já possui outro Discord. Nenhuma identidade será substituída automaticamente.", null, null);
        }
        if (existing == null || existing.getUser().getId().equals(actor.getId())) {
            return new Preview(challenge.getDiscordUsername(), false,
                "CONNECT_READY", List.of("CONFIRM_LINK"),
                "A identidade Discord foi comprovada. A confirmação não altera dados de outras contas.", null, null);
        }

        Integer legacyId = existing.getUser().getId();
        var related = links.findActiveLinksContainingUser(legacyId);
        boolean claimedElsewhere = related.stream().anyMatch(link ->
            link.getStatus() == UserIdentityLinkStatus.CONFIRMED
                && link.getLinkSource() == UserIdentityLinkSource.SOCIAL
                && !link.getUserA().getId().equals(actor.getId()));
        boolean otherReview = related.stream().anyMatch(link ->
            link.getStatus() != UserIdentityLinkStatus.CONFIRMED
                || link.getLinkSource() != UserIdentityLinkSource.SOCIAL
                || !link.getUserA().getId().equals(actor.getId()));
        if (claimedElsewhere || otherReview) {
            return new Preview(challenge.getDiscordUsername(), true,
                "REVIEW_REQUIRED", List.of("VERIFY_OTHER_ACCOUNT", "REQUEST_IDENTITY_REVIEW"),
                "Há outro vínculo ou revisão de identidade. É necessária uma comprovação adicional; nenhum dado será transferido.", null, null);
        }
        var assessment = preflight.inspect(actor, existing.getUser());
        var plan = planner.propose(assessment);
        String notice = switch (assessment.status()) {
            case "REVIEW_REQUIRED" ->
                "Há restrições, privilégios ou referências ainda não verificadas. A identidade está comprovada, mas a fusão exige revisão segura.";
            case "RESOLUTION_REQUIRED" ->
                "Encontramos dados em ambas as contas que precisam de reconciliação. Vamos preservar todos eles e apresentar as alternativas disponíveis.";
            default ->
                "As identidades estão comprovadas e não foram encontrados conflitos nesta triagem. Falta validar as políticas de transferência antes da fusão.";
        };
        return new Preview(challenge.getDiscordUsername(), true,
            assessment.status(), assessment.nextActions(), notice, assessment, plan);
    }

    @Transactional
    public Confirmation confirm(Authentication authentication, String ticket) {
        User actor = requireAuthenticated(authentication);
        UserDiscordLinkChallenge challenge = requireLockedChallenge(ticket);
        requireVerifiedChallenge(actor, challenge);

        UserDiscord existing = discord.findByDiscordUserId(challenge.getDiscordUserId()).orElse(null);
        UserDiscord own = discord.findByUser(actor).orElse(null);
        String outcome = "ALREADY_CONNECTED";

        if (own != null && !own.getDiscordUserId().equals(challenge.getDiscordUserId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Sua conta já tem outro Discord conectado. Desconecte-o antes de tentar novamente.");
        }
        // Until a complete per-Community consolidation plan exists, a proven
        // Discord account owned by another User cannot be silently linked.
        if (existing != null && !existing.getUser().getId().equals(actor.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ACCOUNT_RECONCILIATION_REQUIRED");
        }
        if (existing == null) {
            UserDiscord link = new UserDiscord();
            link.setUser(actor);
            link.setDiscordUserId(challenge.getDiscordUserId());
            link.setUsername(challenge.getDiscordUsername());
            link.setDisplayName(challenge.getDiscordUsername());
            discord.saveAndFlush(link);
            outcome = "CONNECTED";
        } else if (existing.getUser().getId().equals(actor.getId())) {
            outcome = "ALREADY_CONNECTED";
        }


        // The site account and Discord provider were independently verified.
        // Record the immutable OAuth subject only for the same canonical owner.
        // Cross-user recovery is blocked earlier until a safe merge exists.
        verifiedIdentities.bindProvenSubject(actor, "discord",
            challenge.getDiscordUserId().toString(), null);
        challenge.setStatus("CONSUMED");
        challenge.setConsumedAt(now());
        challenges.save(challenge);
        return new Confirmation(outcome, "Discord confirmado. Os registros anteriores foram preservados.");
    }

    /**
     * An explicit second confirmation is mandatory because the current JWT
     * must be revoked. Caller re-authenticates as the surviving Discord
     * history owner after the transaction commits.
     */
    @Transactional
    public ConsolidationResult consolidate(Authentication authentication, String ticket,
                                           boolean acknowledgeDiscordHistoryOwner) {
        if (!acknowledgeDiscordHistoryOwner) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CONFIRM_CANONICAL_OWNER_REQUIRED");
        }
        User requester = requireAuthenticated(authentication);
        UserDiscordLinkChallenge challenge = requireLockedChallenge(ticket);
        requireVerifiedChallenge(requester, challenge);

        Integer canonicalId = consolidation.consolidate(requester, challenge);
        challenge.setStatus("CONSUMED");
        challenge.setConsumedAt(now());
        challenges.saveAndFlush(challenge);
        return new ConsolidationResult("CONSOLIDATED_RELOGIN_REQUIRED", canonicalId,
            "As identidades foram consolidadas. Entre novamente usando seu Discord ou os dados da conta.");
    }

    /**
     * Select only an OAuth-proven *direct* legacy Discord profile for
     * read-only personal history. Never accept generic ADMIN/SUSPECTED links.
     */
    @Transactional(readOnly = true)
    public User legacyProfileFor(User authenticated) {
        if (discord.findByUser(authenticated).isPresent()
            || memberships.countByUserId(authenticated.getId()) > 0) {
            // A website account with its own Community data must never
            // silently swap that history out. Multi-profile reconciliation
            // needs a separate explicit choice instead of hidden replacement.
            return authenticated;
        }
        List<User> proven = links.findActiveLinksContainingUser(authenticated.getId()).stream()
            .filter(link -> link.getStatus() == UserIdentityLinkStatus.CONFIRMED
                && link.getLinkSource() == UserIdentityLinkSource.SOCIAL
                && link.getUserA().getId().equals(authenticated.getId()))
            .map(UserIdentityLink::getUserB)
            .filter(user -> discord.findByUser(user).isPresent())
            .distinct().toList();
        return proven.size() == 1 ? proven.getFirst() : authenticated;
    }

    /** A verified Discord sign-in can resolve its previously recovered website account. */
    @Transactional(readOnly = true)
    public java.util.Optional<User> recoveredLoginFor(User legacyDiscordOwner) {
        List<User> possible = links.findActiveLinksContainingUser(legacyDiscordOwner.getId()).stream()
            .filter(link -> link.getStatus() == UserIdentityLinkStatus.CONFIRMED
                && link.getLinkSource() == UserIdentityLinkSource.SOCIAL
                && link.getUserB().getId().equals(legacyDiscordOwner.getId()))
            .map(UserIdentityLink::getUserA)
            .filter(user -> user.getEmail() != null && !user.getEmail().isBlank())
            .distinct().toList();
        return possible.size() == 1 ? java.util.Optional.of(possible.getFirst()) : java.util.Optional.empty();
    }

    @Transactional(readOnly = true)
    public boolean isVerifiedLink(User websiteAccount, User discordOwner) {
        return links.findActiveByPair(websiteAccount.getId(), discordOwner.getId())
            .filter(link -> link.getStatus() == UserIdentityLinkStatus.CONFIRMED
                && link.getLinkSource() == UserIdentityLinkSource.SOCIAL
                && link.getUserA().getId().equals(websiteAccount.getId())
                && link.getUserB().getId().equals(discordOwner.getId()))
            .isPresent();
    }

    @Scheduled(fixedDelayString = "${app.auth.discord-link-cleanup-ms:3600000}")
    @Transactional
    public void cleanupExpired() {
        challenges.deleteByExpiresAtBefore(now().minusHours(24));
    }

    private User requireAuthenticated(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Faça login antes de conectar o Discord");
        }
        return users.findByEmail(authentication.getName().trim().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão não reconhecida"));
    }

    private UserDiscordLinkChallenge requireChallenge(String ticket) {
        return ensureNotExpired(challenges.findByTicketHash(hash(ticket))
            .orElseThrow(this::invalidTicketChecked));
    }

    private UserDiscordLinkChallenge requireLockedChallenge(String ticket) {
        return ensureNotExpired(challenges.findByTicketHashForUpdate(hash(ticket))
            .orElseThrow(this::invalidTicketChecked));
    }

    private UserDiscordLinkChallenge ensureNotExpired(UserDiscordLinkChallenge challenge) {
        if (!challenge.getExpiresAt().isAfter(now())) throw invalidTicket();
        return challenge;
    }

    private UserDiscordLinkChallenge requireVerifiedChallenge(User actor, UserDiscordLinkChallenge challenge) {
        if (!actor.getId().equals(challenge.getRequester().getId())) {
            throw invalidTicket();
        }
        if (!"VERIFIED".equals(challenge.getStatus()) || challenge.getDiscordUserId() == null) {
            throw invalidTicket();
        }
        return challenge;
    }

    private ResponseStatusException invalidTicketChecked() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Verificação Discord inválida ou expirada");
    }

    private ResponseStatusException invalidTicket() {
        return invalidTicketChecked();
    }

    private String hash(String ticket) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{40,100}")) throw invalidTicket();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 não disponível", e);
        }
    }

    private Long parseDiscordId(String raw) {
        try {
            long id = Long.parseLong(raw);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) { }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Identidade Discord inválida");
    }

    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }

    private void afterCommitPropagate(Integer userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                banPropagation.propagateForConfirmedCluster(userId);
            }
        });
    }
}
