package com.Brafurries.API.platform.drive;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GoogleDriveTrashLedger {
    private final JdbcTemplate jdbc;
    public GoogleDriveTrashLedger(JdbcTemplate jdbc){this.jdbc=jdbc;}
    record Operation(String id,GoogleDriveConnectionService.FolderScope scope,String anchor,String parent,
        String file,String name,String mime,String version,int actor,String status,Instant trashedAt){}
    private static final String SELECT="""
        SELECT id,google_subject,root_folder_id,anchor_folder_id,parent_folder_id,file_id,file_name,mime_type,
            expected_version,trashed_by_user_id,status,trashed_at FROM platform_google_drive_trash_operations
        """;
    private static Operation map(java.sql.ResultSet rs,int row)throws java.sql.SQLException{
        var time=rs.getObject(12,LocalDateTime.class);
        return new Operation(rs.getString(1),new GoogleDriveConnectionService.FolderScope(rs.getString(2),rs.getString(3)),
            rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),
            rs.getInt(10),rs.getString(11),time==null?null:time.toInstant(ZoneOffset.UTC));
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public Operation find(String id){
        var rows=jdbc.query(SELECT+" WHERE id=?",GoogleDriveTrashLedger::map,id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public List<Operation> page(GoogleDriveConnectionService.FolderScope scope,String anchor,String after){
        return jdbc.query(SELECT+"""
            WHERE google_subject=? AND root_folder_id=? AND anchor_folder_id=? AND status='TRASHED' AND id>?
            ORDER BY id LIMIT 11
            """,GoogleDriveTrashLedger::map,scope.subject(),scope.rootId(),anchor,after==null?"":after);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void reserve(Operation r){
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Integer.class,r.actor());
        lock(r.scope());
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM platform_google_drive_rename_operations WHERE google_subject=? AND active_file_id=?)
                OR EXISTS(SELECT 1 FROM platform_google_drive_replacement_commits WHERE google_subject=? AND active_file_id=?)
                OR EXISTS(SELECT 1 FROM platform_google_drive_transfer_operations WHERE google_subject=? AND active_file_id=?)
            """,Boolean.class,r.scope().subject(),r.file(),r.scope().subject(),r.file(),r.scope().subject(),r.file())))throw conflict();
        var recent=jdbc.queryForList("""
            SELECT id FROM platform_google_drive_trash_operations WHERE trashed_by_user_id=?
            AND created_at>=UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE LIMIT 20 FOR UPDATE
            """,String.class,r.actor());
        if(recent.size()>=20)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Muitas exclusões");
        jdbc.update("""
            INSERT INTO platform_google_drive_trash_operations
            (id,google_subject,root_folder_id,anchor_folder_id,parent_folder_id,file_id,file_name,mime_type,
             expected_version,trashed_by_user_id,status,created_at,updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,'TRASHING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
            """,r.id(),r.scope().subject(),r.scope().rootId(),r.anchor(),r.parent(),r.file(),r.name(),r.mime(),r.version(),r.actor());
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void claimRestore(Operation r,int actor){
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Integer.class,actor);
        lock(r.scope());
        if(jdbc.update("""
            UPDATE platform_google_drive_trash_operations SET status='RESTORING',restored_by_user_id=?,updated_at=UTC_TIMESTAMP(6)
            WHERE id=? AND status='TRASHED'
            """,actor,r.id())!=1)throw conflict();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void trashed(Operation r){
        if(jdbc.update("""
            UPDATE platform_google_drive_trash_operations SET status='TRASHED',trashed_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6)
            WHERE id=? AND status='TRASHING'
            """,r.id())!=1)throw conflict();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void restored(Operation r){
        if(jdbc.update("""
            UPDATE platform_google_drive_trash_operations SET status='RESTORED',restored_at=UTC_TIMESTAMP(6),updated_at=UTC_TIMESTAMP(6)
            WHERE id=? AND status='RESTORING'
            """,r.id())!=1)throw conflict();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void failed(Operation r,boolean restoring,boolean attempted){
        String status=attempted?"RECONCILE_REQUIRED":restoring?"TRASHED":"ABORTED";
        if(jdbc.update("""
            UPDATE platform_google_drive_trash_operations SET status=?,updated_at=UTC_TIMESTAMP(6)
            WHERE id=? AND status=?
            """,status,r.id(),restoring?"RESTORING":"TRASHING")!=1)throw conflict();
    }
    private void lock(GoogleDriveConnectionService.FolderScope scope){
        var rows=jdbc.queryForList("SELECT google_subject FROM platform_google_drive_connection WHERE id=1 FOR UPDATE",String.class);
        if(rows.size()!=1||!scope.subject().equals(rows.getFirst()))throw conflict();
    }
    static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"Arquivo alterado ou operação exige revisão");}
}
