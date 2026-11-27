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

class GoogleDriveFolderCreationServiceTest {
    private final GoogleDriveFolderCreationLedger ledger=mock(GoogleDriveFolderCreationLedger.class);
    private final GoogleDriveFolderAccessService acl=mock(GoogleDriveFolderAccessService.class);
    private final GoogleDriveConnectionService connection=mock(GoogleDriveConnectionService.class);
    private final GoogleDriveBrowserService browser=mock(GoogleDriveBrowserService.class);
    private final GoogleDriveOAuthClient google=mock(GoogleDriveOAuthClient.class);
    private final PlatformAuthorizationService authorization=mock(PlatformAuthorizationService.class);
    private final Authentication auth=mock(Authentication.class);
    private final ObjectMapper json=new ObjectMapper();
    private final GoogleDriveConnectionService.FolderScope scope=new GoogleDriveConnectionService.FolderScope("subject","root_folder");
    private final String operation="d15410a1-af3b-4d6f-9941-f8ade811b110";
    private final GoogleDriveFolderCreationService service=new GoogleDriveFolderCreationService(ledger,acl,connection,browser,google,authorization,true);
    private GoogleDriveFolderAccessService.BrowsePolicy policy(boolean create){
        return new GoogleDriveFolderAccessService.BrowsePolicy(scope,Set.of("anchor_folder"),Map.of("anchor_folder","Eventos"),
            Map.of("anchor_folder",create?List.of(VIEW,CREATE_FOLDER):List.of(VIEW)));
    }
    private ObjectNode folder(String id,String parent){
        var n=json.createObjectNode().put("id",id).put("name","Nova pasta").put("mimeType","application/vnd.google-apps.folder")
            .put("ownedByMe",true).put("trashed",false).put("shared",false);
        n.putArray("parents").add(parent);
        n.putObject("capabilities").put("canAddChildren",true);
        n.putObject("appProperties").put("brafurriesFolderOperation",operation);
        return n;
    }
    private void setup(boolean create){
        var user=new User();user.setId(8);
        when(authorization.currentUser(auth)).thenReturn(user);
        when(acl.browsePolicy(auth)).thenReturn(policy(create));
        when(connection.browserAccessToken(scope)).thenReturn("access");
        when(browser.verifyPath("access",policy(create),"anchor_folder","child_folder")).thenReturn(List.of("child_folder","anchor_folder"));
        when(google.uploadFolderMetadata("access","child_folder")).thenReturn(folder("child_folder","anchor_folder"));
        var ids=json.createObjectNode();ids.putArray("ids").add("new_folder");
        when(google.generateUploadId("access")).thenReturn(ids);
        when(google.createFolder("access","new_folder","child_folder","Nova pasta",operation)).thenReturn(folder("new_folder","child_folder"));
        when(google.uploadedFile("access","new_folder")).thenReturn(folder("new_folder","child_folder"));
    }
    private GoogleDriveFolderCreationService.Result create(){return service.create(auth,"anchor_folder","child_folder","Nova pasta",operation);}
    @Test void viewAloneCannotCreate(){
        setup(false);assertThrows(ResponseStatusException.class,this::create);
        verify(google,never()).createFolder(anyString(),anyString(),anyString(),anyString(),anyString());
        verifyNoInteractions(ledger);
    }
    @Test void reservesBeforeMutationAndCompletesAfterVerification(){
        setup(true);assertEquals("new_folder",create().id());
        var order=inOrder(ledger,google);
        order.verify(ledger).reserve(argThat(r->r.actor()==8&&r.id().equals(operation)));
        order.verify(google).createFolder("access","new_folder","child_folder","Nova pasta",operation);
        order.verify(google).uploadedFile("access","new_folder");
        order.verify(ledger).complete(any());
    }
    @Test void ambiguousMutationNeverRetries(){
        setup(true);
        when(google.createFolder("access","new_folder","child_folder","Nova pasta",operation))
            .thenThrow(new GoogleDriveOAuthClient.ProviderFailure(0));
        assertThrows(ResponseStatusException.class,this::create);verify(ledger).uncertain(any());
        when(ledger.find(operation)).thenReturn(new GoogleDriveFolderCreationLedger.Operation(operation,8,scope,
            "anchor_folder","child_folder","new_folder","Nova pasta","RECONCILE_REQUIRED"));
        assertThrows(ResponseStatusException.class,this::create);
        verify(google,times(1)).createFolder(anyString(),anyString(),anyString(),anyString(),anyString());
    }
    @Test void rejectsSharedParent(){
        setup(true);when(google.uploadFolderMetadata("access","child_folder")).thenReturn(folder("child_folder","anchor_folder").put("shared",true));
        assertThrows(ResponseStatusException.class,this::create);
        verifyNoInteractions(ledger);
    }
}
