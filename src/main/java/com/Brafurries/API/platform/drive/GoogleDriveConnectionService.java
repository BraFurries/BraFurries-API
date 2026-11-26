package com.Brafurries.API.platform.drive;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GoogleDriveConnectionService {
    static final String CALLBACK_PATH = "/user/me/platform-drive/oauth/callback";
    private static final String REFRESH_PURPOSE = "drive:institutional-refresh:v1";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final PlatformAuthorizationService authorization;
    private final JdbcTemplate jdbc;
    private final GoogleDriveOAuthClient google;
    private final String clientId, clientSecret, account, folderId, redirectUri;
    private final boolean enabled;
    private final DriveTokenCipher cipher;

    public GoogleDriveConnectionService(
        PlatformAuthorizationService authorization, JdbcTemplate jdbc, GoogleDriveOAuthClient google,
        @Value("${GOOGLE_DRIVE_ENABLED:false}") boolean enabled,
        @Value("${GOOGLE_DRIVE_CLIENT_ID:}") String clientId,
        @Value("${GOOGLE_DRIVE_CLIENT_SECRET:}") String clientSecret,
        @Value("${GOOGLE_DRIVE_ACCOUNT_EMAIL:}") String account,
        @Value("${GOOGLE_DRIVE_ROOT_FOLDER_ID:}") String folderId,
        @Value("${GOOGLE_DRIVE_ENCRYPTION_KEY:}") String encryptionKey,
        @Value("${app.base-url}") String baseUrl
    ) {
        this.authorization = authorization;
        this.jdbc = jdbc;
        this.google = google;
        this.enabled = enabled;
        this.clientId = clientId.trim();
        this.clientSecret = clientSecret.trim();
        this.account = account.trim().toLowerCase(Locale.ROOT);
        this.folderId = folderId.trim();
        this.redirectUri = baseUrl.replaceFirst("/+$", "") + CALLBACK_PATH;
        DriveTokenCipher candidate;
        try { candidate = new DriveTokenCipher(encryptionKey.trim()); }
        catch (IllegalArgumentException ex) { candidate = null; }
        this.cipher = candidate;
    }

    record Connection(String subject, String email, String client, byte[] ciphertext) {}
    public record Status(boolean configured, boolean connected, String accountEmail, boolean reconnectRequired) {}
    public record Start(String authorizationUrl) {}
    public record RootCheck(String status) {}
    public record RootPickerIdentity(String clientId, String folderId, String googleSubject, String googleEmail) {}

    private boolean configured() {
        return enabled && cipher != null && clientId.matches("[A-Za-z0-9._-]+\\.apps\\.googleusercontent\\.com")
            && !clientSecret.isBlank() && account.matches("[^\\s@]+@[^\\s@]+")
            && folderId.matches("[A-Za-z0-9_-]{5,200}") && secureRedirect();
    }

    private boolean secureRedirect() {
        try {
            URI uri = URI.create(redirectUri);
            return uri.getUserInfo() == null && uri.getHost() != null && uri.getQuery() == null
                && uri.getFragment() == null && CALLBACK_PATH.equals(uri.getPath())
                && ("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme())
                    && "localhost".equals(uri.getHost())));
        } catch (IllegalArgumentException ex) { return false; }
    }

    private void requireConfigured() {
        if (!configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Integração Google Drive não configurada");
    }

    private void lockOwner(int owner) {
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Integer.class, owner);
    }

    private Connection connection() { return connection(false); }

    private Connection connection(boolean lock) {
        List<Connection> rows = jdbc.query("""
            SELECT google_subject, google_email, oauth_client_id, refresh_token_ciphertext
            FROM platform_google_drive_connection WHERE id=1
            """+(lock?" FOR UPDATE":""), (rs, i) -> new Connection(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getBytes(4)));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean matchesConfig(Connection connection) {
        return connection != null && account.equalsIgnoreCase(connection.email())
            && clientId.equals(connection.client());
    }

    @Transactional(readOnly = true)
    public Status status(Authentication auth) {
        authorization.requireOwner(auth);
        if (!configured()) return new Status(false, false, null, false);
        Connection existing = connection();
        boolean usable = matchesConfig(existing);
        return new Status(true, usable, account, existing != null && !usable);
    }

    @Transactional
    public Start start(Authentication auth) {
        int owner = authorization.requireOwner(auth).getId();
        requireConfigured();
        lockOwner(owner);
        String state = randomToken();
        String verifier = randomToken();
        jdbc.update("DELETE FROM platform_google_drive_oauth_attempt WHERE owner_user_id=?", owner);
        jdbc.update("""
            INSERT INTO platform_google_drive_oauth_attempt
                (owner_user_id,state_hash,verifier_ciphertext,created_at,expires_at)
            VALUES (?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 10 MINUTE)
            """, owner, digest(state), cipher.encrypt(verifier, attemptPurpose(owner)));
        audit(owner, "AUTHORIZATION_STARTED");
        String query = GoogleDriveOAuthClient.form(Map.of(
            "client_id", clientId, "redirect_uri", redirectUri, "response_type", "code",
            "scope", "openid email " + GoogleDriveOAuthClient.DRIVE_SCOPE,
            "access_type", "offline", "prompt", "consent select_account", "login_hint", account,
            "state", state, "code_challenge", Base64.getUrlEncoder().withoutPadding().encodeToString(digest(verifier)),
            "code_challenge_method", "S256"));
        return new Start("https://accounts.google.com/o/oauth2/v2/auth?" + query);
    }

    // Consume the attempt even if Google rejects the code or the account is wrong.
    // The owner row serializes connect/reconnect/disconnect and newer authorizations.
    @Transactional(noRollbackFor = ResponseStatusException.class)
    public Status complete(Authentication auth, String state, String code) {
        int owner = authorization.requireOwner(auth).getId();
        requireConfigured();
        if (!validState(state) || !validCode(code)) throw invalidAttempt();
        lockOwner(owner);
        List<byte[]> rows = jdbc.query("""
            SELECT verifier_ciphertext FROM platform_google_drive_oauth_attempt
            WHERE owner_user_id=? AND state_hash=? AND expires_at>UTC_TIMESTAMP(6) FOR UPDATE
            """, (rs,i) -> rs.getBytes(1), owner, digest(state));
        if (rows.isEmpty()) throw invalidAttempt();
        jdbc.update("DELETE FROM platform_google_drive_oauth_attempt WHERE owner_user_id=?", owner);
        String subject;
        String refresh;
        try {
            String verifier = cipher.decrypt(rows.getFirst(), attemptPurpose(owner));
            JsonNode token = google.token(Map.of("grant_type", "authorization_code",
                "client_id", clientId, "client_secret", clientSecret, "redirect_uri", redirectUri,
                "code", code, "code_verifier", verifier));
            refresh = token.path("refresh_token").asText("");
            String access = token.path("access_token").asText("");
            if (refresh.isBlank() || refresh.length()>4000 || access.isBlank()
                || !List.of(token.path("scope").asText("").split("\\s+")).contains(GoogleDriveOAuthClient.DRIVE_SCOPE))
                throw new IllegalStateException("Incomplete Google consent");
            JsonNode identity = google.identity(access);
            subject = identity.path("sub").asText("");
            if (!identity.path("email_verified").isBoolean() || !identity.path("email_verified").asBoolean()
                || !account.equalsIgnoreCase(identity.path("email").asText(""))
                || !subject.matches("[A-Za-z0-9_-]{1,128}"))
                throw new IllegalStateException("Unexpected institutional account");
        } catch (RuntimeException ex) {
            audit(owner, "AUTHORIZATION_FAILED");
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Autorização inválida. Selecione a conta institucional e conceda o acesso solicitado.");
        }
        Connection previous = connection();
        // Do not silently replace an established Google subject, even if its email matches.
        if (previous != null && !previous.subject().equals(subject)) {
            audit(owner, "AUTHORIZATION_FAILED");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Identidade institucional diferente");
        }
        jdbc.update("""
            INSERT INTO platform_google_drive_connection
                (id,google_subject,google_email,oauth_client_id,refresh_token_ciphertext,connected_by_user_id,connected_at)
            VALUES (1,?,?,?,?,?,UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE google_subject=VALUES(google_subject),google_email=VALUES(google_email),
                oauth_client_id=VALUES(oauth_client_id),refresh_token_ciphertext=VALUES(refresh_token_ciphertext),
                connected_by_user_id=VALUES(connected_by_user_id),connected_at=VALUES(connected_at)
            """, subject, account, clientId, cipher.encrypt(refresh, REFRESH_PURPOSE), owner);
        audit(owner, previous == null ? "CONNECTED" : "RECONNECTED");
        return new Status(true, true, account, false);
    }

    /** Browser obtains its own short-lived Google token. Never expose the stored token. */
    @Transactional(readOnly = true)
    public RootPickerIdentity rootPickerIdentity(Authentication auth) {
        authorization.requireOwner(auth);
        requireConfigured();
        Connection existing = connection();
        if (!matchesConfig(existing)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Conecte a conta institucional primeiro");
        }
        return new RootPickerIdentity(clientId, folderId, existing.subject(), existing.email());
    }

    static RootCheck classifyRoot(JsonNode root, String expectedId) {
        if (root == null || !expectedId.equals(root.path("id").asText()))
            return new RootCheck("UNAVAILABLE");
        if (!"application/vnd.google-apps.folder".equals(root.path("mimeType").asText()))
            return new RootCheck("NOT_FOLDER");
        if (!root.path("trashed").isBoolean() || !root.path("ownedByMe").isBoolean()
                || !root.path("capabilities").path("canShare").isBoolean())
            return new RootCheck("UNAVAILABLE");
        if (root.path("trashed").asBoolean()) return new RootCheck("TRASHED");
        if (!root.path("ownedByMe").asBoolean()) return new RootCheck("NOT_OWNED");
        if (!root.path("capabilities").path("canShare").asBoolean()) return new RootCheck("CANNOT_SHARE");
        return new RootCheck("ACCESSIBLE");
    }

    @Transactional
    public RootCheck checkRoot(Authentication auth) {
        int owner = authorization.requireOwner(auth).getId();
        requireConfigured();
        lockOwner(owner);
        Connection existing = connection();
        if (!matchesConfig(existing)) return new RootCheck("RECONNECT_REQUIRED");
        try {
            String refresh = cipher.decrypt(existing.ciphertext(), REFRESH_PURPOSE);
            JsonNode token = google.token(Map.of("grant_type","refresh_token",
                "client_id",clientId,"client_secret",clientSecret,"refresh_token",refresh));
            String access = token.path("access_token").asText("");
            if (access.isBlank()) return new RootCheck("UNAVAILABLE");
            String rotated = token.path("refresh_token").asText("");
            if (!rotated.isBlank()) {
                if (rotated.length()>4000) return new RootCheck("RECONNECT_REQUIRED");
                jdbc.update("UPDATE platform_google_drive_connection SET refresh_token_ciphertext=? WHERE id=1",
                    cipher.encrypt(rotated, REFRESH_PURPOSE));
            }
            try {
                JsonNode root = google.root(access, folderId);
                return classifyRoot(root, folderId);
            } catch (GoogleDriveOAuthClient.ProviderFailure ex) {
                return new RootCheck(switch (ex.status) {
                    case 401 -> "RECONNECT_REQUIRED";
                    case 403 -> "ACCESS_DENIED";
                    // Google intentionally does not distinguish invisible files from missing IDs.
                    case 404 -> "NOT_AUTHORIZED";
                    default -> "UNAVAILABLE";
                });
            }
        } catch (GoogleDriveOAuthClient.ProviderFailure ex) {
            return new RootCheck(ex.status == 400 || ex.status == 401 ? "RECONNECT_REQUIRED" : "UNAVAILABLE");
        } catch (IllegalStateException ex) {
            return new RootCheck("RECONNECT_REQUIRED");
        }
    }

    record FolderScope(String subject, String rootId) {}

    // Internal metadata only; no credentials cross this boundary.
    FolderScope folderScope() { return folderScope(false); }

    FolderScope folderScope(boolean lock) {
        requireConfigured();
        Connection existing = connection(lock);
        if (!matchesConfig(existing)) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Conecte a conta institucional primeiro");
        return new FolderScope(existing.subject(), folderId);
    }

    public record ManagedFolder(String folderId, String parentFolderId, String name, Instant verifiedAt) {}
    record VerifiedFolder(String parentId, String name) {}

    @Transactional(readOnly = true)
    public List<ManagedFolder> managedFolders(Authentication auth) {
        authorization.requireOwner(auth);
        requireConfigured();
        Connection existing = connection();
        if (!matchesConfig(existing)) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Conecte a conta institucional primeiro");
        return jdbc.query("""
            SELECT folder_id,parent_folder_id,folder_name,verified_at
            FROM platform_google_drive_managed_folders
            WHERE google_subject=? AND root_folder_id=?
            ORDER BY folder_name,folder_id
            """, (rs,i) -> new ManagedFolder(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getObject(4,LocalDateTime.class).toInstant(ZoneOffset.UTC)), existing.subject(),folderId);
    }

    static boolean validFolderId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{5,200}");
    }

    // No permission mutation here. Validate each ancestor server-side, including
    // the configured root. Shortcuts, incomplete metadata and cycles fail closed.
    VerifiedFolder verifyFolder(String access, String target) {
        if (!validFolderId(target) || target.equals(folderId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Selecione uma subpasta da raiz institucional");
        Set<String> visited = new HashSet<>();
        String current = target;
        VerifiedFolder result = null;
        for (int depth=0; depth<32; depth++) {
            if (!visited.add(current)) break;
            JsonNode node = google.root(access,current);
            if (!"ACCESSIBLE".equals(classifyRoot(node,current).status()))
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Pasta indisponível, não institucional ou sem capacidade de compartilhar");
            if (current.equals(folderId)) return result;
            JsonNode parents = node.path("parents");
            if (!parents.isArray() || parents.size()!=1 || !parents.get(0).isTextual()) break;
            String parent = parents.get(0).asText();
            if (!validFolderId(parent) || parent.equals(current)) break;
            if (result == null) {
                JsonNode name = node.path("name");
                if (!name.isTextual() || name.asText().isBlank() ||
                    name.asText().codePointCount(0,name.asText().length())>255)
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Nome de pasta inválido");
                result = new VerifiedFolder(parent,name.asText());
            }
            current = parent;
        }
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
            "Não foi possível comprovar que a pasta pertence à raiz institucional");
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public ManagedFolder registerFolder(Authentication auth, String target) {
        int owner = authorization.requireOwner(auth).getId();
        requireConfigured();
        if (!validFolderId(target) || target.equals(folderId))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Selecione uma subpasta da raiz institucional");
        lockOwner(owner);
        Connection existing = connection();
        if (!matchesConfig(existing)) throw new ResponseStatusException(HttpStatus.CONFLICT,
            "Conecte a conta institucional primeiro");
        VerifiedFolder verified;
        try {
            String refresh = cipher.decrypt(existing.ciphertext(), REFRESH_PURPOSE);
            JsonNode token = google.token(Map.of("grant_type","refresh_token",
                "client_id",clientId,"client_secret",clientSecret,"refresh_token",refresh));
            String access = token.path("access_token").asText("");
            if (access.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Google indisponível");
            String rotated = token.path("refresh_token").asText("");
            if (!rotated.isBlank()) {
                if (rotated.length()>4000) throw new ResponseStatusException(HttpStatus.CONFLICT,"Reconecte a conta institucional");
                jdbc.update("UPDATE platform_google_drive_connection SET refresh_token_ciphertext=? WHERE id=1",
                    cipher.encrypt(rotated,REFRESH_PURPOSE));
            }
            verified = verifyFolder(access,target);
        } catch (GoogleDriveOAuthClient.ProviderFailure ex) {
            throw new ResponseStatusException(switch (ex.status) {
                case 400,401 -> HttpStatus.CONFLICT;
                case 403,404 -> HttpStatus.UNPROCESSABLE_ENTITY;
                default -> HttpStatus.SERVICE_UNAVAILABLE;
            }, "Não foi possível verificar a pasta no Google; autorize a pasta e seus ancestrais ou reconecte a conta");
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Reconecte a conta institucional");
        }
        jdbc.update("""
            INSERT INTO platform_google_drive_managed_folders
              (google_subject,root_folder_id,folder_id,parent_folder_id,folder_name,
               registered_by_user_id,registered_at,verified_by_user_id,verified_at)
            VALUES (?,?,?,?,?,?,UTC_TIMESTAMP(6),?,UTC_TIMESTAMP(6))
            ON DUPLICATE KEY UPDATE parent_folder_id=VALUES(parent_folder_id),
                folder_name=VALUES(folder_name),verified_by_user_id=VALUES(verified_by_user_id),
                verified_at=VALUES(verified_at)
            """,existing.subject(),folderId,target,verified.parentId(),verified.name(),owner,owner);
        return jdbc.queryForObject("""
            SELECT folder_id,parent_folder_id,folder_name,verified_at
            FROM platform_google_drive_managed_folders
            WHERE google_subject=? AND root_folder_id=? AND folder_id=?
            """,(rs,i)->new ManagedFolder(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getObject(4,LocalDateTime.class).toInstant(ZoneOffset.UTC)),existing.subject(),folderId,target);
    }

    // Local removal only. Google grant revocation is explicit in the Google account.
    @Transactional
    public void disconnect(Authentication auth) {
        int owner = authorization.requireOwner(auth).getId();
        lockOwner(owner);
        jdbc.update("DELETE FROM platform_google_drive_oauth_attempt WHERE owner_user_id=?", owner);
        if (jdbc.update("DELETE FROM platform_google_drive_connection WHERE id=1") > 0)
            audit(owner, "DISCONNECTED");
    }

    private void audit(int owner, String action) {
        jdbc.update("INSERT INTO platform_google_drive_audit(actor_user_id,action) VALUES (?,?)", owner, action);
    }

    static boolean validState(String value) { return value != null && value.matches("[A-Za-z0-9_-]{43}"); }
    static boolean validCode(String value) {
        return value != null && !value.isBlank() && value.length()<=2048
            && value.chars().allMatch(c -> c >= 33 && c <= 126);
    }
    private static ResponseStatusException invalidAttempt() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Autorização expirada ou inválida. Inicie novamente.");
    }
    private static String attemptPurpose(int owner) { return "drive:oauth-verifier:v1:" + owner; }
    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
