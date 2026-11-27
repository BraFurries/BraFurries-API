package com.Brafurries.API.platform.drive;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleDriveUploadPilotControllerTest {
    private final PlatformAuthorizationService authz=mock(PlatformAuthorizationService.class);
    private final GoogleDriveUploadSessionService sessions=mock(GoogleDriveUploadSessionService.class);
    private final GoogleDriveUploadPublicationService publication=mock(GoogleDriveUploadPublicationService.class);
    private final GoogleDriveUploadLedger ledger=mock(GoogleDriveUploadLedger.class);
    private final Authentication auth=mock(Authentication.class);
    private final User user=new User();
    private final GoogleDriveUploadPilotController.Begin request=new GoogleDriveUploadPilotController.Begin(
        "anchor_folder","child_folder","art.png","image/png",100);
    private GoogleDriveUploadPilotController controller(boolean enabled,boolean cleanup) {
        return new GoogleDriveUploadPilotController(authz,sessions,publication,ledger,enabled,cleanup,"staging_folder",5368709120L);
    }
    @Test void ownerGateAndCleanupRequiredBeforeIssuingUrl() {
        when(authz.requireOwner(auth)).thenReturn(user);
        assertEquals(503,assertThrows(ResponseStatusException.class,()->controller(true,false).begin(auth,request)).getStatusCode().value());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->controller(false,true).begin(auth,request)).getStatusCode().value());
        verifyNoInteractions(sessions,publication,ledger);
    }
    @Test void nonOwnerCannotStartOrPublish() {
        when(authz.requireOwner(auth)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class,()->controller(true,true).begin(auth,request));
        assertThrows(ResponseStatusException.class,()->controller(true,true).publish(auth,"id"));
        verifyNoInteractions(sessions,publication,ledger);
    }
    @Test void enabledOwnerReceivesNoStoreRedactedCapability() {
        when(authz.requireOwner(auth)).thenReturn(user);
        when(authz.resolve(auth)).thenReturn(new PlatformAuthorizationService.Actor(user,
            new PlatformAuthorizationService.Access(true,true,Set.of())));
        var uri=URI.create("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&upload_id=secret");
        when(sessions.begin(auth,"anchor_folder","child_folder","art.png","image/png",100))
            .thenReturn(new GoogleDriveUploadSessionService.Start("session",uri));
        var c=controller(true,true);
        assertTrue(c.capability(auth).getBody().enabled());
        var response=c.begin(auth,request);
        assertEquals("no-store",response.getHeaders().getCacheControl());
        assertEquals(uri.toString(),response.getBody().uploadUri());
        assertFalse(response.getBody().toString().contains("secret"));
    }
    @Test void disablePilotStillAllowsOwnerToCancel() {
        when(authz.requireOwner(auth)).thenReturn(user);
        when(publication.cancel(auth,"id")).thenReturn(new GoogleDriveUploadPublicationService.Result("id","CANCELED"));
        assertEquals("CANCELED",controller(false,false).cancel(auth,"id").getBody().status());
    }
}
