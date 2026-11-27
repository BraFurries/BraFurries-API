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
import com.Brafurries.API.platform.drive.GoogleDriveTransferLedger.Action;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleDriveTransferServiceTest {
    private final GoogleDriveTransferLedger ledger=mock(GoogleDriveTransferLedger.class);
    private final GoogleDriveFolderAccessService acl=mock(GoogleDriveFolderAccessService.class);
    private final GoogleDriveConnectionService connection=mock(GoogleDriveConnectionService.class);
    private final GoogleDriveBrowserService browser=mock(GoogleDriveBrowserService.class);
    private final GoogleDriveOAuthClient google=mock(GoogleDriveOAuthClient.class);
    private final PlatformAuthorizationService authorization=mock(PlatformAuthorizationService.class);
    private final Authentication auth=mock(Authentication.class);
    private final ObjectMapper json=new ObjectMapper();
    private final GoogleDriveConnectionService.FolderScope scope=new GoogleDriveConnectionService.FolderScope("subject","root_folder");
    private final String id="d15410a1-af3b-4d6f-9941-f8ade811b110";
    private final GoogleDriveTransferService service=new GoogleDriveTransferService(ledger,acl,connection,browser,google,authorization,true);
    private ObjectNode file(String id,String parent,String version){
        var n=json.createObjectNode().put("id",id).put("name","arte.png").put("mimeType","image/png")
            .put("ownedByMe",true).put("trashed",false).put("shared",false).put("version",version)
            .put("sha256Checksum","a".repeat(64)).put("size","3");
        n.putArray("parents").add(parent);n.putObject("capabilities").put("canCopy",true).put("canMoveItemWithinDrive",true);
        n.putObject("appProperties").put("brafurriesTransferOperation",this.id);
        return n;
    }
    private void setup(boolean destinationUpload,boolean download){
        var actor=new User();actor.setId(8);when(authorization.currentUser(auth)).thenReturn(actor);
        var policy=new GoogleDriveFolderAccessService.BrowsePolicy(scope,Set.of("source_anchor","target_anchor"),
            Map.of("source_anchor","Origem","target_anchor","Destino"),
            Map.of("source_anchor",download?List.of(VIEW,MOVE,DOWNLOAD):List.of(VIEW,MOVE),
                "target_anchor",destinationUpload?List.of(VIEW,UPLOAD):List.of(VIEW)));
        when(acl.browsePolicy(auth)).thenReturn(policy);
        when(connection.browserAccessToken(scope)).thenReturn("access");
        when(browser.verifyPath("access",policy,"source_anchor","source_parent")).thenReturn(List.of("source_parent","source_anchor"));
        when(browser.verifyPath("access",policy,"target_anchor","target_folder")).thenReturn(List.of("target_folder","target_anchor"));
        var folder=json.createObjectNode().put("id","target_folder").put("mimeType","application/vnd.google-apps.folder")
            .put("ownedByMe",true).put("trashed",false).put("shared",false);
        folder.putObject("capabilities").put("canAddChildren",true);
        when(google.uploadFolderMetadata("access","target_folder")).thenReturn(folder);
        when(google.transferMetadata("access","file_123")).thenReturn(file("file_123","source_parent","12"));
        var ids=json.createObjectNode();ids.putArray("ids").add("copy_file");
        when(google.generateUploadId("access")).thenReturn(ids);
        when(google.copyFile("access","file_123","copy_file","target_folder","arte.png",id)).thenReturn(file("copy_file","target_folder","1"));
        when(google.transferMetadata("access","copy_file")).thenReturn(file("copy_file","target_folder","1"));
        when(google.uploadedFile("access","copy_file")).thenReturn(file("copy_file","target_folder","1"));
    }
    private GoogleDriveTransferService.Result run(Action action){
        return service.transfer(auth,"source_anchor","source_parent","file_123","12","target_anchor","target_folder",action,id);
    }
    @Test void copyUsesNewIdAndPreservesSource(){
        setup(true,true);assertEquals("copy_file",run(Action.COPY).id());
        var order=inOrder(ledger,google);
        order.verify(ledger).reserve(any());
        order.verify(google).copyFile("access","file_123","copy_file","target_folder","arte.png",id);
        order.verify(ledger).transition(any(),eq("COMPLETED"));
        verify(google,never()).moveUploadedFile(anyString(),anyString(),anyString(),anyString());
    }
    @Test void moveRetainsIdAndChecksDestination(){
        setup(true,true);
        when(google.transferMetadata("access","file_123")).thenReturn(file("file_123","source_parent","12"),
            file("file_123","source_parent","12"),file("file_123","target_folder","13"));
        when(google.moveUploadedFile("access","file_123","source_parent","target_folder")).thenReturn(file("file_123","target_folder","13"));
        assertEquals("file_123",run(Action.MOVE).id());verify(ledger).transition(any(),eq("COMPLETED"));
    }
    @Test void destinationWithoutUploadCannotReceiveFile(){
        setup(false,true);assertThrows(ResponseStatusException.class,()->run(Action.MOVE));
        verify(ledger,never()).reserve(any());verify(google,never()).moveUploadedFile(anyString(),anyString(),anyString(),anyString());
    }
    @Test void copyWithoutDownloadIsRejected(){
        setup(true,false);assertThrows(ResponseStatusException.class,()->run(Action.COPY));
        verify(ledger,never()).reserve(any());verify(google,never()).copyFile(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
    }
    @Test void uncertainCopyNeverRepeats(){
        setup(true,true);when(google.copyFile("access","file_123","copy_file","target_folder","arte.png",id))
            .thenThrow(new GoogleDriveOAuthClient.ProviderFailure(0));
        assertThrows(ResponseStatusException.class,()->run(Action.COPY));verify(ledger).transition(any(),eq("RECONCILE_REQUIRED"));
        when(ledger.find(id)).thenReturn(new GoogleDriveTransferLedger.Operation(id,8,scope,"source_anchor","source_parent","file_123",
            "target_anchor","target_folder","copy_file","12",Action.COPY,"RECONCILE_REQUIRED"));
        assertThrows(ResponseStatusException.class,()->run(Action.COPY));
        verify(google,times(1)).copyFile(anyString(),anyString(),anyString(),anyString(),anyString(),anyString());
    }
}
