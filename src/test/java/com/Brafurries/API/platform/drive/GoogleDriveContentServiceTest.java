package com.Brafurries.API.platform.drive;

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

class GoogleDriveContentServiceTest {
    private final GoogleDriveFolderAccessService acl=mock(GoogleDriveFolderAccessService.class);
    private final GoogleDriveConnectionService connection=mock(GoogleDriveConnectionService.class);
    private final GoogleDriveOAuthClient google=mock(GoogleDriveOAuthClient.class);
    private final GoogleDriveBrowserService browser=mock(GoogleDriveBrowserService.class);
    private final Authentication auth=mock(Authentication.class);
    private final ObjectMapper json=new ObjectMapper();
    private final GoogleDriveConnectionService.FolderScope scope=new GoogleDriveConnectionService.FolderScope("subject","root_folder");
    private final GoogleDriveContentService service=new GoogleDriveContentService(acl,connection,google,browser);
    private GoogleDriveFolderAccessService.BrowsePolicy policy(boolean download) {
        return new GoogleDriveFolderAccessService.BrowsePolicy(scope,Set.of("anchor_folder"),
            Map.of("anchor_folder","Eventos"),Map.of("anchor_folder",download?List.of(VIEW,DOWNLOAD):List.of(VIEW)));
    }
    private ObjectNode metadata(String parent,String mime) {
        var n=json.createObjectNode().put("id","file_id").put("name","Example").put("mimeType",mime)
            .put("ownedByMe",true).put("trashed",false).put("version","1");
        n.putArray("parents").add(parent);n.putObject("capabilities").put("canDownload",true);
        return n;
    }
    private void setup(boolean download) {
        var policy=policy(download);
        when(acl.browsePolicy(auth)).thenReturn(policy);
        when(connection.browserAccessToken(scope)).thenReturn("access");
        when(browser.verifyPath("access",policy,"anchor_folder","child_folder"))
            .thenReturn(List.of("child_folder","anchor_folder"));
    }
    private GoogleDriveContentService.Content read(boolean preview) {
        return service.read(auth,"anchor_folder","child_folder","file_id",preview);
    }
    @Test void viewDoesNotImplyDownload() {
        setup(false);
        assertEquals(404,assertThrows(ResponseStatusException.class,()->read(false)).getStatusCode().value());
        verifyNoInteractions(google);
        when(google.fileMetadata("access","file_id")).thenReturn(metadata("child_folder","text/plain"));
        when(google.content("access","file_id",null,1024*1024)).thenReturn("hi".getBytes());
        assertEquals("text/plain",read(true).mime());
    }
    @Test void movedOrForgedFileNeverReleasesBytes() {
        setup(true);
        when(google.fileMetadata("access","file_id")).thenReturn(metadata("outside_folder","application/pdf"));
        assertThrows(ResponseStatusException.class,()->read(false));
        verify(google,never()).content(anyString(),anyString(),nullable(String.class),anyInt());
        when(google.fileMetadata("access","file_id")).thenReturn(metadata("child_folder","application/pdf"),
            metadata("outside_folder","application/pdf"));
        when(google.content("access","file_id",null,10*1024*1024)).thenReturn(new byte[]{1});
        assertThrows(ResponseStatusException.class,()->read(false));
    }
    @Test void downloadRevocationAfterProviderCallFailsClosed() {
        setup(true);
        when(acl.browsePolicy(auth)).thenReturn(policy(true),policy(false));
        when(google.fileMetadata("access","file_id")).thenReturn(metadata("child_folder","application/pdf"));
        when(google.content("access","file_id",null,10*1024*1024)).thenReturn(new byte[]{1});
        assertThrows(ResponseStatusException.class,()->read(false));
    }
    @Test void nativeDocumentUsesOfficeExportAndSafeAttachment() {
        setup(true);
        when(google.fileMetadata("access","file_id")).thenReturn(metadata("child_folder","application/vnd.google-apps.document"));
        when(google.content("access","file_id","application/vnd.openxmlformats-officedocument.wordprocessingml.document",10*1024*1024))
            .thenReturn(new byte[]{1});
        var result=read(false);
        assertEquals("Example.docx",result.name());
        assertEquals("application/octet-stream",result.mime());
        assertEquals("a_b_c.pdf",GoogleDriveContentService.safeName("a/b"+(char)10+"c.pdf",""));
    }
    @Test void providerCannotBypassSizeOrSafeFormat() {
        setup(true);
        when(google.fileMetadata("access","file_id")).thenReturn(metadata("child_folder","image/svg+xml"));
        assertEquals(415,assertThrows(ResponseStatusException.class,()->read(true)).getStatusCode().value());
        when(google.fileMetadata("access","file_id")).thenReturn(metadata("child_folder","application/pdf").put("size","10485761"));
        assertEquals(413,assertThrows(ResponseStatusException.class,()->read(false)).getStatusCode().value());
        verify(google,never()).content(anyString(),anyString(),nullable(String.class),anyInt());
        assertFalse(GoogleDriveContentService.validImage("<html>".getBytes(),"image/png"));
    }
    @Test void downloadRestrictionOnClosestManagedFolderOverridesAncestor() {
        var policy=new GoogleDriveFolderAccessService.BrowsePolicy(scope,Set.of("anchor_folder","child_folder"),
            Map.of("anchor_folder","Eventos","child_folder","Privado"),
            Map.of("anchor_folder",List.of(VIEW,DOWNLOAD),"child_folder",List.of(VIEW)));
        assertFalse(GoogleDriveBrowserService.operationAllowed(policy,List.of("child_folder","anchor_folder"),DOWNLOAD));
    }
}
