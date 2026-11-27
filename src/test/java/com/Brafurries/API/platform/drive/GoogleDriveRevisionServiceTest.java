package com.Brafurries.API.platform.drive;

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

class GoogleDriveRevisionServiceTest {
    private final GoogleDriveFolderAccessService acl=mock(GoogleDriveFolderAccessService.class);
    private final GoogleDriveConnectionService connection=mock(GoogleDriveConnectionService.class);
    private final GoogleDriveBrowserService browser=mock(GoogleDriveBrowserService.class);
    private final GoogleDriveOAuthClient google=mock(GoogleDriveOAuthClient.class);
    private final GoogleDriveUploadSessionService uploads=mock(GoogleDriveUploadSessionService.class);
    private final Authentication auth=mock(Authentication.class);
    private final ObjectMapper json=new ObjectMapper();
    private final GoogleDriveConnectionService.FolderScope scope=new GoogleDriveConnectionService.FolderScope("subject","root_folder");
    private final String hash="a".repeat(64);
    private final GoogleDriveRevisionService service=new GoogleDriveRevisionService(acl,connection,browser,google,uploads,true);
    private GoogleDriveFolderAccessService.BrowsePolicy policy(boolean replace){
        return new GoogleDriveFolderAccessService.BrowsePolicy(scope,Set.of("anchor_folder"),Map.of("anchor_folder","Eventos"),
            Map.of("anchor_folder",replace?List.of(VIEW,REPLACE):List.of(VIEW)));
    }
    private ObjectNode file(){
        var n=json.createObjectNode().put("id","file_123").put("name","arte.png").put("mimeType","image/png")
            .put("ownedByMe",true).put("trashed",false).put("shared",false).put("version","12")
            .put("headRevisionId","rev_2").put("sha256Checksum",hash).put("size","100");
        n.putArray("parents").add("child_folder");n.putObject("capabilities").put("canModifyContent",true);
        return n;
    }
    private void setup(boolean replace){
        when(acl.browsePolicy(auth)).thenReturn(policy(replace));
        when(connection.browserAccessToken(scope)).thenReturn("access");
        when(browser.verifyPath("access",policy(replace),"anchor_folder","child_folder")).thenReturn(List.of("child_folder","anchor_folder"));
        when(google.revisionFile("access","file_123")).thenReturn(file());
        var response=json.createObjectNode().put("nextPageToken","next");
        var revisions=response.putArray("revisions");
        revisions.addObject().put("id","rev_1").put("modifiedTime","2026-11-26T10:00:00Z").put("size","90").put("keepForever",true);
        revisions.addObject().put("id","rev_2").put("modifiedTime","2026-11-27T10:00:00Z").put("size","100").put("keepForever",false);
        when(google.revisions("access","file_123",null)).thenReturn(response);
    }
    @Test void historyIdentifiesCurrentAndKeptRevisionsWithoutExposingContent(){
        setup(false);
        var result=service.history(auth,"anchor_folder","child_folder","file_123",null);
        assertEquals("next",result.nextPageToken());assertEquals(2,result.revisions().size());
        assertTrue(result.revisions().get(0).kept());assertFalse(result.revisions().get(0).current());
        assertTrue(result.revisions().get(1).current());verifyNoInteractions(uploads);
    }
    @Test void hiddenOrForeignFileNeverListsRevisions(){
        setup(false);when(connection.hiddenUploadResources(scope)).thenReturn(Set.of("file_123"));
        assertThrows(ResponseStatusException.class,()->service.history(auth,"anchor_folder","child_folder","file_123",null));
        verifyNoInteractions(google,uploads);
    }
    @Test void changedRevisionDuringListingIsRejected(){
        setup(false);when(google.revisionFile("access","file_123")).thenReturn(file(),file().put("version","13"));
        assertThrows(ResponseStatusException.class,()->service.history(auth,"anchor_folder","child_folder","file_123",null));
    }
    @Test void replacementRequiresIndependentPermission(){
        setup(false);
        assertThrows(ResponseStatusException.class,()->service.prepareReplacement(auth,"anchor_folder","child_folder","file_123","12","image/png",500));
        verifyNoInteractions(uploads);
    }
    @Test void replacementBindsOriginalIdentityAndNeverReturnsOriginalUpdateCapability(){
        setup(true);
        var intent=new GoogleDriveUploadLedger.Replacement("file_123","12","rev_2",hash,"arte.png");
        var start=new GoogleDriveUploadSessionService.Start("session",URI.create("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=secret"));
        when(uploads.beginReplacement(auth,"anchor_folder","child_folder","arte.png","image/png",500,intent,scope)).thenReturn(start);
        assertEquals(start,service.prepareReplacement(auth,"anchor_folder","child_folder","file_123","12","image/png",500));
        verify(uploads).beginReplacement(auth,"anchor_folder","child_folder","arte.png","image/png",500,intent,scope);
    }
    @Test void staleOrSharedOriginalCannotStartReplacement(){
        setup(true);
        assertThrows(ResponseStatusException.class,()->service.prepareReplacement(auth,"anchor_folder","child_folder","file_123","11","image/png",500));
        when(google.revisionFile("access","file_123")).thenReturn(file().put("shared",true));
        assertThrows(ResponseStatusException.class,()->service.prepareReplacement(auth,"anchor_folder","child_folder","file_123","12","image/png",500));
        verifyNoInteractions(uploads);
    }
}
