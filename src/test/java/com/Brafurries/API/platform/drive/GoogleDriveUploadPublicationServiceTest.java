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

class GoogleDriveUploadPublicationServiceTest {
    private final GoogleDriveUploadLedger ledger=mock(GoogleDriveUploadLedger.class);
    private final GoogleDriveFolderAccessService acl=mock(GoogleDriveFolderAccessService.class);
    private final GoogleDriveConnectionService connection=mock(GoogleDriveConnectionService.class);
    private final GoogleDriveBrowserService browser=mock(GoogleDriveBrowserService.class);
    private final GoogleDriveOAuthClient google=mock(GoogleDriveOAuthClient.class);
    private final PlatformAuthorizationService authorization=mock(PlatformAuthorizationService.class);
    private final Authentication auth=mock(Authentication.class);
    private final ObjectMapper json=new ObjectMapper();
    private final GoogleDriveConnectionService.FolderScope scope=new GoogleDriveConnectionService.FolderScope("subject","root_folder");
    private final GoogleDriveUploadLedger.Reservation r=new GoogleDriveUploadLedger.Reservation(
        "d15410a1-af3b-4d6f-9941-f8ade811b110",8,scope,"anchor_folder","child_folder",
        "staging_folder","file_123","arte.png","image/png",100);
    private final GoogleDriveUploadPublicationService service=new GoogleDriveUploadPublicationService(
        ledger,acl,connection,browser,google,authorization,"staging_folder");

    private ObjectNode folder(String id,String parent) {
        var n=json.createObjectNode().put("id",id).put("mimeType","application/vnd.google-apps.folder")
            .put("ownedByMe",true).put("trashed",false).put("shared",false);
        if(parent!=null)n.putArray("parents").add(parent);
        n.putObject("capabilities").put("canAddChildren",true);return n;
    }
    private ObjectNode file(String parent) {
        var n=json.createObjectNode().put("id",r.providerId()).put("name",r.name()).put("mimeType",r.mime())
            .put("ownedByMe",true).put("trashed",false).put("shared",false).put("size","100").put("version","1");
        n.putArray("parents").add(parent);n.putObject("appProperties").put("brafurriesUploadSession",r.id());return n;
    }
    private GoogleDriveFolderAccessService.BrowsePolicy policy(boolean upload) {
        return new GoogleDriveFolderAccessService.BrowsePolicy(scope,Set.of("anchor_folder"),
            Map.of("anchor_folder","Eventos"),Map.of("anchor_folder",upload?List.of(VIEW,UPLOAD):List.of(VIEW)));
    }
    private void setup() {
        var user=new User();user.setId(8);
        when(authorization.currentUser(auth)).thenReturn(user);
        when(ledger.owned(r.id(),8)).thenReturn(new GoogleDriveUploadLedger.Session(r,"OPEN",false,false));
        when(acl.browsePolicy(auth)).thenReturn(policy(true));
        when(connection.browserAccessToken(scope)).thenReturn("access");
        when(browser.verifyPath("access",policy(true),r.anchor(),r.destination())).thenReturn(List.of(r.destination(),r.anchor()));
        when(browser.verifyPath("access",policy(false),r.anchor(),r.destination())).thenReturn(List.of(r.destination(),r.anchor()));
        when(google.uploadFolderMetadata("access","root")).thenReturn(folder("my_drive",null));
        when(google.uploadFolderMetadata("access","root_folder")).thenReturn(folder("root_folder","my_drive"));
        when(google.uploadFolderMetadata("access",r.staging())).thenReturn(folder(r.staging(),"my_drive"));
        when(google.uploadFolderMetadata("access",r.destination())).thenReturn(folder(r.destination(),r.anchor()));
        var permissions=json.createObjectNode();permissions.putArray("permissions").addObject().put("id","owner").put("type","user").put("role","owner");
        when(google.uploadFolderPermissions("access",r.staging())).thenReturn(permissions);
        when(google.uploadedFile("access",r.providerId())).thenReturn(file(r.staging()),file(r.staging()),file(r.destination()));
        when(google.moveUploadedFile("access",r.providerId(),r.staging(),r.destination())).thenReturn(file(r.destination()));
        when(ledger.claimPublication(r)).thenReturn(true);
    }
    @Test void claimsBeforeMovingAndConfirmsBeforePublishing() {
        setup();
        assertEquals("PUBLISHED",service.publish(auth,r.id()).status());
        var order=inOrder(ledger,google);
        order.verify(ledger).claimPublication(r);
        order.verify(google).moveUploadedFile("access",r.providerId(),r.staging(),r.destination());
        order.verify(ledger).finishPublication(r);
    }
    @Test void invalidSizeNeverClaimsOrMoves() {
        setup();
        when(google.uploadedFile("access",r.providerId())).thenReturn(file(r.staging()).put("size","101"));
        assertThrows(ResponseStatusException.class,()->service.publish(auth,r.id()));
        verify(ledger,never()).claimPublication(r);
        verify(google,never()).moveUploadedFile(anyString(),anyString(),anyString(),anyString());
    }
    @Test void revokedGrantAfterClaimNeverMoves() {
        setup();when(acl.browsePolicy(auth)).thenReturn(policy(true),policy(false));
        assertThrows(ResponseStatusException.class,()->service.publish(auth,r.id()));
        verify(ledger).publicationFailed(r);
        verify(google,never()).moveUploadedFile(anyString(),anyString(),anyString(),anyString());
    }
    @Test void ambiguousMoveIsNotRetriedOrPublished() {
        setup();
        when(google.moveUploadedFile("access",r.providerId(),r.staging(),r.destination()))
            .thenThrow(new GoogleDriveOAuthClient.ProviderFailure(0));
        assertThrows(ResponseStatusException.class,()->service.publish(auth,r.id()));
        verify(ledger).publicationFailed(r);
        verify(ledger,never()).finishPublication(r);
        when(ledger.owned(r.id(),8)).thenReturn(new GoogleDriveUploadLedger.Session(r,"RECONCILE_REQUIRED",false,false));
        assertThrows(ResponseStatusException.class,()->service.publish(auth,r.id()));
        verify(google,times(1)).moveUploadedFile(anyString(),anyString(),anyString(),anyString());
    }
    @Test void publishedSessionRetryDoesNotMoveAgain() {
        setup();
        when(ledger.owned(r.id(),8)).thenReturn(new GoogleDriveUploadLedger.Session(r,"PUBLISHED",true,true));
        assertEquals("PUBLISHED",service.publish(auth,r.id()).status());
        verifyNoInteractions(google,connection,acl);
    }
    @Test void rejectsSharedDestinationBeforeClaim() {
        setup();
        when(google.uploadFolderMetadata("access",r.destination())).thenReturn(folder(r.destination(),r.anchor()).put("shared",true));
        assertThrows(ResponseStatusException.class,()->service.publish(auth,r.id()));
        verify(ledger,never()).claimPublication(r);
        verify(google,never()).moveUploadedFile(anyString(),anyString(),anyString(),anyString());
    }

    @Test void replacementSessionCannotBePublishedAsOrdinaryFile() {
        setup();
        when(ledger.isReplacement(r.id())).thenReturn(true);
        assertThrows(ResponseStatusException.class,()->service.publish(auth,r.id()));
        verifyNoInteractions(google,connection,acl);
        verify(ledger,never()).claimPublication(any());
    }
}
