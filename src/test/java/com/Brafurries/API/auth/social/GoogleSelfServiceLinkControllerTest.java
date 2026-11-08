package com.Brafurries.API.auth.social;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoogleSelfServiceLinkControllerTest {
    @Test
    void passesOnlyVerifiedSiteSessionAndGoogleTokenToService() {
        AuthSocialService service = mock(AuthSocialService.class);
        var auth = new UsernamePasswordAuthenticationToken("owner@example.com", null, List.of());
        when(service.linkGoogleToAuthenticatedAccount(auth, "google-id-token"))
            .thenReturn("VERIFIED");

        var result = new GoogleSelfServiceLinkController(service).link(
            auth, new GoogleSelfServiceLinkController.GoogleLinkRequest("google-id-token"));

        assertEquals("VERIFIED", result.status());
        verify(service).linkGoogleToAuthenticatedAccount(auth, "google-id-token");
    }
}
