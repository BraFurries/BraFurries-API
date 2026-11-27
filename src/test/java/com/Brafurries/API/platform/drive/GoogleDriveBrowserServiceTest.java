package com.Brafurries.API.platform.drive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleDriveBrowserServiceTest {
    private final GoogleDriveFolderAccessService acl=mock(GoogleDriveFolderAccessService.class);
    private final GoogleDriveConnectionService connection=mock(GoogleDriveConnectionService.class);
    private final GoogleDriveOAuthClient google=mock(GoogleDriveOAuthClient.class);
    private final Authentication auth=mock(Authentication.class);
    private final ObjectMapper json=new ObjectMapper();
    private final GoogleDriveConnectionService.FolderScope scope=new GoogleDriveConnectionService.FolderScope("subject","root_folder");
    private final GoogleDriveBrowserService service=new GoogleDriveBrowserService(acl,connection,google);
    private GoogleDriveFolderAccessService.BrowsePolicy policy(boolean allowed) {
        return new GoogleDriveFolderAccessService.BrowsePolicy(scope,Set.of("anchor_folder","secret_folder"),
            allowed?Map.of("anchor_folder","Eventos"):Map.of());
    }
    private ObjectNode file(String id,String parent,String mime) {
        var n=json.createObjectNode().put("id",id).put("name",id).put("mimeType",mime)
            .put("ownedByMe",true).put("trashed",false);
        if(parent!=null)n.putArray("parents").add(parent);
        return n;
    }
    private void folder(String id,String parent) {
        when(google.root("access",id)).thenReturn(file(id,parent,"application/vnd.google-apps.folder"));
    }
    private void setup() {
        when(acl.browsePolicy(auth)).thenReturn(policy(true));
        when(connection.browserAccessToken(scope)).thenReturn("access");
        folder("anchor_folder","root_folder");folder("root_folder",null);
    }
    @Test void unapprovedAnchorNeverCallsGoogleOrRefreshesToken() {
        when(acl.browsePolicy(auth)).thenReturn(policy(false));
        assertThrows(ResponseStatusException.class,()->service.list(auth,"anchor_folder","anchor_folder",null));
        verifyNoInteractions(connection,google);
    }
    @Test void mountListUsesOnlyAuthorizedRegistry() {
        when(acl.browsePolicy(auth)).thenReturn(policy(true));
        assertEquals(List.of(new GoogleDriveBrowserService.Mount("anchor_folder","Eventos")),service.mounts(auth));
        verifyNoInteractions(connection,google);
    }
    @Test void rejectsOutsideRootAndManagedBoundary() {
        setup();
        folder("outside_folder","root_folder");
        assertThrows(ResponseStatusException.class,()->service.list(auth,"anchor_folder","outside_folder",null));
        assertThrows(ResponseStatusException.class,()->service.list(auth,"anchor_folder","secret_folder",null));
        verify(google,never()).children(anyString(),anyString(),nullable(String.class));
    }
    @Test void filtersForeignFilesShortcutsAndRestrictedSubfoldersAndKeepsPagination() {
        setup();
        var page=json.createObjectNode().put("nextPageToken","next");
        var files=page.putArray("files");
        files.add(file("normal_file","anchor_folder","application/pdf"));
        files.add(file("secret_folder","anchor_folder","application/vnd.google-apps.folder"));
        files.add(file("shortcut_file","anchor_folder","application/vnd.google-apps.shortcut"));
        files.add(file("foreign_file","anchor_folder","application/pdf").put("ownedByMe",false));
        files.add(file("wrong_parent","other_folder","application/pdf"));
        when(google.children("access","anchor_folder",null)).thenReturn(page);
        var result=service.list(auth,"anchor_folder","anchor_folder",null);
        assertEquals(List.of("normal_file"),result.entries().stream().map(GoogleDriveBrowserService.Entry::id).toList());
        assertEquals("next",result.nextPageToken());
    }
    @Test void inheritedChildAccessFailsIfRevokedDuringProviderCall() {
        setup();folder("child_folder","anchor_folder");
        when(acl.browsePolicy(auth)).thenReturn(policy(true),policy(false));
        var page=json.createObjectNode();page.putArray("files");
        when(google.children("access","child_folder",null)).thenReturn(page);
        assertThrows(ResponseStatusException.class,()->service.list(auth,"anchor_folder","child_folder",null));
    }
    @Test void detectsMoveOutsideAnchorDuringListing() {
        setup();
        when(google.root("access","anchor_folder")).thenReturn(
            file("anchor_folder","root_folder","application/vnd.google-apps.folder"),
            file("anchor_folder","outside_folder","application/vnd.google-apps.folder"));
        folder("outside_folder",null);
        var page=json.createObjectNode();page.putArray("files");
        when(google.children("access","anchor_folder",null)).thenReturn(page);
        assertThrows(ResponseStatusException.class,()->service.list(auth,"anchor_folder","anchor_folder",null));
    }

    @Test void incompleteProviderSearchDoesNotLookLikeAnEmptyFolder() {
        setup();
        var page=json.createObjectNode().put("incompleteSearch",true);page.putArray("files");
        when(google.children("access","anchor_folder",null)).thenReturn(page);
        assertEquals(503,assertThrows(ResponseStatusException.class,
            ()->service.list(auth,"anchor_folder","anchor_folder",null)).getStatusCode().value());
    }

    @Test void newExplicitBoundaryDuringListingFailsClosed() {
        setup();folder("child_folder","anchor_folder");
        var page=json.createObjectNode();page.putArray("files");
        when(google.children("access","child_folder",null)).thenReturn(page);
        var changed=new GoogleDriveFolderAccessService.BrowsePolicy(scope,
            Set.of("anchor_folder","secret_folder","child_folder"),Map.of("anchor_folder","Eventos"));
        when(acl.browsePolicy(auth)).thenReturn(policy(true),changed);
        assertEquals(404,assertThrows(ResponseStatusException.class,
            ()->service.list(auth,"anchor_folder","child_folder",null)).getStatusCode().value());
    }
}
