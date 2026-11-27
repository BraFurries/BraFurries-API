package com.Brafurries.API.platform.drive;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleDriveReplacementPilotControllerTest {
    private final PlatformAuthorizationService authz=mock(PlatformAuthorizationService.class);
    private final GoogleDriveRevisionService revisions=mock(GoogleDriveRevisionService.class);
    private final GoogleDriveReplacementService replacement=mock(GoogleDriveReplacementService.class);
    private final GoogleDriveUploadLedger uploads=mock(GoogleDriveUploadLedger.class);
    private final GoogleDriveReplacementLedger ledger=mock(GoogleDriveReplacementLedger.class);
    private final GoogleDriveFolderAccessService acl=mock(GoogleDriveFolderAccessService.class);
    private final Authentication auth=mock(Authentication.class);
    private final String id="d15410a1-af3b-4d6f-9941-f8ade811b110";
    private final GoogleDriveConnectionService.FolderScope scope=new GoogleDriveConnectionService.FolderScope("subject","root_folder");
    private final GoogleDriveUploadLedger.Reservation r=new GoogleDriveUploadLedger.Reservation(id,8,scope,
        "anchor_folder","child_folder","staging_folder","staged_file","art.png","image/png",3);
    private GoogleDriveReplacementPilotController controller(boolean enabled){
        return new GoogleDriveReplacementPilotController(authz,revisions,replacement,uploads,ledger,acl,
            enabled,true,true,"staging_folder",268435456,5368709120L);
    }
    private void owner(String state){
        var user=new User();user.setId(8);when(authz.requireOwner(auth)).thenReturn(user);
        when(uploads.owned(id,8)).thenReturn(new GoogleDriveUploadLedger.Session(r,state,false,false));
        when(acl.browsePolicy(auth)).thenReturn(new GoogleDriveFolderAccessService.BrowsePolicy(scope,Set.of(),Map.of()));
    }
    @Test void disabledPilotCannotBeginOrPublish(){
        owner("OPEN");var c=controller(false);
        var request=new GoogleDriveReplacementPilotController.Begin("anchor_folder","child_folder","file_123","12","image/png",3);
        assertEquals(503,assertThrows(ResponseStatusException.class,()->c.begin(auth,request)).getStatusCode().value());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->c.publish(auth,id)).getStatusCode().value());
        verifyNoInteractions(revisions,replacement);
    }
    @Test void nonOwnerCannotBegin(){
        when(authz.requireOwner(auth)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class,()->controller(true).begin(auth,
            new GoogleDriveReplacementPilotController.Begin("anchor_folder","child_folder","file_123","12","image/png",3)));
        verifyNoInteractions(revisions,replacement);
    }
    @Test void appliedStatusSurvivesStagingCleanupWithoutRetry(){
        owner("CLEANUP_PENDING");
        when(ledger.find(id)).thenReturn(new GoogleDriveReplacementLedger.Commit("APPLIED","rev_2"));
        var result=controller(true).publish(auth,id);
        assertEquals("PUBLISHED",result.getBody().status());
        assertEquals("no-store",result.getHeaders().getCacheControl());
        verifyNoInteractions(replacement);
        controller(true).cancel(auth,id);verify(uploads,never()).cancel(any());
    }
    @Test void ambiguousOrRunningCommitCannotBeReplayed(){
        owner("RECONCILE_REQUIRED");
        when(ledger.find(id)).thenReturn(new GoogleDriveReplacementLedger.Commit("APPLYING",null));
        assertEquals("RECONCILE_REQUIRED",controller(true).status(auth,id).getBody().status());
        assertThrows(ResponseStatusException.class,()->controller(true).publish(auth,id));
        verifyNoInteractions(replacement);
    }
}
