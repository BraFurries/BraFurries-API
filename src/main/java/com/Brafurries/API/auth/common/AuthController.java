package com.Brafurries.API.auth.common;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.Brafurries.API.auth.common.dto.AuthDtos.ClientTokenRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.ClientTokenResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.ConfirmEmailRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.ConfirmEmailResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.ForgotPasswordRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.ForgotPasswordResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.LoginRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.LoginResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.LogoutRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.LogoutResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.RefreshTokenRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.RefreshTokenResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.RegisterRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.RegisterResponse;
import com.Brafurries.API.auth.common.dto.AuthDtos.ResetPasswordRequest;
import com.Brafurries.API.auth.common.dto.AuthDtos.ResetPasswordResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@AllArgsConstructor
@RequestMapping("")
@Tag(name = "Autenticação", description = "Endpoints de autenticação local e OAuth client")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Login com e-mail/senha")
    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.loginCommon(request);
    }

    @Operation(summary = "Cadastro de usuário local")
    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.registerCommon(request);
    }

    @Operation(summary = "Confirma e-mail de cadastro local")
    @PostMapping("/auth/confirm-email")
    public ConfirmEmailResponse confirmEmail(@Valid @RequestBody ConfirmEmailRequest request) {
        return authService.confirmEmail(request);
    }

    @Operation(summary = "Solicita redefinição de senha")
    @PostMapping("/auth/forgot-password")
    public ForgotPasswordResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return authService.forgotPassword(request);
    }

    @Operation(summary = "Redefine a senha com token")
    @PostMapping("/auth/reset-password")
    public ResetPasswordResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return authService.resetPassword(request);
    }

    @Operation(summary = "Renova token de acesso")
    @PostMapping("/auth/refresh")
    public RefreshTokenResponse refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        return authService.refreshToken(request);
    }

    @Operation(summary = "Realiza logout e invalida refresh token")
    @PostMapping("/auth/logout")
    public LogoutResponse logout(@Valid @RequestBody LogoutRequest request) {
        return authService.logout(request);
    }

    @Operation(summary = "Gera token para clientes third-party")
    @PostMapping("/oauth/token")
    @ResponseStatus(HttpStatus.OK)
    public ClientTokenResponse clientToken(@Valid @RequestBody ClientTokenRequest request) {
        return authService.clientToken(request);
    }
}
