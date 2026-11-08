package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.common.TokenService;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserSocialLoginProof;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserSocialLoginProofRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Independent Google OAuth proof. Does not log anyone in or mutate an identity. */
@Service
public class SocialLoginOAuthProofService {
    public static final String GOOGLE_SESSION_TICKET = "BRF_GOOGLE_LOGIN_REENABLE_PROOF";
    private final UserRepository users;
    private final UserSocialLoginProofRepository proofs;
    private final TokenService tokens;

    public SocialLoginOAuthProofService(
        UserRepository users, UserSocialLoginProofRepository proofs, TokenService tokens
    ) {
        this.users = users;
        this.proofs = proofs;
        this.tokens = tokens;
    }

    public record Start(String ticket) {}

    @Transactional
    public Start beginGoogle(Authentication authentication) {
        User user = requireAuthenticated(authentication);
        String token = tokens.generateToken(32);
        UserSocialLoginProof proof = new UserSocialLoginProof();
        proof.setTicketHash(hash(token));
        proof.setRequester(user);
        proof.setProvider("google");
        proof.setStatus("STARTED");
        proof.setExpiresAt(now().plusMinutes(10));
        proofs.save(proof);
        return new Start(token);
    }

    @Transactional(readOnly = true)
    public void authorizeGoogleStart(String ticket) {
        UserSocialLoginProof proof = proofs.findByTicketHash(hash(ticket))
            .orElseThrow(this::invalidProof);
        if (!"google".equals(proof.getProvider()) || !"STARTED".equals(proof.getStatus())
            || !proof.getExpiresAt().isAfter(now())) {
            throw invalidProof();
        }
    }

    @Transactional
    public void verifiedGoogleOAuth(String ticket, OAuth2AuthenticationToken authentication) {
        if (!"google".equals(authentication.getAuthorizedClientRegistrationId())) {
            throw invalidProof();
        }
        Object raw = authentication.getPrincipal().getAttributes().get("sub");
        String subject = raw == null ? "" : raw.toString().trim();
        if (subject.isEmpty() || subject.length() > 128) throw invalidProof();

        UserSocialLoginProof proof = lock(ticket);
        if (!"google".equals(proof.getProvider()) || !"STARTED".equals(proof.getStatus())) {
            throw invalidProof();
        }
        proof.setProviderSubject(subject);
        proof.setVerifiedAt(now());
        proof.setStatus("VERIFIED");
        proofs.save(proof);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String consumeVerifiedGoogle(User actor, String ticket) {
        UserSocialLoginProof proof = lock(ticket);
        if (!"google".equals(proof.getProvider()) || !"VERIFIED".equals(proof.getStatus())
            || proof.getProviderSubject() == null || proof.getProviderSubject().isBlank()
            || !proof.getRequester().getId().equals(actor.getId())) {
            throw invalidProof();
        }
        proof.setStatus("CONSUMED");
        proof.setConsumedAt(now());
        proofs.save(proof);
        return proof.getProviderSubject();
    }

    @Scheduled(fixedDelayString = "${app.auth.social-login-proof-cleanup-ms:3600000}")
    @Transactional
    public void cleanupExpired() {
        proofs.deleteByExpiresAtBefore(now().minusHours(24));
    }

    private UserSocialLoginProof lock(String ticket) {
        UserSocialLoginProof proof = proofs.findByTicketHashForUpdate(hash(ticket))
            .orElseThrow(this::invalidProof);
        if (!proof.getExpiresAt().isAfter(now())) throw invalidProof();
        return proof;
    }

    private User requireAuthenticated(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
            || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão BraFurries necessária");
        }
        User user = users.findByEmail(authentication.getName().trim().toLowerCase(Locale.ROOT))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sessão não reconhecida"));
        if (user.getMergedIntoUser() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ACCOUNT_CONSOLIDATED");
        }
        return user;
    }

    private String hash(String ticket) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{40,100}")) throw invalidProof();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(ticket.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 não disponível", ex);
        }
    }

    private ResponseStatusException invalidProof() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comprovação Google inválida ou expirada");
    }

    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
