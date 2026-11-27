package com.Brafurries.API.platform.drive;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleDriveSearchServiceTest {
    private final GoogleDriveFolderAccessService acl=mock(GoogleDriveFolderAccessService.class);
    private final GoogleDriveConnectionService connection=mock(GoogleDriveConnectionService.class);
    private final GoogleDriveOAuthClient google=mock(GoogleDriveOAuthClient.class);
    private final PlatformAuthorizationService authorization=mock(PlatformAuthorizationService.class);
    private final Authentication auth=mock(Authentication.class);
    private final ObjectMapper json=new ObjectMapper();
    private final GoogleDriveConnectionService.FolderScope scope=new GoogleDriveConnectionService.FolderScope("subject","root_folder");
    private final GoogleDriveSearchService service=new GoogleDriveSearchService(acl,connection,google,authorization,true);
    private ObjectNode node(String id,String name,String mime,String parent){
        var n=json.createObjectNode().put("id",id).put("name",name).put("mimeType",mime)
            .put("ownedByMe",true).put("trashed",false).put("version","12");
        if(parent!=null)n.putArray("parents").add(parent);
        return n;
    }
    private ObjectNode folder(String id,String name,String parent){
        return node(id,name,"application/vnd.google-apps.folder",parent);
    }
    private GoogleDriveFolderAccessService.BrowsePolicy setup(boolean allowed){
        var actor=new User();actor.setId(8);when(authorization.currentUser(auth)).thenReturn(actor);
        var p=new GoogleDriveFolderAccessService.BrowsePolicy(scope,Set.of("anchor_folder","denied_folder"),
            allowed?Map.of("anchor_folder","Eventos"):Map.of());
        when(acl.browsePolicy(auth)).thenReturn(p);
        when(connection.browserAccessToken(scope)).thenReturn("access");
        when(google.root("access","root_folder")).thenReturn(folder("root_folder","Institucional",null));
        when(google.root("access","anchor_folder")).thenReturn(folder("anchor_folder","Nome no Google","root_folder"));
        when(google.root("access","child_folder")).thenReturn(folder("child_folder","Artes","anchor_folder"));
        when(google.root("access","denied_folder")).thenReturn(folder("denied_folder","Privado","anchor_folder"));
        var file=node("file_123","Arte.png","image/png","child_folder");
        var denied=node("file_456","Arte secreta.png","image/png","denied_folder");
        var foreign=node("file_789","Arte externa.png","image/png","other_folder");
        when(google.searchMetadata("access","file_123")).thenReturn(file);
        when(google.searchMetadata("access","file_456")).thenReturn(denied);
        when(google.searchMetadata("access","file_789")).thenReturn(foreign);
        when(google.root("access","other_folder")).thenReturn(folder("other_folder","Fora da raiz",null));
        var response=json.createObjectNode().put("nextPageToken","private-provider-token");
        response.putArray("files").add(file).add(denied).add(foreign);
        when(google.searchNames("access","arte",null)).thenReturn(response);
        return p;
    }
    @Test void returnsOnlyAccessibleNamesAndPathBelowAuthorizedMount(){
        setup(true);
        var result=service.search(auth,"arte",null);
        assertEquals(1,result.entries().size());
        var hit=result.entries().getFirst();
        assertEquals("file_123",hit.id());
        assertEquals(List.of(new GoogleDriveBrowserService.Mount("anchor_folder","Eventos"),
            new GoogleDriveBrowserService.Mount("child_folder","Artes")),hit.trail());
        assertNotNull(result.nextCursor());assertNotEquals("private-provider-token",result.nextCursor());
    }
    @Test void noVisibleMountMeansNoProviderSearch(){
        setup(false);
        assertTrue(service.search(auth,"arte",null).entries().isEmpty());
        verify(google,never()).searchNames(anyString(),anyString(),nullable(String.class));
    }
    @Test void hiddenResourceAndMovedFileAreNotReturned(){
        setup(true);
        when(connection.hiddenUploadResources(scope)).thenReturn(Set.of("file_123"));
        assertTrue(service.search(auth,"arte",null).entries().isEmpty());
        when(connection.hiddenUploadResources(scope)).thenReturn(Set.of());
        when(google.searchMetadata("access","file_123")).thenReturn(node("file_123","Arte.png","image/png","child_folder"),
            node("file_123","Arte.png","image/png","denied_folder"));
        assertTrue(service.search(auth,"arte",null).entries().isEmpty());
    }
    @Test void cursorIsBoundToQueryAndActor(){
        setup(true);
        var cursor=service.search(auth,"arte",null).nextCursor();
        assertThrows(ResponseStatusException.class,()->service.search(auth,"outro",cursor));
        var other=new User();other.setId(9);when(authorization.currentUser(auth)).thenReturn(other);
        assertThrows(ResponseStatusException.class,()->service.search(auth,"arte",cursor));
        verify(google,never()).searchNames("access","arte","private-provider-token");
    }
    @Test void revocationDuringSearchSuppressesEntireResponse(){
        var before=setup(true);
        var after=new GoogleDriveFolderAccessService.BrowsePolicy(scope,before.registered(),Map.of());
        when(acl.browsePolicy(auth)).thenReturn(before,after);
        assertThrows(ResponseStatusException.class,()->service.search(auth,"arte",null));
    }
    @Test void escapesProviderQueryAndMatchesNamesNotDocumentContent(){
        assertTrue(GoogleDriveOAuthClient.searchQuery("a'b\\c").contains("a\\'b\\\\c"));
        assertTrue(GoogleDriveSearchService.matches("Minha ARTE.png","arte"));
        assertFalse(GoogleDriveSearchService.matches("Contratos.pdf","arte"));
        assertThrows(ResponseStatusException.class,()->GoogleDriveSearchService.normalize("a"));
    }
}
