package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.common.TokenService;
import com.Brafurries.API.auth.common.dto.AuthDtos.LoginResponse;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.repository.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Authenticated proof of the site account plus independent Discord OAuth proof.
 * An opaque, short-lived, one-time challenge is bound to the OAuth HTTP session.
 */
@Service
public class SocialAccountLinkService {
    public static final String SESSION_CHALLENGE = "brafurries.oauth.discord-link-challenge";
    private static final Pattern SAFE_TOKEN = Pattern.compile("^[A-Za-z0-9_-]{32,150}$");
    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final TokenService tokens;
    private final VerifiedAccountConsolidationService consolidation;
    private final AuthSocialService authSocial;
    private final SocialFrontendOriginPolicy origins;
    private final String apiBaseUrl;

    public SocialAccountLinkService(
        JdbcTemplate jdbc,
        UserRepository users,
        TokenService tokens,
        VerifiedAccountConsolidationService consolidation,
        AuthSocialService authSocial,
        SocialFrontendOriginPolicy origins,
        @Value("${app.base-url}") String apiBaseUrl
    ) {
        this.jdbc = jdbc;
        this.users = users;
        this.tokens = tokens;
        this.consolidation = consolidation;
        this.authSocial = authSocial;
        this.origins = origins;
        this.apiBaseUrl = apiBaseUrl;
    }

    public record LinkUrl(String url) {}

    @Transactional
    public LinkUrl issueChallenge(Authentication authentication, String frontendOrigin) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Entre em sua conta para conectar o Discord.");
        }
        String principal = authentication.getName();
        User user = users.findByEmail(principal)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Conta não encontrada"));
        if (user.getMergedIntoUserId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Sua conta já foi consolidada. Entre novamente.");
        }
        Integer recent = jdbc.queryForObject("""
            SELECT COUNT(*) FROM user_social_link_challenges
            WHERE initiated_user_id=? AND created_at>DATE_SUB(NOW(6), INTERVAL 15 MINUTE)
            """, Integer.class, user.getId());
        if (recent != null && recent >= 5) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "Muitas tentativas de conexão. Tente novamente mais tarde.");
        }
        String opaque = tokens.generateToken(32);
        jdbc.update("""
            INSERT INTO user_social_link_challenges
              (token_hash,initiated_user_id,expires_at)
            VALUES (?,?,DATE_ADD(NOW(6), INTERVAL 10 MINUTE))
            """, sha256(opaque), user.getId());

        String url = UriComponentsBuilder.fromUriString(apiBaseUrl)
            .path("/auth/oauth2/discord/link")
            .queryParam("challenge", opaque)
            .queryParam("frontend_origin", origins.resolve(frontendOrigin))
            .build()
            .encode()
            .toUriString();
        return new LinkUrl(url);
    }

    @Transactional(readOnly = true)
    public void begin(String opaque, String frontendOrigin, HttpServletRequest request) {
        if (opaque == null || !SAFE_TOKEN.matcher(opaque).matches())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vinculação inválida");
        String hash = sha256(opaque);
        List<Map<String,Object>> rows = jdbc.queryForList("""
            SELECT initiated_user_id FROM user_social_link_challenges
             WHERE token_hash=? AND consumed_at IS NULL AND expires_at>NOW(6)
            """, hash);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.GONE, "Vinculação expirada. Reinicie no painel.");
        var session = request.getSession(true);
        session.setAttribute(SESSION_CHALLENGE, hash);
        session.setAttribute(SocialFrontendOriginPolicy.SESSION_ATTRIBUTE, origins.resolve(frontendOrigin));
    }

    @Transactional
    public LoginResponse complete(String challengeHash, OAuth2AuthenticationToken verifiedOAuth) {
        if (verifiedOAuth == null || !"discord".equalsIgnoreCase(verifiedOAuth.getAuthorizedClientRegistrationId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Confirmação do Discord obrigatória");
        }
        Object discordIdRaw = verifiedOAuth.getPrincipal().getAttributes().get("id");
        if (discordIdRaw == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador Discord ausente");
        long discordUserId;
        try {
            discordUserId = Long.parseLong(discordIdRaw.toString());
            if (discordUserId <= 0) throw new NumberFormatException("Discord ID invalid");
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador Discord inválido");
        }

        List<Map<String,Object>> records = jdbc.queryForList("""
            SELECT initiated_user_id FROM user_social_link_challenges
            WHERE token_hash=? AND consumed_at IS NULL AND expires_at>NOW(6)
            FOR UPDATE
            """, challengeHash);
        if (records.size() != 1) throw new ResponseStatusException(HttpStatus.GONE, "Vinculação expirada. Reinicie no painel.");
        Integer userId = ((Number)records.getFirst().get("initiated_user_id")).intValue();
        jdbc.update("UPDATE user_social_link_challenges SET consumed_at=NOW(6) WHERE token_hash=?", challengeHash);

        Map<String,Object> attributes = verifiedOAuth.getPrincipal().getAttributes();
        String username = firstNonBlank(attributes.get("username"), attributes.get("global_name"), "discord");
        String displayName = firstNonBlank(attributes.get("global_name"), attributes.get("username"));
        User canonical = consolidation.linkVerifiedDiscord(userId, discordUserId, username, displayName);
        return authSocial.issueLinkedAccountLogin(canonical.getId());
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) return value.toString().trim();
        }
        return null;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponível", ex);
        }
    }
}
