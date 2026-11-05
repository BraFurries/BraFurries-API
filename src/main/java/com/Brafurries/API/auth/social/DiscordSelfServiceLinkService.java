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
import org.springframework.dao.DataIntegrityViolationException;
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
    private final UserIdentityLinkRepository links;
    private final UserDiscordLinkChallengeRepository challenges;
    private final IdentityBanPropagationService banPropagation;
    private final TokenService tokens;

    public DiscordSelfServiceLinkService(
        UserRepository users,
        UserDiscordRepository discord,
        UserIdentityLinkRepository links,
        UserDiscordLinkChallengeRepository challenges,
        IdentityBanPropagationService banPropagation,
        TokenService tokens
    ) {
        this.users = users;
        this.discord = discord;
        this.links = links;
        this.challenges = challenges;
        this.banPropagation = banPropagation;
        this.tokens = tokens;
    }

    public record Start(String ticket) {}
    public record Preview(String discordUsername, boolean hasExistingHistory, String notice) {}
    public record Confirmation(String status, String notice) {}

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
        boolean legacy = discord.findByDiscordUserId(challenge.getDiscordUserId())
            .filter(link -> !link.getUser().getId().equals(actor.getId()))
            .isPresent();
        return new Preview(challenge.getDiscordUsername(), legacy,
            legacy
                ? "Este Discord já tem um registro histórico. Ele será conectado à sua conta sem apagar nem misturar dados, permissões ou punições."
                : "Você está prestes a conectar este Discord à sua conta BraFurries.");
    }

    @Transactional
    public Confirmation confirm(Authentication authentication, String ticket) {
        User actor = requireAuthenticated(authentication);
        UserDiscordLinkChallenge challenge = requireLockedChallenge(ticket);
        requireVerifiedChallenge(actor, challenge);

        UserDiscord existing = discord.findByDiscordUserId(challenge.getDiscordUserId()).orElse(null);
        UserDiscord own = discord.findByUser(actor).orElse(null);
        String outcome;

        if (own != null && !own.getDiscordUserId().equals(challenge.getDiscordUserId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Sua conta já tem outro Discord conectado. Desconecte-o antes de tentar novamente.");
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
        } else {
            Integer otherUserId = existing.getUser().getId();
            // Lock both users in a deterministic order so concurrent claims
            // cannot create different identity clusters or race deletion.
            List<Integer> ordered = java.util.stream.Stream.of(actor.getId(), otherUserId).sorted().toList();
            for (Integer userId : ordered) {
                users.findByIdForUpdate(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Conta alterada durante a vinculação"));
            }
            // Only one authenticated website account may claim the Discord
            // profile through self-service. Existing ADMIN moderation evidence
            // alone does not confer access to someone else's private data.
            boolean claimedByAnother = links.findActiveLinksContainingUser(otherUserId).stream()
                .anyMatch(link -> link.getStatus() == UserIdentityLinkStatus.CONFIRMED
                    && link.getLinkSource() == UserIdentityLinkSource.SOCIAL
                    && !link.getUserA().getId().equals(actor.getId()));
            if (claimedByAnother) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este Discord já foi recuperado por outra conta. Faça a recuperação a partir dessa conta.");
            }
            UserIdentityLink known = links.findActiveByPair(actor.getId(), otherUserId).orElse(null);
            if (known != null && (known.getStatus() != UserIdentityLinkStatus.CONFIRMED
                || known.getLinkSource() != UserIdentityLinkSource.SOCIAL)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Existe uma revisão de identidade em aberto. Não é possível sobrepor esse vínculo automaticamente.");
            }
            if (known == null) {
                UserIdentityLink link = new UserIdentityLink();
                link.setUserA(actor);
                link.setUserB(existing.getUser());
                link.setStatus(UserIdentityLinkStatus.CONFIRMED);
                link.setLinkSource(UserIdentityLinkSource.SOCIAL);
                link.setReason("Vínculo confirmado por conta autenticada e OAuth Discord do titular");
                link.setCreatedByUser(actor);
                links.saveAndFlush(link);
                afterCommitPropagate(actor.getId());
            }
            outcome = "HISTORY_CONNECTED";
        }

        challenge.setStatus("CONSUMED");
        challenge.setConsumedAt(now());
        challenges.save(challenge);
        return new Confirmation(outcome, "Discord confirmado. Os registros anteriores foram preservados.");
    }

    /**
     * Select only an OAuth-proven *direct* legacy Discord profile for
     * read-only personal history. Never accept generic ADMIN/SUSPECTED links.
     */
    @Transactional(readOnly = true)
    public User legacyProfileFor(User authenticated) {
        if (discord.findByUser(authenticated).isPresent()) return authenticated;
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
        return challenges.findByTicketHash(hash(ticket)).orElseThrow(this::invalidTicketChecked);
    }

    private UserDiscordLinkChallenge requireLockedChallenge(String ticket) {
        return challenges.findByTicketHashForUpdate(hash(ticket)).orElseThrow(this::invalidTicketChecked);
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
