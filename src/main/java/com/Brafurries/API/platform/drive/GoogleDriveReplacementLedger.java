package com.Brafurries.API.platform.drive;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GoogleDriveReplacementLedger {
    private final JdbcTemplate jdbc;
    public GoogleDriveReplacementLedger(JdbcTemplate jdbc){this.jdbc=jdbc;}
    record Commit(String status,String newRevision){}
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public GoogleDriveUploadLedger.Replacement intent(GoogleDriveUploadLedger.Reservation r){
        var rows=jdbc.query("""
            SELECT target_file_id,expected_version,previous_revision_id,previous_sha256,previous_name
            FROM platform_google_drive_replacement_intents WHERE session_id=? AND google_subject=?
            """,(rs,i)->new GoogleDriveUploadLedger.Replacement(rs.getString(1),rs.getString(2),rs.getString(3),
                rs.getString(4),rs.getString(5)),r.id(),r.scope().subject());
        if(rows.size()!=1)throw GoogleDriveBrowserService.inaccessible();
        return rows.getFirst();
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public Commit find(String id){
        var rows=jdbc.query("SELECT status,new_revision_id FROM platform_google_drive_replacement_commits WHERE session_id=?",
            (rs,i)->new Commit(rs.getString(1),rs.getString(2)),id);
        return rows.isEmpty()?null:rows.getFirst();
    }

    // Shared short lock with rename reservation; never hold it during Google I/O.
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void claim(GoogleDriveUploadLedger.Reservation r,GoogleDriveUploadLedger.Replacement i,String sha,String version){
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Integer.class,r.actor());
        lockConnection(r.scope().subject());
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM platform_google_drive_rename_operations
                WHERE google_subject=? AND active_file_id=?)
                OR EXISTS(SELECT 1 FROM platform_google_drive_transfer_operations WHERE google_subject=? AND active_file_id=?)
                OR EXISTS(SELECT 1 FROM platform_google_drive_trash_operations WHERE google_subject=? AND active_file_id=?)
            """,Boolean.class,r.scope().subject(),i.target(),r.scope().subject(),i.target(),r.scope().subject(),i.target())))throw conflict();
        if(jdbc.update("""
            UPDATE platform_google_drive_upload_sessions SET status='PUBLISHING',updated_at=UTC_TIMESTAMP(6),next_reconcile_at=NULL
            WHERE id=? AND actor_user_id=? AND status='OPEN' AND expires_at>UTC_TIMESTAMP(6)
            """,r.id(),r.actor())!=1)throw conflict();
        jdbc.update("""
            INSERT INTO platform_google_drive_replacement_commits
            (session_id,google_subject,target_file_id,staged_sha256,staged_version,status)
            VALUES (?,?,?,?,?,'APPLYING')
            """,r.id(),r.scope().subject(),i.target(),sha,version);
        audit(r,"PUBLISH_STARTED");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void complete(GoogleDriveUploadLedger.Reservation r,String revision){
        if(jdbc.update("""
            UPDATE platform_google_drive_replacement_commits SET status='APPLIED',new_revision_id=?,updated_at=UTC_TIMESTAMP(6)
            WHERE session_id=? AND status='APPLYING'
            """,revision,r.id())!=1)throw conflict();
        // The staged copy is NOT published. Existing cleanup safely handles it.
        queueCleanup(r);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void abort(GoogleDriveUploadLedger.Reservation r){
        if(jdbc.update("""
            UPDATE platform_google_drive_replacement_commits SET status='ABORTED',updated_at=UTC_TIMESTAMP(6)
            WHERE session_id=? AND status='APPLYING'
            """,r.id())!=1)throw conflict();
        queueCleanup(r);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void uncertain(GoogleDriveUploadLedger.Reservation r){
        if(jdbc.update("""
            UPDATE platform_google_drive_replacement_commits SET status='RECONCILE_REQUIRED',updated_at=UTC_TIMESTAMP(6)
            WHERE session_id=? AND status='APPLYING'
            """,r.id())!=1)throw conflict();
        if(jdbc.update("""
            UPDATE platform_google_drive_upload_sessions SET status='RECONCILE_REQUIRED',updated_at=UTC_TIMESTAMP(6),next_reconcile_at=NULL
            WHERE id=? AND actor_user_id=? AND status='PUBLISHING'
            """,r.id(),r.actor())!=1)throw conflict();
        audit(r,"RECONCILE_REQUIRED");
    }

    private void queueCleanup(GoogleDriveUploadLedger.Reservation r){
        if(jdbc.update("""
            UPDATE platform_google_drive_upload_sessions SET status='CLEANUP_PENDING',updated_at=UTC_TIMESTAMP(6),next_reconcile_at=UTC_TIMESTAMP(6)
            WHERE id=? AND actor_user_id=? AND status='PUBLISHING'
            """,r.id(),r.actor())!=1)throw conflict();
        audit(r,"CLEANUP_PENDING");
    }
    private void lockConnection(String subject){
        var rows=jdbc.queryForList("SELECT google_subject FROM platform_google_drive_connection WHERE id=1 FOR UPDATE",String.class);
        if(rows.size()!=1||!subject.equals(rows.getFirst()))throw conflict();
    }
    private void audit(GoogleDriveUploadLedger.Reservation r,String action){
        jdbc.update("INSERT INTO platform_google_drive_upload_audit(session_id,actor_user_id,action,occurred_at) VALUES (?,?,?,UTC_TIMESTAMP(6))",
            r.id(),r.actor(),action);
    }
    private static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"Substituição indisponível ou exige revisão");}
}
