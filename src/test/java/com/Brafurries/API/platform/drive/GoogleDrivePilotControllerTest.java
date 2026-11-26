package com.Brafurries.API.platform.drive;

import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserSocialIdentity;
import com.Brafurries.API.platform.PlatformAuthorizationService;
import com.Brafurries.API.repository.user.UserSocialIdentityRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleDrivePilotControllerTest {
    final PlatformAuthorizationService authz=mock(PlatformAuthorizationService.class);
    final UserSocialIdentityRepository identities=mock(UserSocialIdentityRepository.class);
    final Authentication auth=mock(Authentication.class);
    GoogleDrivePilotController controller(boolean enabled) {
        return new GoogleDrivePilotController(authz,identities,enabled,
            "pilot.apps.googleusercontent.com","placeholder_browser_key_12345",
            "123456789","private_test_folder");
    }
    void grant() {
        User user=new User();
        user.setId(7);
        when(authz.requireOwner(auth)).thenReturn(user);
    }
    @Test void nonOwnerCannotDiscoverFolderOrBrowserConfig() {
        when(authz.requireOwner(auth)).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,
            ()->controller(true).config(auth)).getStatusCode());
        verifyNoInteractions(identities);
    }
    @Test void disabledPilotExposesNoConfiguration() {
        grant();
        var result=controller(false).config(auth);
        assertFalse(result.getBody().enabled());
        assertNull(result.getBody().folderId());
        assertEquals("no-store",result.getHeaders().getCacheControl());
        verifyNoInteractions(identities);
    }
    @Test void missingVerifiedIdentityExposesNoConfiguration() {
        grant();
        when(identities.findByUserIdAndProvider(7,"google")).thenReturn(List.of());
        var config=controller(true).config(auth).getBody();
        assertTrue(config.enabled());
        assertFalse(config.linked());
        assertNull(config.folderId());
    }
    @Test void returnsOnlyOwnVerifiedGoogleIdentity() {
        grant();
        UserSocialIdentity identity=new UserSocialIdentity();
        identity.setProviderSubject("verified-google-subject");
        identity.setVerifiedEmail("owner@example.invalid");
        when(identities.findByUserIdAndProvider(7,"google")).thenReturn(List.of(identity));
        var config=controller(true).config(auth).getBody();
        assertTrue(config.linked());
        assertEquals("verified-google-subject",config.googleSubject());
        assertEquals("private_test_folder",config.folderId());
    }
}
