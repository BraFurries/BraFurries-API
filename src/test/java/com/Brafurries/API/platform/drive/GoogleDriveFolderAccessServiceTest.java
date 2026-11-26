package com.Brafurries.API.platform.drive;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static com.Brafurries.API.platform.drive.GoogleDriveFolderAccessService.Permission.*;
import static com.Brafurries.API.platform.drive.GoogleDriveFolderAccessService.Principal.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleDriveFolderAccessServiceTest {
    private final PlatformAuthorizationService authorization=mock(PlatformAuthorizationService.class);
    private final GoogleDriveConnectionService connection=mock(GoogleDriveConnectionService.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final Authentication auth=mock(Authentication.class);
    private final GoogleDriveFolderAccessService service=new GoogleDriveFolderAccessService(authorization,connection,jdbc);
    private User user(int id) { User u=new User();u.setId(id);return u; }
    private void scope() {
        when(connection.folderScope()).thenReturn(new GoogleDriveConnectionService.FolderScope("subject","root_folder"));
        when(jdbc.queryForList(contains("SELECT folder_id FROM platform_google_drive_managed_folders"),
            eq(String.class),eq("subject"),eq("root_folder"),eq("child_folder"))).thenReturn(List.of("child_folder"));
        when(authorization.officialCommunityId()).thenReturn(42);
    }
    @Test void nonOwnerCannotListOrMutateGrants() {
        when(authorization.requireOwner(auth)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class,()->service.grants(auth,"child_folder"));
        assertThrows(ResponseStatusException.class,()->service.change(auth,"child_folder",ROLE,5,VIEW,true));
        verifyNoInteractions(connection,jdbc);
    }
    @Test void disabledPlatformHasNoAccess() {
        when(authorization.resolve(auth)).thenReturn(new PlatformAuthorizationService.Actor(user(8),
            new PlatformAuthorizationService.Access(false,false,Set.of())));
        assertEquals(List.of(),service.effective(auth,"child_folder"));
        verifyNoInteractions(connection,jdbc);
    }
    @Test void effectiveAccessUsesSameFolderAndOfficialCommunityIntersection() {
        scope();
        when(authorization.resolve(auth)).thenReturn(new PlatformAuthorizationService.Actor(user(8),
            new PlatformAuthorizationService.Access(false,true,Set.of())));
        when(jdbc.queryForList(argThat(sql->sql.contains("g.folder_id=p.folder_id") &&
            sql.contains("g.permission_code=p.permission_code") && sql.contains("role.community_id=?") &&
            sql.contains("role.active=1") && sql.contains("a.user_id=p.user_id") &&
            sql.contains("m.is_present=1") && sql.contains("m.approved=1")),
            eq(String.class),eq(42),eq("subject"),eq("root_folder"),eq("child_folder"),eq(8)))
            .thenReturn(List.of("VIEW"));
        assertEquals(List.of(VIEW),service.effective(auth,"child_folder"));
        assertEquals(List.of(),service.effective(auth,"root_folder"));
        assertEquals(List.of(),service.effective(auth,"unknown_folder"));
    }
    @Test void ownerCannotGrantRoleFromAnotherCommunity() {
        scope();
        when(authorization.requireOwner(auth)).thenReturn(user(7));
        when(jdbc.queryForList(contains("SELECT id FROM community_team_roles"),eq(Integer.class),eq(5),eq(42)))
            .thenReturn(List.of());
        assertEquals(HttpStatus.NOT_FOUND,assertThrows(ResponseStatusException.class,
            ()->service.change(auth,"child_folder",ROLE,5,VIEW,true)).getStatusCode());
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void externalGrantIsOnlyLocalAndRepeatedGrantIsNoOp() {
        scope();
        when(authorization.requireOwner(auth)).thenReturn(user(7));
        when(jdbc.queryForList(contains("SELECT id FROM community_team_roles"),eq(Integer.class),eq(5),eq(42)))
            .thenReturn(List.of(5));
        when(jdbc.queryForList(contains("SELECT permission_code FROM platform_google_drive_role_grants"),
            eq(String.class),eq("subject"),eq("root_folder"),eq("child_folder"),eq(5),eq("EXTERNAL_EDIT")))
            .thenReturn(List.of(),List.of("EXTERNAL_EDIT"));
        service.change(auth,"child_folder",ROLE,5,EXTERNAL_EDIT,true);
        service.change(auth,"child_folder",ROLE,5,EXTERNAL_EDIT,true);
        verify(jdbc,times(1)).update(contains("INSERT INTO platform_google_drive_role_grants"),
            eq("subject"),eq("root_folder"),eq("child_folder"),eq(5),eq("EXTERNAL_EDIT"),eq(7));
        verify(jdbc,times(1)).update(contains("INSERT INTO platform_google_drive_folder_access_audit"),
            eq("subject"),eq("root_folder"),eq("child_folder"),eq(7),eq("ROLE"),eq(5),eq("EXTERNAL_EDIT"),eq("GRANT"));
        verify(connection,times(2)).folderScope();
        verifyNoMoreInteractions(connection);
    }
}
