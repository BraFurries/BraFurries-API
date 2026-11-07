package com.Brafurries.API.auth.social;

import java.util.Map;
import java.util.List;
import com.Brafurries.API.auth.common.dto.AuthDtos.LoginResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.UserResponse;
import com.Brafurries.API.auth.social.AuthSocialService.ExistingUserLogin;
import com.Brafurries.API.auth.social.AuthSocialService.RegistrationRequired;
import com.Brafurries.API.auth.social.AuthSocialService.PendingSocialRegistration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialOAuth2AuthenticationSuccessHandlerTest {

    @Mock
    private AuthSocialService authSocialService;

    @Mock
    private DiscordSelfServiceLinkService discordLinkService;

    @Test
    void redirectsToFrontendErrorWhenUnexpectedOauthCompletionErrorHappens() throws Exception {
        SocialOAuth2AuthenticationSuccessHandler handler = new SocialOAuth2AuthenticationSuccessHandler(authSocialService, policy(), discordLinkService);
        DefaultOAuth2User principal = new DefaultOAuth2User(
            java.util.List.of(),
            Map.of("id", "123456789"),
            "id"
        );
        OAuth2AuthenticationToken authentication = new OAuth2AuthenticationToken(
            principal,
            principal.getAuthorities(),
            "discord"
        );
        when(authSocialService.loginOAuth2(authentication)).thenThrow(new IllegalStateException("conflito de identidade"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

        assertEquals(302, response.getStatus());
        assertTrue(response.getRedirectedUrl().startsWith("https://brafurries.com.br/social-login?error="));
        assertTrue(response.getRedirectedUrl().contains("Falha%20inesperada%20ao%20finalizar%20login%20social"));
    }

    @Test
    void oauthSignupRedirectNeverPublishesSuggestedProviderEmail() throws Exception {
        OAuth2AuthenticationToken auth = authentication();
        PendingSocialRegistration challenge = new PendingSocialRegistration(
            "opaque-registration-ticket", "discord", "123456789",
            "private-provider@example.com", true, "Dragon", null,
            Instant.now().plusSeconds(600));
        when(authSocialService.loginOAuth2(auth)).thenReturn(new RegistrationRequired(challenge));

        MockHttpServletResponse response = new MockHttpServletResponse();
        new SocialOAuth2AuthenticationSuccessHandler(authSocialService, policy(), discordLinkService)
            .onAuthenticationSuccess(new MockHttpServletRequest(), response, auth);

        String url = response.getRedirectedUrl();
        assertTrue(url.startsWith("https://brafurries.com.br/social-login#"));
        assertTrue(url.contains("registration_token=opaque-registration-ticket"));
        assertTrue(!url.contains("private-provider@example.com"));
        assertTrue(!url.contains("email="));
        assertTrue(!url.contains("display_name="));
        assertTrue(!url.contains("access_token="));
    }

    @Test
    void sendsEncodedGlobalProfileImageUrlInExistingUserCallback() throws Exception {
        OAuth2AuthenticationToken authentication = authentication();
        LoginResponse login = new LoginResponse(
            "access", "refresh", "Bearer", 3600,
            new UserResponse(1L, "Luna", "luna@example.com", "https://cdn.example/avatar novo.webp", List.of("member"), List.of())
        );
        when(authSocialService.loginOAuth2(authentication)).thenReturn(new ExistingUserLogin(login));

        MockHttpServletResponse response = new MockHttpServletResponse();
        new SocialOAuth2AuthenticationSuccessHandler(authSocialService, policy(), discordLinkService)
            .onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

        assertEquals(302, response.getStatus());
        assertTrue(response.getRedirectedUrl().contains("profile_image_url=https://cdn.example/avatar%20novo.webp"), response.getRedirectedUrl());
    }

    @Test
    void keepsOauthCallbackSafeWhenGlobalProfileImageIsAbsent() throws Exception {
        OAuth2AuthenticationToken authentication = authentication();
        LoginResponse login = new LoginResponse(
            "access", "refresh", "Bearer", 3600,
            new UserResponse(1L, "Luna", "luna@example.com", null, List.of("member"), List.of())
        );
        when(authSocialService.loginOAuth2(authentication)).thenReturn(new ExistingUserLogin(login));

        MockHttpServletResponse response = new MockHttpServletResponse();
        new SocialOAuth2AuthenticationSuccessHandler(authSocialService, policy(), discordLinkService)
            .onAuthenticationSuccess(new MockHttpServletRequest(), response, authentication);

        assertEquals(302, response.getStatus());
        assertTrue(response.getRedirectedUrl().contains("profile_image_url="));
    }

    @Test
    void returnsExistingUserToTrustedFirebasePreviewAndConsumesOrigin() throws Exception {
        OAuth2AuthenticationToken authentication = authentication();
        LoginResponse login = new LoginResponse(
            "access", "refresh", "Bearer", 3600,
            new UserResponse(1L, "Luna", "luna@example.com", null, List.of("member"), List.of())
        );
        when(authSocialService.loginOAuth2(authentication)).thenReturn(new ExistingUserLogin(login));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(
            SocialFrontendOriginPolicy.SESSION_ATTRIBUTE,
            "https://brafurries--pr94-example.web.app"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SocialOAuth2AuthenticationSuccessHandler(authSocialService, policy(), discordLinkService)
            .onAuthenticationSuccess(request, response, authentication);

        assertTrue(
            response.getRedirectedUrl().startsWith("https://brafurries--pr94-example.web.app/social-login#"),
            response.getRedirectedUrl()
        );
        assertNull(request.getSession(false).getAttribute(SocialFrontendOriginPolicy.SESSION_ATTRIBUTE));
    }

    @Test
    void revalidatesStoredOriginAndFallsBackToProduction() throws Exception {
        OAuth2AuthenticationToken authentication = authentication();
        LoginResponse login = new LoginResponse(
            "access", "refresh", "Bearer", 3600,
            new UserResponse(1L, "Luna", "luna@example.com", null, List.of("member"), List.of())
        );
        when(authSocialService.loginOAuth2(authentication)).thenReturn(new ExistingUserLogin(login));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute(
            SocialFrontendOriginPolicy.SESSION_ATTRIBUTE,
            "https://evil.example"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SocialOAuth2AuthenticationSuccessHandler(authSocialService, policy(), discordLinkService)
            .onAuthenticationSuccess(request, response, authentication);

        assertTrue(
            response.getRedirectedUrl().startsWith("https://brafurries.com.br/social-login#"),
            response.getRedirectedUrl()
        );
    }

    @Test
    void proofCallbackVerifiesDiscordWithoutSwitchingGoogleSessionOrIssuingTokens() throws Exception {
        OAuth2AuthenticationToken auth = authentication();
        MockHttpServletRequest request = new MockHttpServletRequest();
        String ticket = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGH";
        request.getSession(true).setAttribute(
            DiscordSelfServiceLinkService.SESSION_TICKET, ticket
        );
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SocialOAuth2AuthenticationSuccessHandler(authSocialService, policy(), discordLinkService)
            .onAuthenticationSuccess(request, response, auth);

        org.mockito.Mockito.verify(discordLinkService).verifiedDiscordOAuth(ticket, auth);
        org.mockito.Mockito.verifyNoInteractions(authSocialService);
        assertEquals(302, response.getStatus());
        assertTrue(response.getRedirectedUrl().startsWith(
            "https://brafurries.com.br/painel/perfil#discord_link_ticket="));
        assertTrue(response.getRedirectedUrl().contains(ticket));
        assertNull(request.getSession(false).getAttribute(DiscordSelfServiceLinkService.SESSION_TICKET));
        assertTrue(!response.getRedirectedUrl().contains("access_token="));
    }

    @Test
    void failedDiscordProofDoesNotLeakProviderOrSessionToken() throws Exception {
        OAuth2AuthenticationToken auth = authentication();
        MockHttpServletRequest request = new MockHttpServletRequest();
        String ticket = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGH";
        request.getSession(true).setAttribute(DiscordSelfServiceLinkService.SESSION_TICKET, ticket);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("untrusted discord value"))
            .when(discordLinkService).verifiedDiscordOAuth(ticket, auth);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new SocialOAuth2AuthenticationSuccessHandler(authSocialService, policy(), discordLinkService)
            .onAuthenticationSuccess(request, response, auth);

        org.mockito.Mockito.verifyNoInteractions(authSocialService);
        assertTrue(response.getRedirectedUrl().startsWith(
            "https://brafurries.com.br/painel/perfil?discord_link_error="));
        assertTrue(!response.getRedirectedUrl().contains(ticket));
        assertTrue(!response.getRedirectedUrl().contains("untrusted"));
    }

    private SocialFrontendOriginPolicy policy() {
        return new SocialFrontendOriginPolicy("https://brafurries.com.br");
    }

    private OAuth2AuthenticationToken authentication() {
        DefaultOAuth2User principal = new DefaultOAuth2User(List.of(), Map.of("id", "123456789"), "id");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "discord");
    }
}
