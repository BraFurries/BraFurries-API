package com.Brafurries.API.platform.drive;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static com.Brafurries.API.platform.drive.GoogleDriveFolderAccessService.Permission.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleDriveTrashServiceTest {
    private final GoogleDriveTrashLedger ledger=mock(GoogleDriveTrashLedger.class);
    private final GoogleDriveFolderAccessService acl=mock(GoogleDriveFolderAccessService.class);
    private final GoogleDriveConnectionService connection=mock(GoogleDriveConnectionService.class);
    private final GoogleDriveBrowserService browser=mock(GoogleDriveBrowserService.class);
    private final GoogleDriveOAuthClient google=mock(GoogleDriveOAuthClient.class);
    private final PlatformAuthorizationService authorization=mock(PlatformAuthorizationService.class);
    private final Authentication auth=mock(Authentication.class);
    private final ObjectMapper json=new ObjectMapper();
    private final GoogleDriveConnectionService.FolderScope scope=new GoogleDriveConnectionService.FolderScope("subject","root_folder");
    private final String id="d15410a1-af3b-4d6f-9941-f8ade811b110";
    private final GoogleDriveTrashService service=new GoogleDriveTrashService(ledger,acl,connection,browser,google,authorization,true);
    private final GoogleDriveTrashLedger.Operation record=new GoogleDriveTrashLedger.Operation(id,scope,"anchor_folder","child_folder",
        "file_123","arte.png","image/png","12",8,"TRASHED",Instant.parse("2026-11-27T10:00:00Z"));
    private ObjectNode file(boolean trashed,String version){
        var n=json.createObjectNode().put("id","file_123").put("name","arte.png").put("mimeType","image/png")
            .put("ownedByMe",true).put("shared",false).put("trashed",trashed).put("explicitlyTrashed",trashed).put("version",version);
        n.putArray("parents").add("child_folder");n.putObject("capabilities").put("canTrash",!trashed).put("canUntrash",trashed);
        return n;
    }
    private void setup(boolean trash,boolean upload){
        var actor=new User();actor.setId(8);when(authorization.currentUser(auth)).thenReturn(actor);
        var rights=new java.util.ArrayList<GoogleDriveFolderAccessService.Permission>();rights.add(VIEW);
        if(trash)rights.add(TRASH);if(upload)rights.add(UPLOAD);
        var policy=new GoogleDriveFolderAccessService.BrowsePolicy(scope,Set.of("anchor_folder"),Map.of("anchor_folder","Eventos"),
            Map.of("anchor_folder",List.copyOf(rights)));
        when(acl.browsePolicy(auth)).thenReturn(policy);
        when(connection.browserAccessToken(scope)).thenReturn("access");
        when(browser.verifyPath("access",policy,"anchor_folder","child_folder")).thenReturn(List.of("child_folder","anchor_folder"));
        var folder=json.createObjectNode().put("id","child_folder").put("mimeType","application/vnd.google-apps.folder")
            .put("ownedByMe",true).put("trashed",false).put("shared",false);
        folder.putObject("capabilities").put("canAddChildren",true);
        when(google.uploadFolderMetadata("access","child_folder")).thenReturn(folder);
    }
    private GoogleDriveTrashService.Result trash(){return service.trash(auth,"anchor_folder","child_folder","file_123","12",id);}
    @Test void nativeTrashReservesBeforeMutationAndConfirms(){
        setup(true,true);
        when(google.trashMetadata("access","file_123")).thenReturn(file(false,"12"),file(false,"12"),file(true,"13"));
        when(google.setTrashed("access","file_123",true)).thenReturn(file(true,"13"));
        assertEquals("TRASHED",trash().status());
        var order=inOrder(ledger,google);order.verify(ledger).reserve(any());order.verify(google).setTrashed("access","file_123",true);order.verify(ledger).trashed(any());
    }
    @Test void viewAloneCannotTrash(){
        setup(false,true);assertThrows(ResponseStatusException.class,this::trash);
        verify(ledger,never()).reserve(any());verify(google,never()).setTrashed(anyString(),anyString(),anyBoolean());
    }
    @Test void restoreNeedsDestinationUploadAndExplicitTrash(){
        setup(true,false);when(ledger.find(id)).thenReturn(record);
        assertThrows(ResponseStatusException.class,()->service.restore(auth,"anchor_folder",id,"13"));
        verify(ledger,never()).claimRestore(any(),anyInt());
        setup(true,true);when(google.trashMetadata("access","file_123")).thenReturn(file(true,"13").put("explicitlyTrashed",false));
        assertThrows(ResponseStatusException.class,()->service.restore(auth,"anchor_folder",id,"13"));
        verify(google,never()).setTrashed(anyString(),anyString(),anyBoolean());
    }
    @Test void restoresOnlyOriginalParentAndSameFile(){
        setup(true,true);when(ledger.find(id)).thenReturn(record);
        when(connection.hiddenUploadResources(scope)).thenReturn(Set.of("file_123"));
        when(google.trashMetadata("access","file_123")).thenReturn(file(true,"13"),file(true,"13"),file(false,"14"));
        when(google.setTrashed("access","file_123",false)).thenReturn(file(false,"14"));
        assertEquals("RESTORED",service.restore(auth,"anchor_folder",id,"13").status());
        verify(ledger).claimRestore(record,8);verify(ledger).restored(record);
    }
    @Test void listChecksLivePermissionAndProviderState(){
        setup(true,true);when(ledger.page(scope,"anchor_folder",null)).thenReturn(List.of(record));
        when(ledger.find(id)).thenReturn(record);
        when(google.trashMetadata("access","file_123")).thenReturn(file(true,"13"));
        assertTrue(service.list(auth,"anchor_folder",null).items().getFirst().canRestore());
        setup(false,true);
        assertTrue(service.list(auth,"anchor_folder",null).items().isEmpty());
    }
    @Test void ambiguousTrashIsNotRepeated(){
        setup(true,true);
        when(google.trashMetadata("access","file_123")).thenReturn(file(false,"12"));
        when(google.setTrashed("access","file_123",true)).thenThrow(new GoogleDriveOAuthClient.ProviderFailure(0));
        assertThrows(ResponseStatusException.class,this::trash);verify(ledger).failed(any(),eq(false),eq(true));
        when(ledger.find(id)).thenReturn(new GoogleDriveTrashLedger.Operation(id,scope,"anchor_folder","child_folder",
            "file_123","arte.png","image/png","12",8,"RECONCILE_REQUIRED",null));
        assertThrows(ResponseStatusException.class,this::trash);verify(google,times(1)).setTrashed("access","file_123",true);
    }
}
