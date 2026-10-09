package com.Brafurries.API.auth.social;

import com.Brafurries.API.auth.common.dto.AuthDtos.LoginResponse;
import com.Brafurries.API.auth.social.AuthSocialService.ExistingUserLogin;
import com.Brafurries.API.auth.social.AuthSocialService.OAuth2LoginResult;
import com.Brafurries.API.auth.social.AuthSocialService.PendingSocialRegistration;
import com.Brafurries.API.auth.social.AuthSocialService.RegistrationRequired;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.StringJoiner;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@Component
public class SocialOAuth2AuthenticationSuccessHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(SocialOAuth2AuthenticationSuccessHandler.class);

    private final AuthSocialService authSocialService;
    private final SocialFrontendOriginPolicy frontendOriginPolicy;

    public SocialOAuth2AuthenticationSuccessHandler(
        AuthSocialService authSocialService,
        SocialFrontendOriginPolicy frontendOriginPolicy
    ) {
        this.authSocialService = authSocialService;
        this.frontendOriginPolicy = frontendOriginPolicy;
    }

    @Override
    public void onAuthenticationSuccess(
        HttpServletRequest request,
        HttpServletResponse response,
        Authentication authentication
    ) throws IOException, ServletException {
        if (!(authentication instanceof OAuth2AuthenticationToken oauth2Authentication)) {
            throw new ServletException("Autenticacao OAuth2 invalida");
        }

        String frontendOrigin = consumeFrontendOrigin(request);

        try {
            OAuth2LoginResult result = authSocialService.loginOAuth2(oauth2Authentication);
            response.sendRedirect(redirectUri(result, frontendOrigin));
        } catch (BadCredentialsException | IllegalArgumentException ex) {
            response.sendRedirect(errorRedirectUri(ex.getMessage(), frontendOrigin));
        } catch (RuntimeException ex) {
            LOGGER.error("Erro inesperado ao finalizar login OAuth2", ex);
            response.sendRedirect(errorRedirectUri("Falha inesperada ao finalizar login social", frontendOrigin));
        }
    }

    @Override
    public void onAuthenticationFailure(
        HttpServletRequest request,
        HttpServletResponse response,
        org.springframework.security.core.AuthenticationException exception
    ) throws IOException {
        String frontendOrigin = consumeFrontendOrigin(request);
        response.sendRedirect(errorRedirectUri(exception.getMessage(), frontendOrigin));
    }

    private String redirectUri(OAuth2LoginResult result, String frontendOrigin) {
        if (result instanceof ExistingUserLogin existingUserLogin) {
            return successRedirectUri(existingUserLogin.loginResponse(), frontendOrigin);
        }

        if (result instanceof RegistrationRequired registrationRequired) {
            return registrationRedirectUri(registrationRequired.pendingRegistration(), frontendOrigin);
        }

        throw new IllegalStateException("Resultado OAuth2 nao suportado");
    }

    private String successRedirectUri(LoginResponse loginResponse, String frontendOrigin) {
        String fragment = "access_token=" + encode(loginResponse.accessToken())
            + "&refresh_token=" + encode(loginResponse.refreshToken())
            + "&token_type=" + encode(loginResponse.tokenType())
            + "&expires_in=" + loginResponse.expiresIn()
            + "&user_id=" + loginResponse.user().id()
            + "&name=" + encode(loginResponse.user().name())
            + "&email=" + encode(loginResponse.user().email())
            + "&profile_image_url=" + encode(loginResponse.user().profileImageUrl())
            + "&roles=" + encode(String.join(",", loginResponse.user().roles()))
            + "&permissions=" + encode(String.join(",", loginResponse.user().permissions()));

        return UriComponentsBuilder.fromUriString(frontendOrigin)
            .path("/social-login")
            .fragment(fragment)
            .build(true)
            .toUriString();
    }

    private String registrationRedirectUri(PendingSocialRegistration pendingRegistration, String frontendOrigin) {
        StringJoiner query = new StringJoiner("&")
            .add("requires_registration=true")
            .add("registration_token=" + encode(pendingRegistration.token()))
            .add("email=" + encode(pendingRegistration.email()))
            .add("display_name=" + encode(pendingRegistration.displayName()))
            .add("provider=" + encode(pendingRegistration.provider()));

        return UriComponentsBuilder.fromUriString(frontendOrigin)
            .path("/social-login")
            .query(query.toString())
            .build(true)
            .toUriString();
    }

    private String errorRedirectUri(String message, String frontendOrigin) {
        return UriComponentsBuilder.fromUriString(frontendOrigin)
            .path("/social-login")
            .queryParam("error", encode(firstNonBlank(message, "Falha no login social")))
            .build(true)
            .toUriString();
    }

    private String consumeFrontendOrigin(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) {
            return frontendOriginPolicy.defaultOrigin();
        }

        Object storedOrigin = session.getAttribute(SocialFrontendOriginPolicy.SESSION_ATTRIBUTE);
        session.removeAttribute(SocialFrontendOriginPolicy.SESSION_ATTRIBUTE);
        return frontendOriginPolicy.resolve(storedOrigin instanceof String value ? value : null);
    }

    private String encode(String value) {
        return UriUtils.encodeQueryParam(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
