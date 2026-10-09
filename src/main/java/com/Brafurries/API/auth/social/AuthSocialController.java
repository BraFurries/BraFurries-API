package com.Brafurries.API.auth.social;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import com.Brafurries.API.auth.common.dto.AuthDtos.LoginResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.RegisterResponse;
import com.Brafurries.API.auth.social.dto.AuthSocialDtos.CompleteSocialRegisterRequest;
import com.Brafurries.API.auth.social.dto.AuthSocialDtos.SocialLoginRequest;
import com.Brafurries.API.auth.social.dto.AuthSocialDtos.SocialRegisterRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@AllArgsConstructor
@RequestMapping("")
@Tag(name = "Autenticação Social", description = "Endpoints de login e cadastro social")
public class AuthSocialController {

    private final AuthSocialService authSocialService;
    private final SocialFrontendOriginPolicy frontendOriginPolicy;

    @Operation(summary = "Inicia login OAuth2 via Discord")
    @GetMapping("/auth/oauth2/discord")
    public RedirectView startDiscordOAuth2Login(
        @RequestParam(name = "frontend_origin", required = false) String frontendOrigin,
        HttpServletRequest request
    ) {
        return startOAuth2Login("discord", frontendOrigin, request);
    }

    @Operation(summary = "Inicia login OAuth2 via Google")
    @GetMapping("/auth/oauth2/google")
    public RedirectView startGoogleOAuth2Login(
        @RequestParam(name = "frontend_origin", required = false) String frontendOrigin,
        HttpServletRequest request
    ) {
        return startOAuth2Login("google", frontendOrigin, request);
    }

    @Operation(summary = "Login via Google")
    @PostMapping("/auth/login/google")
    public LoginResponse loginGoogle(@Valid @RequestBody SocialLoginRequest request) {
        return authSocialService.loginGoogle(request);
    }

    @Operation(summary = "Login via Discord")
    @PostMapping("/auth/login/discord")
    public LoginResponse loginDiscord(@Valid @RequestBody SocialLoginRequest request) {
        return authSocialService.loginDiscord(request);
    }

    @Operation(summary = "Cadastro via Google")
    @PostMapping("/auth/register/google")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse registerGoogle(@Valid @RequestBody SocialRegisterRequest request) {
        return authSocialService.registerGoogle(request);
    }

    @Operation(summary = "Cadastro via Discord")
    @PostMapping("/auth/register/discord")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse registerDiscord(@Valid @RequestBody SocialRegisterRequest request) {
        return authSocialService.registerDiscord(request);
    }

    @Operation(summary = "Finaliza cadastro social iniciado via OAuth2")
    @PostMapping("/auth/social-register")
    public LoginResponse completeSocialRegistration(@Valid @RequestBody CompleteSocialRegisterRequest request) {
        return authSocialService.completeSocialRegistration(request);
    }

    private RedirectView startOAuth2Login(
        String provider,
        String frontendOrigin,
        HttpServletRequest request
    ) {
        String resolvedOrigin = frontendOriginPolicy.resolve(frontendOrigin);
        request.getSession(true).setAttribute(SocialFrontendOriginPolicy.SESSION_ATTRIBUTE, resolvedOrigin);
        return new RedirectView("/oauth2/authorization/" + provider);
    }
}
