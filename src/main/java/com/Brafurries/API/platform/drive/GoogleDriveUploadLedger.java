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

    private void audit(Reservation r,String action) {
        jdbc.update("""
            INSERT INTO platform_google_drive_upload_audit(session_id,actor_user_id,action,occurred_at)
            VALUES (?,?,?,UTC_TIMESTAMP(6))
            """,r.id(),r.actor(),action);
    }
}
