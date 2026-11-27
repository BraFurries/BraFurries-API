package com.Brafurries.API.platform.drive;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.HttpURLConnection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static com.Brafurries.API.platform.drive.GoogleDriveFolderAccessService.Permission.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleDriveStreamingDownloadServiceTest {
    private final GoogleDriveDownloadTicketService tickets=mock(GoogleDriveDownloadTicketService.class);
    private final GoogleDriveFolderAccessService acl=mock(GoogleDriveFolderAccessService.class);
    private final GoogleDriveConnectionService connection=mock(GoogleDriveConnectionService.class);
    private final GoogleDriveBrowserService browser=mock(GoogleDriveBrowserService.class);
    private final GoogleDriveOAuthClient google=mock(GoogleDriveOAuthClient.class);
    private final PlatformAuthorizationService authorization=mock(PlatformAuthorizationService.class);
    private final Authentication auth=mock(Authentication.class);
    private final GoogleDriveConnectionService.FolderScope scope=new GoogleDriveConnectionService.FolderScope("subject","root_folder");
    private final GoogleDriveDownloadTicketService.Target target=new GoogleDriveDownloadTicketService.Target(8,1,scope,"anchor_folder","child_folder","file_123");
    private final GoogleDriveStreamingDownloadService service=new GoogleDriveStreamingDownloadService(tickets,acl,connection,browser,google,authorization,true,5368709120L);
    private GoogleDriveFolderAccessService.BrowsePolicy policy(boolean download){
        return new GoogleDriveFolderAccessService.BrowsePolicy(scope,Set.of("anchor_folder"),Map.of("anchor_folder","Eventos"),
            Map.of("anchor_folder",download?List.of(VIEW,DOWNLOAD):List.of(VIEW)));
    }
    private void setup(boolean download){
        var user=new User();user.setId(8);
        when(authorization.currentUser(auth)).thenReturn(user);
        when(tickets.consume("ticket")).thenReturn(target);when(tickets.authenticate(target)).thenReturn(auth);
        when(connection.browserAccessToken(scope)).thenReturn("access");
        when(acl.browsePolicy(auth)).thenReturn(policy(download));
        when(browser.verifyPath("access",policy(download),"anchor_folder","child_folder")).thenReturn(List.of("child_folder","anchor_folder"));
        var file=new ObjectMapper().createObjectNode().put("id","file_123").put("name","arte.png")
            .put("mimeType","image/png").put("ownedByMe",true).put("trashed",false).put("size","3").put("version","1");
        file.putArray("parents").add("child_folder");file.putObject("capabilities").put("canDownload",true);
        when(google.fileMetadata("access","file_123")).thenReturn(file);
    }
    @Test void streamsOnlyAfterValidTicketAndPermission() throws Exception {
        setup(true);var http=mock(HttpURLConnection.class);
        when(google.openMedia("access","file_123",3)).thenReturn(new GoogleDriveOAuthClient.MediaStream(http,new ByteArrayInputStream(new byte[]{1,2,3})));
        var response=new MockHttpServletResponse();service.download("ticket",response);
        assertArrayEquals(new byte[]{1,2,3},response.getContentAsByteArray());
        assertEquals("3",response.getHeader("Content-Length"));assertEquals("no-store",response.getHeader("Cache-Control"));
        verify(http).disconnect();
    }
    @Test void missingDownloadGrantNeverOpensMedia(){
        setup(false);assertThrows(ResponseStatusException.class,()->service.download("ticket",new MockHttpServletResponse()));
        verify(google,never()).openMedia(anyString(),anyString(),anyLong());
    }
    @Test void permissionRevokedBeforeFirstByteClosesProvider() {
        setup(true);when(acl.browsePolicy(auth)).thenReturn(policy(true),policy(true),policy(false));
        when(browser.verifyPath("access",policy(false),"anchor_folder","child_folder")).thenReturn(List.of("child_folder","anchor_folder"));
        var http=mock(HttpURLConnection.class);
        when(google.openMedia("access","file_123",3)).thenReturn(new GoogleDriveOAuthClient.MediaStream(http,new ByteArrayInputStream(new byte[]{1,2,3})));
        var response=new MockHttpServletResponse();
        assertThrows(ResponseStatusException.class,()->service.download("ticket",response));
        assertEquals(0,response.getContentAsByteArray().length);verify(http).disconnect();
    }
    @Test void unpublishedFileCannotBeDownloaded(){
        setup(true);when(connection.hiddenUploadResources(scope)).thenReturn(Set.of("file_123"));
        assertThrows(ResponseStatusException.class,()->service.download("ticket",new MockHttpServletResponse()));
        verify(google,never()).openMedia(anyString(),anyString(),anyLong());
    }
}
