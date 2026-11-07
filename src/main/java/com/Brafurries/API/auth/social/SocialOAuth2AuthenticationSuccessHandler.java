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
    private final DiscordSelfServiceLinkService discordLinkService;
    private final SocialLoginOAuthProofService googleLoginProofs;
    private final SocialFrontendOriginPolicy frontendOriginPolicy;

    public SocialOAuth2AuthenticationSuccessHandler(
        AuthSocialService authSocialService,
        SocialFrontendOriginPolicy frontendOriginPolicy,
        DiscordSelfServiceLinkService discordLinkService,
        SocialLoginOAuthProofService googleLoginProofs
    ) {
        this.authSocialService = authSocialService;
        this.discordLinkService = discordLinkService;
        this.googleLoginProofs = googleLoginProofs;
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
        String linkTicket = consumeDiscordLinkTicket(request);
        String googleTicket = consumeGoogleLoginTicket(request);

        try {
            if (googleTicket != null) {
                if (linkTicket != null) throw new BadCredentialsException("Conflicting OAuth operations");
                googleLoginProofs.verifiedGoogleOAuth(googleTicket, oauth2Authentication);
                response.sendRedirect(googleConfirmationRedirectUri(googleTicket, frontendOrigin));
                return;
            }
            if (linkTicket != null) {
                // This is proof of Discord possession, not a new website login.
                // Never issue replacement JWTs or switch the current Google session.
                discordLinkService.verifiedDiscordOAuth(linkTicket, oauth2Authentication);
                response.sendRedirect(linkConfirmationRedirectUri(linkTicket, frontendOrigin));
                return;
            }
            OAuth2LoginResult result = authSocialService.loginOAuth2(oauth2Authentication);
            response.sendRedirect(redirectUri(result, frontendOrigin));
        } catch (BadCredentialsException | IllegalArgumentException | org.springframework.web.server.ResponseStatusException ex) {
            response.sendRedirect(googleTicket != null
                ? googleErrorRedirectUri(frontendOrigin)
                : linkTicket != null
                    ? linkErrorRedirectUri("Não foi possível confirmar esse Discord. Inicie novamente a vinculação.", frontendOrigin)
                    : errorRedirectUri(ex.getMessage(), frontendOrigin));
        } catch (RuntimeException ex) {
            LOGGER.error("Erro inesperado ao finalizar OAuth2", ex);
            response.sendRedirect(googleTicket != null
                ? googleErrorRedirectUri(frontendOrigin)
                : linkTicket != null
                    ? linkErrorRedirectUri("Falha ao verificar Discord. Tente novamente.", frontendOrigin)
                    : errorRedirectUri("Falha inesperada ao finalizar login social", frontendOrigin));
        }
    }

    @Override
    public void onAuthenticationFailure(
        HttpServletRequest request,
        HttpServletResponse response,
        org.springframework.security.core.AuthenticationException exception
    ) throws IOException {
        String frontendOrigin = consumeFrontendOrigin(request);
        String linkTicket = consumeDiscordLinkTicket(request);
        String googleTicket = consumeGoogleLoginTicket(request);
        response.sendRedirect(googleTicket != null
            ? googleErrorRedirectUri(frontendOrigin)
            : linkTicket != null
            ? linkErrorRedirectUri("Autorização Discord não concluída. Você pode tentar novamente.", frontendOrigin)
            : errorRedirectUri(exception.getMessage(), frontendOrigin));
    }

    private String consumeGoogleLoginTicket(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        Object ticket = session.getAttribute(SocialLoginOAuthProofService.GOOGLE_SESSION_TICKET);
        session.removeAttribute(SocialLoginOAuthProofService.GOOGLE_SESSION_TICKET);
        return ticket instanceof String value && !value.isBlank() ? value : null;
    }

    private String googleConfirmationRedirectUri(String ticket, String frontendOrigin) {
        return UriComponentsBuilder.fromUriString(frontendOrigin)
            .path("/painel/perfil")
            .fragment("google_login_ticket=" + encode(ticket))
            .build(true).toUriString();
    }

    private String googleErrorRedirectUri(String frontendOrigin) {
        return UriComponentsBuilder.fromUriString(frontendOrigin)
            .path("/painel/perfil")
            .queryParam("google_login_error", "Não foi possível comprovar Google. Tente novamente.")
            .build().encode().toUriString();
    }

    private String consumeDiscordLinkTicket(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session == null) return null;
        Object ticket = session.getAttribute(DiscordSelfServiceLinkService.SESSION_TICKET);
        session.removeAttribute(DiscordSelfServiceLinkService.SESSION_TICKET);
        return ticket instanceof String value && !value.isBlank() ? value : null;
    }

    private String linkConfirmationRedirectUri(String ticket, String frontendOrigin) {
        return UriComponentsBuilder.fromUriString(frontendOrigin)
            .path("/painel/perfil")
            .fragment("discord_link_ticket=" + encode(ticket))
            .build(true).toUriString();
    }

    private String linkErrorRedirectUri(String message, String frontendOrigin) {
        return UriComponentsBuilder.fromUriString(frontendOrigin)
            .path("/painel/perfil")
            .queryParam("discord_link_error", encode(message))
            .build(true).toUriString();
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
        // The OAuth provider e-mail is sensitive and only a suggestion. Never
        // put it in a query parameter. The frontend consumes this one-use
        // opaque proof from the fragment and obtains the suggestion via POST.
        return UriComponentsBuilder.fromUriString(frontendOrigin)
            .path("/social-login")
            .fragment("requires_registration=true&registration_token="
                + encode(pendingRegistration.token()))
            .build(true).toUriString();
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
