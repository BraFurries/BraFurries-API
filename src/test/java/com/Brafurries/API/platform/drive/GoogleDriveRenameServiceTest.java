package com.Brafurries.API.platform.drive;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static com.Brafurries.API.platform.drive.GoogleDriveFolderAccessService.Permission.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleDriveRenameServiceTest {
    private final GoogleDriveRenameLedger ledger=mock(GoogleDriveRenameLedger.class);
    private final GoogleDriveFolderAccessService acl=mock(GoogleDriveFolderAccessService.class);
    private final GoogleDriveConnectionService connection=mock(GoogleDriveConnectionService.class);
    private final GoogleDriveBrowserService browser=mock(GoogleDriveBrowserService.class);
    private final GoogleDriveOAuthClient google=mock(GoogleDriveOAuthClient.class);
    private final PlatformAuthorizationService authorization=mock(PlatformAuthorizationService.class);
    private final Authentication auth=mock(Authentication.class);
    private final GoogleDriveConnectionService.FolderScope scope=new GoogleDriveConnectionService.FolderScope("subject","root_folder");
    private final String operation="d15410a1-af3b-4d6f-9941-f8ade811b110";
    private final GoogleDriveRenameService service=new GoogleDriveRenameService(ledger,acl,connection,browser,google,authorization,true);

    private GoogleDriveFolderAccessService.BrowsePolicy policy(boolean rename){
        return new GoogleDriveFolderAccessService.BrowsePolicy(scope,Set.of("anchor_folder"),Map.of("anchor_folder","Eventos"),
            Map.of("anchor_folder",rename?List.of(VIEW,RENAME):List.of(VIEW)));
    }
    private ObjectNode file(String name,String version){
        var n=new ObjectMapper().createObjectNode().put("id","file_123").put("name",name).put("version",version)
            .put("mimeType","image/png").put("ownedByMe",true).put("trashed",false).put("shared",false);
        n.putArray("parents").add("child_folder");n.putObject("capabilities").put("canRename",true);return n;
    }
    private void setup(boolean rename){
        var user=new User();user.setId(8);when(authorization.currentUser(auth)).thenReturn(user);
        when(acl.browsePolicy(auth)).thenReturn(policy(rename));
        when(connection.browserAccessToken(scope)).thenReturn("access");
        when(browser.verifyPath("access",policy(rename),"anchor_folder","child_folder")).thenReturn(List.of("child_folder","anchor_folder"));
        when(google.renameMetadata("access","file_123")).thenReturn(file("old.png","1"),file("old.png","1"),file("new.png","2"));
        when(google.rename("access","file_123","new.png")).thenReturn(file("new.png","2"));
    }
    private GoogleDriveRenameService.Result rename(){return service.rename(auth,"anchor_folder","child_folder","file_123","1","new.png",operation);}
    @Test void onlyRenamesAfterDurableReservationAndConfirms(){
        setup(true);assertEquals("new.png",rename().name());
        var order=inOrder(ledger,google);
        order.verify(ledger).reserve(any());order.verify(google).rename("access","file_123","new.png");order.verify(ledger).complete(any());
    }
    @Test void viewAloneCannotRename(){
        setup(false);assertThrows(ResponseStatusException.class,this::rename);
        verifyNoInteractions(ledger);verify(google,never()).rename(anyString(),anyString(),anyString());
    }
    @Test void staleVersionDoesNotWrite(){
        setup(true);when(google.renameMetadata("access","file_123")).thenReturn(file("old.png","3"));
        assertEquals(409,assertThrows(ResponseStatusException.class,this::rename).getStatusCode().value());
        verify(ledger,never()).reserve(any());verify(google,never()).rename(anyString(),anyString(),anyString());
    }
    @Test void changeAfterReservationAbortsWithoutProviderMutation(){
        setup(true);when(google.renameMetadata("access","file_123")).thenReturn(file("old.png","1"),file("old.png","2"));
        assertThrows(ResponseStatusException.class,this::rename);
        verify(ledger).abort(any());verify(google,never()).rename(anyString(),anyString(),anyString());
    }
    @Test void uncertainWriteRemainsRecordedAndIsNotReplayed(){
        setup(true);when(google.rename("access","file_123","new.png")).thenThrow(new GoogleDriveOAuthClient.ProviderFailure(0));
        assertThrows(ResponseStatusException.class,this::rename);verify(ledger).uncertain(any());
        when(ledger.find(operation)).thenReturn(new GoogleDriveRenameLedger.Operation(operation,8,scope,
            "anchor_folder","child_folder","file_123","1","old.png","new.png","RECONCILE_REQUIRED"));
        assertThrows(ResponseStatusException.class,this::rename);verify(google,times(1)).rename(anyString(),anyString(),anyString());
    }
    @Test void sharedOrWrongParentRejected(){
        setup(true);when(google.renameMetadata("access","file_123")).thenReturn(file("old.png","1").put("shared",true));
        assertThrows(ResponseStatusException.class,this::rename);verify(ledger,never()).reserve(any());
    }
}
