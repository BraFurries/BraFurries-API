package com.Brafurries.API.auth.social;

import java.util.Map;
import java.util.List;
import com.Brafurries.API.auth.common.dto.AuthDtos.LoginResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.UserResponse;
import com.Brafurries.API.auth.social.AuthSocialService.ExistingUserLogin;
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

    @Test
    void redirectsToFrontendErrorWhenUnexpectedOauthCompletionErrorHappens() throws Exception {
        SocialOAuth2AuthenticationSuccessHandler handler = new SocialOAuth2AuthenticationSuccessHandler(authSocialService, policy());
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
    void sendsEncodedGlobalProfileImageUrlInExistingUserCallback() throws Exception {
        OAuth2AuthenticationToken authentication = authentication();
        LoginResponse login = new LoginResponse(
            "access", "refresh", "Bearer", 3600,
            new UserResponse(1L, "Luna", "luna@example.com", "https://cdn.example/avatar novo.webp", List.of("member"), List.of())
        );
        when(authSocialService.loginOAuth2(authentication)).thenReturn(new ExistingUserLogin(login));

        MockHttpServletResponse response = new MockHttpServletResponse();
        new SocialOAuth2AuthenticationSuccessHandler(authSocialService, policy())
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
        new SocialOAuth2AuthenticationSuccessHandler(authSocialService, policy())
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

        new SocialOAuth2AuthenticationSuccessHandler(authSocialService, policy())
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

        new SocialOAuth2AuthenticationSuccessHandler(authSocialService, policy())
            .onAuthenticationSuccess(request, response, authentication);

        assertTrue(
            response.getRedirectedUrl().startsWith("https://brafurries.com.br/social-login#"),
            response.getRedirectedUrl()
        );
    }

    private SocialFrontendOriginPolicy policy() {
        return new SocialFrontendOriginPolicy("https://brafurries.com.br");
    }

    private OAuth2AuthenticationToken authentication() {
        DefaultOAuth2User principal = new DefaultOAuth2User(List.of(), Map.of("id", "123456789"), "id");
        return new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "discord");
    }
}
