package com.Brafurries.API.platform.drive;

import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Local policy only. No Google sharing or file operations. */
@Service
public class GoogleDriveFolderAccessService {
    public enum Permission {
        VIEW, DOWNLOAD, UPLOAD, CREATE_FOLDER, RENAME, REPLACE, MOVE, TRASH,
        EXTERNAL_VIEW, EXTERNAL_EDIT, EXTERNAL_FOLDER
    }
    public enum Principal { PERSON, ROLE }
    public record Grant(Principal principalType, int principalId, Permission permission) {}
    private final PlatformAuthorizationService authorization;
    private final GoogleDriveConnectionService connection;
    private final JdbcTemplate jdbc;

    public GoogleDriveFolderAccessService(PlatformAuthorizationService authorization,
            GoogleDriveConnectionService connection, JdbcTemplate jdbc) {
        this.authorization=authorization;
        this.connection=connection;
        this.jdbc=jdbc;
    }

    private static final String FOLDER_KEY="google_subject=? AND root_folder_id=? AND folder_id=?";
    private static String table(Principal principal) {
        return principal==Principal.PERSON ? "platform_google_drive_person_grants" : "platform_google_drive_role_grants";
    }
    private static String column(Principal principal) {
        return principal==Principal.PERSON ? "user_id" : "role_id";
    }
    private boolean registered(GoogleDriveConnectionService.FolderScope scope,String folder,boolean lock) {
        if (!GoogleDriveConnectionService.validFolderId(folder) || folder.equals(scope.rootId())) return false;
        return !jdbc.queryForList("SELECT folder_id FROM platform_google_drive_managed_folders WHERE "+
            FOLDER_KEY+(lock?" FOR UPDATE":""),String.class,scope.subject(),scope.rootId(),folder).isEmpty();
    }
    private void requireFolder(GoogleDriveConnectionService.FolderScope scope,String folder,boolean lock) {
        if (!registered(scope,folder,lock))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Pasta gerenciada não encontrada");
    }

    @Transactional(readOnly=true)
    public List<Grant> grants(Authentication auth,String folder) {
        authorization.requireOwner(auth);
        var scope=connection.folderScope();
        requireFolder(scope,folder,false);
        var result=new java.util.ArrayList<Grant>();
        for (Principal principal:Principal.values()) {
            result.addAll(jdbc.query("SELECT "+column(principal)+",permission_code FROM "+table(principal)+
                " WHERE "+FOLDER_KEY+" ORDER BY "+column(principal)+",permission_code",
                (rs,i)->new Grant(principal,rs.getInt(1),Permission.valueOf(rs.getString(2))),
                scope.subject(),scope.rootId(),folder));
        }
        return List.copyOf(result);
    }

    // Owner approval is a personal ceiling, not an alternative to a Team role.
    // Both grants must be for the very same registered folder and permission.
    // This method is a policy preview, not proof of live Google resource access.
    @Transactional(readOnly=true)
    public List<Permission> effective(Authentication auth,String folder) {
        var actor=authorization.resolve(auth);
        if (!actor.access().enabled()) return List.of();
        var scope=connection.folderScope();
        if (!registered(scope,folder,false)) return List.of();
        if (actor.access().owner()) return List.of(Permission.values());
        return jdbc.queryForList("""
            SELECT DISTINCT p.permission_code
            FROM platform_google_drive_person_grants p
            JOIN platform_authorized_users approved ON approved.user_id=p.user_id
            JOIN platform_google_drive_role_grants g ON g.google_subject=p.google_subject
                AND g.root_folder_id=p.root_folder_id AND g.folder_id=p.folder_id
                AND g.permission_code=p.permission_code
            JOIN community_team_roles role ON role.id=g.role_id AND role.community_id=? AND role.active=1
            JOIN community_team_role_assignments a ON a.role_id=role.id
                AND a.community_id=role.community_id AND a.user_id=p.user_id
            JOIN user_community_status m ON m.community_id=role.community_id AND m.user_id=p.user_id
            WHERE p.google_subject=? AND p.root_folder_id=? AND p.folder_id=? AND p.user_id=?
                AND m.is_present=1 AND COALESCE(m.banned,0)=0
                AND (m.approval_required=0 OR m.approved=1)
            ORDER BY p.permission_code
            """,String.class,authorization.officialCommunityId(),scope.subject(),scope.rootId(),folder,actor.user().getId())
            .stream().map(Permission::valueOf).toList();
    }

    @Transactional
    public void change(Authentication auth,String folder,Principal principal,int id,Permission permission,boolean grant) {
        int owner=authorization.requireOwner(auth).getId();
        if (principal==null || permission==null || id<=0 || (principal==Principal.PERSON && id==owner))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Concessão inválida");
        // Serialize against connection changes and other owner writes.
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",Integer.class,owner);
        var scope=connection.folderScope();
        requireFolder(scope,folder,true);
        if (principal==Principal.ROLE) {
            if (jdbc.queryForList("SELECT id FROM community_team_roles WHERE id=? AND community_id=?"+
                (grant?" AND active=1":"")+" FOR UPDATE",Integer.class,id,authorization.officialCommunityId()).isEmpty())
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Cargo da BraFurries não encontrado ou inativo");
        } else if (grant && jdbc.queryForList("""
            SELECT approved.user_id FROM platform_authorized_users approved
            JOIN user_community_status m ON m.user_id=approved.user_id AND m.community_id=?
            WHERE approved.user_id=? AND m.is_present=1 AND COALESCE(m.banned,0)=0
                AND (m.approval_required=0 OR m.approved=1) FOR UPDATE
            """,Integer.class,authorization.officialCommunityId(),id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"Pessoa não aprovada ou sem vínculo ativo com a BraFurries");
        }
        String predicate=FOLDER_KEY+" AND "+column(principal)+"=? AND permission_code=?";
        boolean exists=!jdbc.queryForList("SELECT permission_code FROM "+table(principal)+" WHERE "+predicate,
            String.class,scope.subject(),scope.rootId(),folder,id,permission.name()).isEmpty();
        if (grant==exists) return;
        if (grant) {
            jdbc.update("INSERT INTO "+table(principal)+" (google_subject,root_folder_id,folder_id,"+
                column(principal)+",permission_code,granted_by_user_id,granted_at) VALUES (?,?,?,?,?,?,UTC_TIMESTAMP(6))",
                scope.subject(),scope.rootId(),folder,id,permission.name(),owner);
        } else {
            jdbc.update("DELETE FROM "+table(principal)+" WHERE "+predicate,
                scope.subject(),scope.rootId(),folder,id,permission.name());
        }
        jdbc.update("""
            INSERT INTO platform_google_drive_folder_access_audit
                (google_subject,root_folder_id,folder_id,actor_user_id,principal_type,principal_id,permission_code,action,occurred_at)
            VALUES (?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
            """,scope.subject(),scope.rootId(),folder,owner,principal.name(),id,permission.name(),grant?"GRANT":"REVOKE");
    }
}
