package com.Brafurries.API.platform.drive;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserTokenRepository;
import com.Brafurries.API.entity.user.UserTokenType;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GoogleDriveDownloadTicketService {
    private static final SecureRandom RANDOM=new SecureRandom();
    private final JdbcTemplate jdbc;
    private final PlatformAuthorizationService authorization;
    private final UserRepository users;
    private final UserTokenRepository tokens;

    public GoogleDriveDownloadTicketService(JdbcTemplate jdbc,PlatformAuthorizationService authorization,
            UserRepository users,UserTokenRepository tokens) {
        this.jdbc=jdbc;this.authorization=authorization;this.users=users;this.tokens=tokens;
    }
    record Target(int actor,long authVersion,GoogleDriveConnectionService.FolderScope scope,
                  String anchor,String folder,String file) {}
    record Ticket(String ticket) {
        @Override public String toString(){return "Ticket[REDACTED]";}
    }

    @Transactional
    public Ticket issue(Authentication auth,GoogleDriveConnectionService.FolderScope scope,String anchor,String folder,String file) {
        var actor=authorization.currentUser(auth);
        if(actor.getAuthVersion()==null||actor.getMergedIntoUser()!=null ||
            !(auth.getDetails() instanceof Number signedVersion)||signedVersion.longValue()!=actor.getAuthVersion().longValue())throw unavailable();
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Integer.class,actor.getId());
        var recent=jdbc.queryForList("""
            SELECT ticket_hash FROM platform_google_drive_download_tickets
            WHERE actor_user_id=? AND created_at>=UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE LIMIT 12 FOR UPDATE
            """,byte[].class,actor.getId());
        if(recent.size()>=12)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Muitas solicitações de download");
        // Expired tickets have no further authority. Bounded opportunistic maintenance.
        jdbc.update("DELETE FROM platform_google_drive_download_tickets WHERE expires_at<UTC_TIMESTAMP(6)-INTERVAL 1 DAY LIMIT 100");
        byte[] secret=new byte[32];RANDOM.nextBytes(secret);
        String ticket=Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        jdbc.update("""
            INSERT INTO platform_google_drive_download_tickets
                (ticket_hash,actor_user_id,auth_version,google_subject,root_folder_id,anchor_folder_id,folder_id,file_id,expires_at)
            VALUES (?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6)+INTERVAL 60 SECOND)
            """,hash(ticket),actor.getId(),actor.getAuthVersion(),scope.subject(),scope.rootId(),anchor,folder,file);
        return new Ticket(ticket);
    }

    @Transactional
    public Target consume(String ticket) {
        if(ticket==null||!ticket.matches("[A-Za-z0-9_-]{43}"))throw unavailable();
        byte[] key=hash(ticket);
        var rows=jdbc.query("""
            SELECT actor_user_id,auth_version,google_subject,root_folder_id,anchor_folder_id,folder_id,file_id
            FROM platform_google_drive_download_tickets
            WHERE ticket_hash=? AND consumed_at IS NULL AND expires_at>UTC_TIMESTAMP(6) FOR UPDATE
            """,(rs,i)->new Target(rs.getInt(1),rs.getLong(2),
                new GoogleDriveConnectionService.FolderScope(rs.getString(3),rs.getString(4)),
                rs.getString(5),rs.getString(6),rs.getString(7)),key);
        if(rows.size()!=1)throw unavailable();
        if(jdbc.update("""
            UPDATE platform_google_drive_download_tickets SET consumed_at=UTC_TIMESTAMP(6)
            WHERE ticket_hash=? AND consumed_at IS NULL AND expires_at>UTC_TIMESTAMP(6)
            """,key)!=1)throw unavailable();
        return rows.getFirst();
    }

    // Only called with a target recovered from the consumed server-side ticket.
    // Do not set a SecurityContext or accept user identity supplied by the client.
    Authentication authenticate(Target target) {
        // JDBC bypasses the request's JPA persistence context for revocation checks.
        var current=jdbc.queryForList("SELECT email FROM users WHERE id=? AND auth_version=? AND merged_into_user_id IS NULL",
            String.class,target.actor(),target.authVersion());
        if(current.size()!=1||current.getFirst()==null||current.getFirst().isBlank())throw unavailable();
        var user=users.findById(target.actor()).orElseThrow(GoogleDriveDownloadTicketService::unavailable);
        if(user.getMergedIntoUser()!=null||user.getAuthVersion()==null||
            user.getAuthVersion().longValue()!=target.authVersion()||
            !current.getFirst().equals(user.getEmail())||
            tokens.existsByUserAndTypeAndUsedAtIsNullAndRevokedAtIsNull(user,UserTokenType.EMAIL_VERIFICATION))
            throw unavailable();
        return UsernamePasswordAuthenticationToken.authenticated(current.getFirst(),null,List.of());
    }

    private static byte[] hash(String ticket) {
        try{return MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
        catch(java.security.NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"Download indisponível ou expirado");
    }
}
