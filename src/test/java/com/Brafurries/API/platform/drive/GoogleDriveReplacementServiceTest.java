package com.Brafurries.API.platform.drive;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleDriveReplacementServiceTest {
    private final GoogleDriveUploadLedger uploads=mock(GoogleDriveUploadLedger.class);
    private final GoogleDriveReplacementLedger ledger=mock(GoogleDriveReplacementLedger.class);
    private final GoogleDriveRevisionService revisions=mock(GoogleDriveRevisionService.class);
    private final GoogleDriveConnectionService connection=mock(GoogleDriveConnectionService.class);
    private final GoogleDriveOAuthClient google=mock(GoogleDriveOAuthClient.class);
    private final PlatformAuthorizationService authz=mock(PlatformAuthorizationService.class);
    private final Authentication auth=mock(Authentication.class);
    private final ObjectMapper json=new ObjectMapper();
    private final GoogleDriveConnectionService.FolderScope scope=new GoogleDriveConnectionService.FolderScope("subject","root_folder");
    private final String oldHash="a".repeat(64);
    private final String newHash="039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81";
    private final String session="d15410a1-af3b-4d6f-9941-f8ade811b110";
    private final GoogleDriveUploadLedger.Reservation r=new GoogleDriveUploadLedger.Reservation(session,8,scope,
        "anchor_folder","child_folder","staging_folder","staged_file","art.png","image/png",3);
    private final GoogleDriveUploadLedger.Replacement intent=new GoogleDriveUploadLedger.Replacement(
        "original_file","12","rev_1",oldHash,"art.png");
    private final URI uri=URI.create("https://www.googleapis.com/upload/drive/v3/files/original_file?uploadType=resumable&upload_id=secret");
    private final GoogleDriveReplacementService service=new GoogleDriveReplacementService(uploads,ledger,revisions,
        connection,google,authz,"staging_folder",true,268435456);
    private GoogleDriveRevisionService.Target target(String version,String head,String hash,String size){
        return new GoogleDriveRevisionService.Target(scope,List.of("child_folder","anchor_folder"),
            new GoogleDriveRevisionService.Snapshot("art.png","image/png",version,head,hash,size));
    }
    private ObjectNode folder(String id,String parent){
        var n=json.createObjectNode().put("id",id).put("mimeType","application/vnd.google-apps.folder")
            .put("ownedByMe",true).put("trashed",false).put("shared",false);
        if(parent!=null)n.putArray("parents").add(parent);
        n.putObject("capabilities").put("canAddChildren",true);return n;
    }
    private void setup(byte[] bytes){
        var user=new User();user.setId(8);
        when(authz.requireOwner(auth)).thenReturn(user);when(authz.currentUser(auth)).thenReturn(user);
        when(uploads.owned(session,8)).thenReturn(new GoogleDriveUploadLedger.Session(r,"OPEN",false,false));
        when(ledger.intent(r)).thenReturn(intent);
        when(connection.browserAccessToken(scope)).thenReturn("access");
        var original=target("12","rev_1",oldHash,"10");
        var pinned=target("13","rev_1",oldHash,"10");
        var applied=target("14","rev_2",newHash,"3");
        when(revisions.inspect(auth,scope,"access",r.anchor(),r.destination(),intent.target(),true))
            .thenReturn(original,original,pinned,pinned,pinned,applied,applied);
        when(google.uploadFolderMetadata("access","root")).thenReturn(folder("my_drive",null));
        when(google.uploadFolderMetadata("access","root_folder")).thenReturn(folder("root_folder","my_drive"));
        when(google.uploadFolderMetadata("access",r.staging())).thenReturn(folder(r.staging(),"my_drive"));
        var permissions=json.createObjectNode();
        permissions.putArray("permissions").addObject().put("id","owner").put("type","user").put("role","owner");
        when(google.uploadFolderPermissions("access",r.staging())).thenReturn(permissions);
        var staged=json.createObjectNode().put("id",r.providerId()).put("name",r.name()).put("mimeType",r.mime())
            .put("ownedByMe",true).put("trashed",false).put("shared",false).put("size","3").put("version","1")
            .put("sha256Checksum",newHash).put("headRevisionId","stage_rev");
        staged.putArray("parents").add(r.staging());staged.putObject("appProperties").put("brafurriesUploadSession",session);
        when(google.uploadedFile("access",r.providerId())).thenReturn(staged);
        when(google.revisionFile("access",r.providerId())).thenReturn(staged);
        var pin=json.createObjectNode().put("id","rev_1").put("keepForever",true);
        when(google.revision("access",intent.target(),"rev_1")).thenReturn(json.createObjectNode().put("id","rev_1").put("keepForever",false),pin,pin,pin);
        when(google.preserveRevision("access",intent.target(),"rev_1")).thenReturn(pin);
        when(google.beginReplacementUpdate("access",intent.target(),r.mime(),3)).thenReturn(uri);
        when(google.openMedia("access",r.providerId(),3)).thenReturn(new GoogleDriveOAuthClient.MediaStream(
            mock(HttpURLConnection.class),new ByteArrayInputStream(bytes)));
    }
    @Test void preservesOriginalAndVerifiesNewHeadBeforeCompleting(){
        setup(new byte[]{1,2,3});
        assertEquals("rev_2",service.apply(auth,session).revisionId());
        var order=inOrder(ledger,google);
        order.verify(ledger).claim(r,intent,newHash,"1");
        order.verify(google).preserveRevision("access",intent.target(),"rev_1");
        order.verify(google).beginReplacementUpdate("access",intent.target(),r.mime(),3);
        order.verify(google).replacementChunk(eq(uri),eq(intent.target()),eq(r.mime()),eq(0L),eq(3L),org.mockito.AdditionalMatchers.aryEq(new byte[]{1,2,3}));
        order.verify(ledger).complete(r,"rev_2");
    }
    @Test void checksumMismatchNeverSendsFinalBlock(){
        setup(new byte[]{1,2,4});
        assertThrows(ResponseStatusException.class,()->service.apply(auth,session));
        verify(google,never()).replacementChunk(any(),anyString(),anyString(),anyLong(),anyLong(),any());
        verify(ledger).uncertain(r);verify(ledger,never()).complete(any(),anyString());
    }
    @Test void oldVersionMismatchNeverClaimsOrPins(){
        setup(new byte[]{1,2,3});
        when(revisions.inspect(auth,scope,"access",r.anchor(),r.destination(),intent.target(),true))
            .thenReturn(target("15","rev_1",oldHash,"10"));
        assertThrows(ResponseStatusException.class,()->service.apply(auth,session));
        verify(ledger,never()).claim(any(),any(),anyString(),anyString());
        verify(google,never()).preserveRevision(anyString(),anyString(),anyString());
    }
    @Test void serverCapabilityCannotBeReusedForAnotherFile(){
        assertEquals(uri,GoogleDriveUploadPolicy.sessionUri(uri.toString(),intent.target()));
        assertThrows(ResponseStatusException.class,()->GoogleDriveUploadPolicy.sessionUri(uri.toString(),"other_file"));
        assertThrows(ResponseStatusException.class,()->GoogleDriveUploadPolicy.sessionUri(uri.toString()));
    }

    @Test void permissionRevokedBeforeFinalBlockDoesNotOverwrite(){
        setup(new byte[]{1,2,3});
        var original=target("12","rev_1",oldHash,"10");
        var pinned=target("13","rev_1",oldHash,"10");
        when(revisions.inspect(auth,scope,"access",r.anchor(),r.destination(),intent.target(),true))
            .thenReturn(original,original,pinned,pinned)
            .thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class,()->service.apply(auth,session));
        verify(google,never()).replacementChunk(any(),anyString(),anyString(),anyLong(),anyLong(),any());
        verify(ledger).uncertain(r);
    }
    @Test void ambiguousFinalResponseDoesNotRetryOrMarkApplied(){
        setup(new byte[]{1,2,3});
        doThrow(new GoogleDriveOAuthClient.ProviderFailure(0)).when(google)
            .replacementChunk(any(),anyString(),anyString(),anyLong(),anyLong(),any());
        assertThrows(ResponseStatusException.class,()->service.apply(auth,session));
        verify(google,times(1)).replacementChunk(any(),anyString(),anyString(),anyLong(),anyLong(),any());
        verify(ledger).uncertain(r);verify(ledger,never()).complete(any(),anyString());
    }
}
