package com.Brafurries.API.auth.social;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class AuthSocialControllerTest {

    private final SocialFrontendOriginPolicy policy =
        new SocialFrontendOriginPolicy("https://brafurries.com.br");
    private final AuthSocialController controller =
        new AuthSocialController(mock(AuthSocialService.class), policy);

    @Test
    void storesTrustedPreviewOriginBeforeStartingDiscordOauth() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        var redirect = controller.startDiscordOAuth2Login(
            "https://brafurries--pr94-example.web.app",
            request
        );

        assertEquals("/oauth2/authorization/discord", redirect.getUrl());
        assertEquals(
            "https://brafurries--pr94-example.web.app",
            request.getSession(false).getAttribute(SocialFrontendOriginPolicy.SESSION_ATTRIBUTE)
        );
    }

    @Test
    void replacesUntrustedOriginWithConfiguredFrontendBeforeStartingOauth() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        controller.startGoogleOAuth2Login("https://evil.example", request);

        assertEquals(
            "https://brafurries.com.br",
            request.getSession(false).getAttribute(SocialFrontendOriginPolicy.SESSION_ATTRIBUTE)
        );
    }
}
