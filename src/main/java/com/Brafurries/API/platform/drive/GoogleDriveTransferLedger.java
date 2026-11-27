package com.Brafurries.API.platform.drive;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GoogleDriveTransferLedger {
    private final JdbcTemplate jdbc;
    public GoogleDriveTransferLedger(JdbcTemplate jdbc){this.jdbc=jdbc;}
    enum Action {MOVE,COPY}
    record Operation(String id,int actor,GoogleDriveConnectionService.FolderScope scope,String sourceAnchor,
        String sourceParent,String file,String destinationAnchor,String destination,String result,String version,Action action,String status){}

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public Operation find(String id){
        var rows=jdbc.query("""
            SELECT id,actor_user_id,google_subject,root_folder_id,source_anchor_id,source_parent_id,source_file_id,
                destination_anchor_id,destination_folder_id,result_file_id,expected_version,operation,status
            FROM platform_google_drive_transfer_operations WHERE id=?
            """,(rs,i)->new Operation(rs.getString(1),rs.getInt(2),new GoogleDriveConnectionService.FolderScope(rs.getString(3),rs.getString(4)),
            rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getString(10),
            rs.getString(11),Action.valueOf(rs.getString(12)),rs.getString(13)),id);
        return rows.isEmpty()?null:rows.getFirst();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void reserve(Operation r){
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Integer.class,r.actor());
        var connected=jdbc.queryForList("SELECT google_subject FROM platform_google_drive_connection WHERE id=1 FOR UPDATE",String.class);
        if(connected.size()!=1||!r.scope().subject().equals(connected.getFirst()))throw conflict();
        if(Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM platform_google_drive_rename_operations WHERE google_subject=? AND active_file_id=?)
                OR EXISTS(SELECT 1 FROM platform_google_drive_replacement_commits WHERE google_subject=? AND active_file_id=?)
                OR EXISTS(SELECT 1 FROM platform_google_drive_trash_operations WHERE google_subject=? AND active_file_id=?)
            """,Boolean.class,r.scope().subject(),r.file(),r.scope().subject(),r.file(),r.scope().subject(),r.file())))throw conflict();
        var recent=jdbc.queryForList("""
            SELECT id FROM platform_google_drive_transfer_operations WHERE actor_user_id=?
            AND created_at>=UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE LIMIT 20 FOR UPDATE
            """,String.class,r.actor());
        if(recent.size()>=20)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Muitas operações de arquivos");
        jdbc.update("""
            INSERT INTO platform_google_drive_transfer_operations
            (id,actor_user_id,google_subject,root_folder_id,source_anchor_id,source_parent_id,source_file_id,
             destination_anchor_id,destination_folder_id,result_file_id,expected_version,operation,status)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'PENDING')
            """,r.id(),r.actor(),r.scope().subject(),r.scope().rootId(),r.sourceAnchor(),r.sourceParent(),r.file(),
            r.destinationAnchor(),r.destination(),r.result(),r.version(),r.action().name());
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void transition(Operation r,String status){
        if(!java.util.Set.of("COMPLETED","ABORTED","RECONCILE_REQUIRED").contains(status))throw new IllegalArgumentException();
        if(jdbc.update("""
            UPDATE platform_google_drive_transfer_operations SET status=?,updated_at=UTC_TIMESTAMP(6)
            WHERE id=? AND actor_user_id=? AND status='PENDING'
            """,status,r.id(),r.actor())!=1)throw conflict();
    }
    static ResponseStatusException conflict(){return new ResponseStatusException(HttpStatus.CONFLICT,"Arquivo alterado ou operação pendente de revisão");}
}
