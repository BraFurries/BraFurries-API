package com.Brafurries.API.platform.drive;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;

/** Short, committed DB transactions. Never hold a database lock during upload I/O. */
@Service
public class GoogleDriveUploadLedger {
    private final JdbcTemplate jdbc;

    public GoogleDriveUploadLedger(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    record Reservation(String id,int actor,GoogleDriveConnectionService.FolderScope scope,
                       String anchor,String destination,String staging,String providerId,
                       String name,String mime,long size) {}

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void reserve(Reservation r) {
        // Per-user serialization, independent of selected Community. Include unresolved
        // expired/canceled operations: a provider capability may still be alive.
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Integer.class,r.actor());
        List<String> unresolved=jdbc.queryForList("""
            SELECT id FROM platform_google_drive_upload_sessions
            WHERE actor_user_id=? AND status NOT IN ('PUBLISHED','CLEANED')
            LIMIT 3 FOR UPDATE
            """,String.class,r.actor());
        if (unresolved.size()>=GoogleDriveUploadPolicy.MAX_ACTIVE_PER_USER)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Limite de uploads pendentes atingido");
        // Bound new session creation even if the user repeatedly completes small files.
        List<String> recent=jdbc.queryForList("""
            SELECT id FROM platform_google_drive_upload_sessions
            WHERE actor_user_id=? AND created_at>=UTC_TIMESTAMP(6)-INTERVAL 1 DAY
            LIMIT 20 FOR UPDATE
            """,String.class,r.actor());
        if (recent.size()>=20) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Limite diário de sessões atingido");
        jdbc.update("""
            INSERT INTO platform_google_drive_upload_sessions
                (id,google_subject,root_folder_id,anchor_folder_id,destination_folder_id,
                 staging_folder_id,actor_user_id,provider_file_id,file_name,mime_type,
                 expected_size,status,created_at,expires_at,updated_at,next_reconcile_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,'RESERVED',UTC_TIMESTAMP(6),
                UTC_TIMESTAMP(6)+INTERVAL 2 HOUR,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 2 HOUR)
            """,r.id(),r.scope().subject(),r.scope().rootId(),r.anchor(),r.destination(),
            r.staging(),r.actor(),r.providerId(),r.name(),r.mime(),r.size());
        audit(r,"RESERVED");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean markOpen(Reservation r) {
        int changed=jdbc.update("""
            UPDATE platform_google_drive_upload_sessions SET status='OPEN',updated_at=UTC_TIMESTAMP(6)
            WHERE id=? AND actor_user_id=? AND status='RESERVED' AND expires_at>UTC_TIMESTAMP(6)
            """,r.id(),r.actor());
        if (changed==1) audit(r,"OPENED");
        return changed==1;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void requireCleanup(Reservation r) {
        // Even an ambiguous provider timeout is reconciled by the reserved file ID.
        // Never forget the row merely because the capability URL was not returned.
        if (jdbc.update("""
            UPDATE platform_google_drive_upload_sessions
            SET status='CLEANUP_PENDING',updated_at=UTC_TIMESTAMP(6),next_reconcile_at=UTC_TIMESTAMP(6)
            WHERE id=? AND actor_user_id=? AND status IN ('RESERVED','OPEN')
            """,r.id(),r.actor())==1) audit(r,"CLEANUP_PENDING");
    }

    record Session(Reservation reservation,String status,boolean expired,boolean providerExpiryPassed) {}

    private static final String SELECT_SESSION="""
        SELECT id,actor_user_id,google_subject,root_folder_id,anchor_folder_id,destination_folder_id,
            staging_folder_id,provider_file_id,file_name,mime_type,expected_size,status,
            expires_at<=UTC_TIMESTAMP(6),created_at<=UTC_TIMESTAMP(6)-INTERVAL 8 DAY
        FROM platform_google_drive_upload_sessions
        """;
    private static Session map(java.sql.ResultSet rs,int row) throws java.sql.SQLException {
        return new Session(new Reservation(rs.getString(1),rs.getInt(2),
            new GoogleDriveConnectionService.FolderScope(rs.getString(3),rs.getString(4)),
            rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),
            rs.getString(10),rs.getLong(11)),rs.getString(12),rs.getBoolean(13),rs.getBoolean(14));
    }

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public Session owned(String id,int actor) {
        var rows=jdbc.query(SELECT_SESSION+" WHERE id=? AND actor_user_id=?",
            GoogleDriveUploadLedger::map,id,actor);
        if (rows.isEmpty()) throw GoogleDriveBrowserService.inaccessible();
        return rows.getFirst();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean claimPublication(Reservation r) {
        int changed=jdbc.update("""
            UPDATE platform_google_drive_upload_sessions
            SET status='PUBLISHING',updated_at=UTC_TIMESTAMP(6),next_reconcile_at=NULL
            WHERE id=? AND actor_user_id=? AND status='OPEN' AND expires_at>UTC_TIMESTAMP(6)
            """,r.id(),r.actor());
        if (changed==1) audit(r,"PUBLISH_STARTED");
        return changed==1;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void finishPublication(Reservation r) {
        if (jdbc.update("""
            UPDATE platform_google_drive_upload_sessions SET status='PUBLISHED',
                published_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6),next_reconcile_at=NULL
            WHERE id=? AND actor_user_id=? AND status='PUBLISHING'
            """,r.id(),r.actor())!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Publicação exige reconciliação");
        audit(r,"PUBLISHED");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void publicationFailed(Reservation r) {
        if (jdbc.update("""
            UPDATE platform_google_drive_upload_sessions SET status='RECONCILE_REQUIRED',
                updated_at=UTC_TIMESTAMP(6),next_reconcile_at=NULL
            WHERE id=? AND actor_user_id=? AND status='PUBLISHING'
            """,r.id(),r.actor())==1) audit(r,"RECONCILE_REQUIRED");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void cancel(Reservation r) {
        if (jdbc.update("""
            UPDATE platform_google_drive_upload_sessions SET status='CANCELED',
                updated_at=UTC_TIMESTAMP(6),next_reconcile_at=UTC_TIMESTAMP(6)
            WHERE id=? AND actor_user_id=? AND status IN ('RESERVED','OPEN')
            """,r.id(),r.actor())==1) audit(r,"CANCELED");
    }

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public List<Session> due() {
        return jdbc.query(SELECT_SESSION+"""
             WHERE status IN ('RESERVED','OPEN','CANCELED','EXPIRED','CLEANUP_PENDING')
               AND next_reconcile_at<=UTC_TIMESTAMP(6)
               AND (expires_at<=UTC_TIMESTAMP(6) OR status IN ('CANCELED','EXPIRED','CLEANUP_PENDING'))
             ORDER BY next_reconcile_at,id LIMIT 10
            """,GoogleDriveUploadLedger::map);
    }

    // Atomic claim with lease. No overlapping publication because only OPEN can publish.
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean claimCleanup(Reservation r) {
        int changed=jdbc.update("""
            UPDATE platform_google_drive_upload_sessions SET status='CLEANUP_PENDING',
                updated_at=UTC_TIMESTAMP(6),next_reconcile_at=UTC_TIMESTAMP(6)+INTERVAL 1 HOUR
            WHERE id=? AND actor_user_id=? AND next_reconcile_at<=UTC_TIMESTAMP(6)
              AND status IN ('RESERVED','OPEN','CANCELED','EXPIRED','CLEANUP_PENDING')
              AND (expires_at<=UTC_TIMESTAMP(6) OR status IN ('CANCELED','EXPIRED','CLEANUP_PENDING'))
            """,r.id(),r.actor());
        if(changed==1) audit(r,"CLEANUP_PENDING");
        return changed==1;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void cleaned(Reservation r) {
        if (jdbc.update("""
            UPDATE platform_google_drive_upload_sessions SET status='CLEANED',
                updated_at=UTC_TIMESTAMP(6),next_reconcile_at=NULL
            WHERE id=? AND actor_user_id=? AND status='CLEANUP_PENDING'
              AND created_at<=UTC_TIMESTAMP(6)-INTERVAL 8 DAY
            """,r.id(),r.actor())==1) audit(r,"CLEANED");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void retryCleanup(Reservation r) {
        jdbc.update("""
            UPDATE platform_google_drive_upload_sessions
            SET next_reconcile_at=UTC_TIMESTAMP(6)+INTERVAL 1 DAY,updated_at=UTC_TIMESTAMP(6)
            WHERE id=? AND actor_user_id=? AND status='CLEANUP_PENDING'
            """,r.id(),r.actor());
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void cleanupNeedsReview(Reservation r) {
        if (jdbc.update("""
            UPDATE platform_google_drive_upload_sessions SET status='RECONCILE_REQUIRED',
                updated_at=UTC_TIMESTAMP(6),next_reconcile_at=NULL
            WHERE id=? AND actor_user_id=? AND status='CLEANUP_PENDING'
            """,r.id(),r.actor())==1) audit(r,"RECONCILE_REQUIRED");
    }

    private void audit(Reservation r,String action) {
        jdbc.update("""
            INSERT INTO platform_google_drive_upload_audit(session_id,actor_user_id,action,occurred_at)
            VALUES (?,?,?,UTC_TIMESTAMP(6))
            """,r.id(),r.actor(),action);
    }
}
