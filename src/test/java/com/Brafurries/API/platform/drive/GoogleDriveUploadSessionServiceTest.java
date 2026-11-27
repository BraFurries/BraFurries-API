package com.Brafurries.API.platform.drive;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static com.Brafurries.API.platform.drive.GoogleDriveFolderAccessService.Permission.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleDriveUploadSessionServiceTest {
    private final GoogleDriveFolderAccessService acl=mock(GoogleDriveFolderAccessService.class);
    private final GoogleDriveConnectionService connection=mock(GoogleDriveConnectionService.class);
    private final GoogleDriveBrowserService browser=mock(GoogleDriveBrowserService.class);
    private final GoogleDriveOAuthClient google=mock(GoogleDriveOAuthClient.class);
    private final GoogleDriveUploadLedger ledger=mock(GoogleDriveUploadLedger.class);
    private final PlatformAuthorizationService authorization=mock(PlatformAuthorizationService.class);
    private final Authentication auth=mock(Authentication.class);
    private final ObjectMapper json=new ObjectMapper();
    private final GoogleDriveConnectionService.FolderScope scope=new GoogleDriveConnectionService.FolderScope("subject","root_folder");
    private final URI url=URI.create("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=secret");
    private final GoogleDriveUploadSessionService service=new GoogleDriveUploadSessionService(
        acl,connection,browser,google,ledger,authorization,"staging_folder",5368709120L);
    private GoogleDriveFolderAccessService.BrowsePolicy policy(boolean upload) {
        return new GoogleDriveFolderAccessService.BrowsePolicy(scope,Set.of("anchor_folder"),
            Map.of("anchor_folder","Eventos"),Map.of("anchor_folder",upload?List.of(VIEW,UPLOAD):List.of(VIEW)));
    }
    private ObjectNode folder(String id,String parent) {
        var n=json.createObjectNode().put("id",id).put("mimeType","application/vnd.google-apps.folder")
            .put("ownedByMe",true).put("trashed",false).put("shared",false);
        if(parent!=null)n.putArray("parents").add(parent);
        n.putObject("capabilities").put("canAddChildren",true);
        return n;
    }
    private void setup(boolean upload) {
        User user=new User();user.setId(8);
        when(authorization.currentUser(auth)).thenReturn(user);
        when(acl.browsePolicy(auth)).thenReturn(policy(upload));
        when(connection.browserAccessToken(scope)).thenReturn("access");
        when(browser.verifyPath("access",policy(upload),"anchor_folder","child_folder"))
            .thenReturn(List.of("child_folder","anchor_folder"));
        when(google.uploadFolderMetadata("access","root")).thenReturn(folder("my_drive",null));
        when(google.uploadFolderMetadata("access","root_folder")).thenReturn(folder("root_folder","my_drive"));
        when(google.uploadFolderMetadata("access","staging_folder")).thenReturn(folder("staging_folder","my_drive"));
        when(google.uploadFolderMetadata("access","child_folder")).thenReturn(folder("child_folder","anchor_folder"));
        var permissions=json.createObjectNode();
        permissions.putArray("permissions").addObject().put("id","owner").put("type","user").put("role","owner");
        when(google.uploadFolderPermissions("access","staging_folder")).thenReturn(permissions);
        var generated=json.createObjectNode();generated.putArray("ids").add("file_123");
        when(google.generateUploadId("access")).thenReturn(generated);
        when(google.beginResumableUpload(eq("access"),eq("file_123"),eq("staging_folder"),
            anyString(),eq("arte.png"),eq("image/png"),eq(5368709120L))).thenReturn(url);
        when(ledger.markOpen(any())).thenReturn(true);
    }
    private GoogleDriveUploadSessionService.Start begin() {
        return service.begin(auth,"anchor_folder","child_folder","arte.png","image/png",5368709120L);
    }
    @Test void persistsReservationBeforeIssuingProviderCapability() {
        setup(true);
        var started=begin();
        assertEquals(url,started.uploadUri());
        assertFalse(started.toString().contains("secret"));
        var order=inOrder(ledger,google);
        order.verify(ledger).reserve(argThat(r->r.id().equals(started.sessionId())&&r.actor()==8&&
            r.providerId().equals("file_123")&&r.destination().equals("child_folder")&&r.size()==5368709120L));
        order.verify(google).beginResumableUpload("access","file_123","staging_folder",started.sessionId(),
            "arte.png","image/png",5368709120L);
        order.verify(ledger).markOpen(any());
    }
    @Test void viewAloneDoesNotPermitUpload() {
        setup(false);
        assertThrows(ResponseStatusException.class,this::begin);
        verifyNoInteractions(ledger,google);
    }
    @Test void revocationAfterProviderInitiationQueuesCleanupWithoutReturningUrl() {
        setup(true);
        when(acl.browsePolicy(auth)).thenReturn(policy(true),policy(true),policy(false));
        when(browser.verifyPath("access",policy(false),"anchor_folder","child_folder"))
            .thenReturn(List.of("child_folder","anchor_folder"));
        assertThrows(ResponseStatusException.class,this::begin);
        verify(ledger).requireCleanup(any());
        verify(ledger,never()).markOpen(any());
    }
    @Test void ambiguousProviderFailureRetainsReservationForReconciliation() {
        setup(true);
        when(google.beginResumableUpload(anyString(),anyString(),anyString(),anyString(),
            anyString(),anyString(),anyLong())).thenThrow(new GoogleDriveOAuthClient.ProviderFailure(0));
        assertThrows(ResponseStatusException.class,this::begin);
        verify(ledger).reserve(any());
        verify(ledger).requireCleanup(any());
        verify(ledger,never()).markOpen(any());
    }
    @Test void rejectsOversizeBeforeProviderOrDatabaseCalls() {
        assertEquals(413,assertThrows(ResponseStatusException.class,()->service.begin(auth,"anchor_folder",
            "child_folder","arte.png","image/png",5368709121L)).getStatusCode().value());
        verifyNoInteractions(ledger,google,connection,acl,authorization);
    }
}
