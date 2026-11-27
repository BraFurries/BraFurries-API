package com.Brafurries.API.platform.drive;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class GoogleDriveFolderCreationLedger {
    private final JdbcTemplate jdbc;
    public GoogleDriveFolderCreationLedger(JdbcTemplate jdbc){this.jdbc=jdbc;}

    record Operation(String id,int actor,GoogleDriveConnectionService.FolderScope scope,
                     String anchor,String parent,String providerId,String name,String status) {}

    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public Operation find(String id) {
        var rows=jdbc.query("""
            SELECT id,actor_user_id,google_subject,root_folder_id,anchor_folder_id,parent_folder_id,
                provider_folder_id,folder_name,status FROM platform_google_drive_folder_operations WHERE id=?
            """,(rs,i)->new Operation(rs.getString(1),rs.getInt(2),
            new GoogleDriveConnectionService.FolderScope(rs.getString(3),rs.getString(4)),
            rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9)),id);
        return rows.isEmpty()?null:rows.getFirst();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void reserve(Operation r) {
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Integer.class,r.actor());
        var recent=jdbc.queryForList("""
            SELECT id FROM platform_google_drive_folder_operations
            WHERE actor_user_id=? AND created_at>=UTC_TIMESTAMP(6)-INTERVAL 1 MINUTE
            LIMIT 10 FOR UPDATE
            """,String.class,r.actor());
        if(recent.size()>=10)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Muitas criações de pasta");
        // Unique operation key makes racing requests fail before any provider write.
        jdbc.update("""
            INSERT INTO platform_google_drive_folder_operations
                (id,actor_user_id,google_subject,root_folder_id,anchor_folder_id,parent_folder_id,
                 provider_folder_id,folder_name,status)
            VALUES (?,?,?,?,?,?,?,?,'PENDING')
            """,r.id(),r.actor(),r.scope().subject(),r.scope().rootId(),r.anchor(),r.parent(),r.providerId(),r.name());
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void complete(Operation r) {
        if(jdbc.update("""
            UPDATE platform_google_drive_folder_operations SET status='CREATED',updated_at=UTC_TIMESTAMP(6)
            WHERE id=? AND actor_user_id=? AND status='PENDING'
            """,r.id(),r.actor())!=1)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Criação exige reconciliação");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void confirmReconciled(Operation r) {
        int changed=jdbc.update("""
            UPDATE platform_google_drive_folder_operations SET status='CREATED',updated_at=UTC_TIMESTAMP(6)
            WHERE id=? AND actor_user_id=? AND status='RECONCILE_REQUIRED'
            """,r.id(),r.actor());
        if(changed!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Operação exige reconciliação");
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void uncertain(Operation r) {
        jdbc.update("""
            UPDATE platform_google_drive_folder_operations SET status='RECONCILE_REQUIRED',updated_at=UTC_TIMESTAMP(6)
            WHERE id=? AND actor_user_id=? AND status='PENDING'
            """,r.id(),r.actor());
    }
}
