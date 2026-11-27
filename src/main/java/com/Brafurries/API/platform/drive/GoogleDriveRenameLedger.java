package com.Brafurries.API.platform.drive;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GoogleDriveRenameLedger {
    private final JdbcTemplate jdbc;
    public GoogleDriveRenameLedger(JdbcTemplate jdbc){this.jdbc=jdbc;}
    record Operation(String id,int actor,GoogleDriveConnectionService.FolderScope scope,
        String anchor,String parent,String file,String version,String oldName,String newName,String status) {}

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public Operation find(String id) {
        var rows=jdbc.query("""
            SELECT id,actor_user_id,google_subject,root_folder_id,anchor_folder_id,parent_folder_id,
                file_id,expected_version,old_name,new_name,status
            FROM platform_google_drive_rename_operations WHERE id=?
            """,(rs,i)->new Operation(rs.getString(1),rs.getInt(2),
            new GoogleDriveConnectionService.FolderScope(rs.getString(3),rs.getString(4)),
            rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),rs.getString(11)),id);
        return rows.isEmpty()?null:rows.getFirst();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void reserve(Operation r) {
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Integer.class,r.actor());
        var connected=jdbc.queryForList("SELECT google_subject FROM platform_google_drive_connection WHERE id=1 FOR UPDATE",String.class);
        if(connected.size()!=1||!r.scope().subject().equals(connected.getFirst())||
            Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM platform_google_drive_replacement_commits
                    WHERE google_subject=? AND active_file_id=?)
                """,Boolean.class,r.scope().subject(),r.file())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Arquivo com operação pendente");
        var recent=jdbc.queryForList("""
            SELECT id FROM platform_google_drive_rename_operations
            WHERE actor_user_id=? AND created_at>=UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE LIMIT 20 FOR UPDATE
            """,String.class,r.actor());
        if(recent.size()>=20)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Muitas renomeações");
        jdbc.update("""
            INSERT INTO platform_google_drive_rename_operations
            (id,actor_user_id,google_subject,root_folder_id,anchor_folder_id,parent_folder_id,file_id,
             expected_version,old_name,new_name,status)
            VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING')
            """,r.id(),r.actor(),r.scope().subject(),r.scope().rootId(),r.anchor(),r.parent(),r.file(),r.version(),r.oldName(),r.newName());
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void complete(Operation r) {
        transition(r,"RENAMED");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void abort(Operation r) {
        transition(r,"ABORTED");
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void uncertain(Operation r) {
        transition(r,"RECONCILE_REQUIRED");
    }
    private void transition(Operation r,String status) {
        if(jdbc.update("""
            UPDATE platform_google_drive_rename_operations SET status=?,updated_at=UTC_TIMESTAMP(6)
            WHERE id=? AND actor_user_id=? AND status='PENDING'
            """,status,r.id(),r.actor())!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Renomeação exige revisão");
    }
}
