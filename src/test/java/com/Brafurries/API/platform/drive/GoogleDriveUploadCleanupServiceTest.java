package com.Brafurries.API.platform.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class GoogleDriveUploadCleanupServiceTest {
    private final GoogleDriveUploadLedger ledger=mock(GoogleDriveUploadLedger.class);
    private final GoogleDriveConnectionService connection=mock(GoogleDriveConnectionService.class);
    private final GoogleDriveOAuthClient google=mock(GoogleDriveOAuthClient.class);
    private final ObjectMapper json=new ObjectMapper();
    private final GoogleDriveConnectionService.FolderScope scope=new GoogleDriveConnectionService.FolderScope("subject","root_folder");
    private final GoogleDriveUploadLedger.Reservation r=new GoogleDriveUploadLedger.Reservation(
        "d15410a1-af3b-4d6f-9941-f8ade811b110",8,scope,"anchor_folder","child_folder",
        "staging_folder","file_123","arte.png","image/png",100);
    private final GoogleDriveUploadCleanupService service=new GoogleDriveUploadCleanupService(ledger,connection,google,true);
    private ObjectNode folder(String id,String parent) {
        var n=json.createObjectNode().put("id",id).put("mimeType","application/vnd.google-apps.folder")
            .put("ownedByMe",true).put("trashed",false).put("shared",false);
        if(parent!=null)n.putArray("parents").add(parent);
        n.putObject("capabilities").put("canAddChildren",true);return n;
    }
    private ObjectNode file(String parent,boolean trash) {
        var n=json.createObjectNode().put("id",r.providerId()).put("mimeType",r.mime())
            .put("ownedByMe",true).put("trashed",trash).put("shared",false).put("size","999");
        n.putArray("parents").add(parent);n.putObject("appProperties").put("brafurriesUploadSession",r.id());return n;
    }
    private void setup() {
        when(ledger.claimCleanup(r)).thenReturn(true);
        when(connection.browserAccessToken(scope)).thenReturn("access");
        when(google.uploadFolderMetadata("access","root")).thenReturn(folder("my_drive",null));
        when(google.uploadFolderMetadata("access","root_folder")).thenReturn(folder("root_folder","my_drive"));
        when(google.uploadFolderMetadata("access",r.staging())).thenReturn(folder(r.staging(),"my_drive"));
        var permissions=json.createObjectNode();permissions.putArray("permissions").addObject().put("id","owner").put("type","user").put("role","owner");
        when(google.uploadFolderPermissions("access",r.staging())).thenReturn(permissions);
    }
    private GoogleDriveUploadLedger.Session session(boolean past) {
        return new GoogleDriveUploadLedger.Session(r,"CLEANUP_PENDING",true,past);
    }
    @Test void missingFileBeforeProviderExpiryIsNotFinalCleanup() {
        setup();when(google.uploadedFile("access",r.providerId())).thenThrow(new GoogleDriveOAuthClient.ProviderFailure(404));
        service.cleanup(session(false));
        verify(ledger).retryCleanup(r);verify(ledger,never()).cleaned(r);
    }
    @Test void validStagedFileWithIncorrectSizeIsTrashedButReservationRetained() {
        setup();when(google.uploadedFile("access",r.providerId())).thenReturn(file(r.staging(),false));
        when(google.trashStagedFile("access",r.providerId())).thenReturn(file(r.staging(),true));
        service.cleanup(session(false));
        verify(google).trashStagedFile("access",r.providerId());
        verify(ledger).retryCleanup(r);verify(ledger,never()).cleaned(r);
    }
    @Test void neverTrashFileOutsideStaging() {
        setup();when(google.uploadedFile("access",r.providerId())).thenReturn(file(r.destination(),false));
        service.cleanup(session(true));
        verify(ledger).cleanupNeedsReview(r);
        verify(google,never()).trashStagedFile(anyString(),anyString());
    }
    @Test void cleanupCompletesOnlyAfterProviderWindow() {
        setup();when(google.uploadedFile("access",r.providerId())).thenReturn(file(r.staging(),true));
        service.cleanup(session(true));
        verify(ledger).cleaned(r);
        verify(google,never()).trashStagedFile(anyString(),anyString());
    }
    @Test void cannotCleanWithoutClaim() {
        when(ledger.claimCleanup(r)).thenReturn(false);
        service.cleanup(session(true));verifyNoInteractions(google,connection);
    }
}
